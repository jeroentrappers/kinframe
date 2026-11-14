package api

import (
	"context"
	"sync"
	"time"

	openapi_types "github.com/oapi-codegen/runtime/types"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/shop"
)

// stockCache keeps the shop's stock counts for a short while: discovery is
// called often, and a count that is a few seconds old is good enough there.
// Orders read the counts fresh.
type stockCache struct {
	mu sync.Mutex
	at time.Time
	v  map[string]int
}

const stockCacheTTL = 30 * time.Second

func (c *stockCache) forget() {
	c.mu.Lock()
	c.v = nil
	c.mu.Unlock()
}

// stockCached is the stock for discovery.
func (s *Server) stockCached(ctx context.Context) (map[string]int, error) {
	c := &s.shopStock
	c.mu.Lock()
	if c.v != nil && time.Since(c.at) < stockCacheTTL {
		v := c.v
		c.mu.Unlock()
		return v, nil
	}
	c.mu.Unlock()
	v, err := s.stockNow(ctx)
	if err != nil {
		return nil, err
	}
	c.mu.Lock()
	c.v, c.at = v, time.Now()
	c.mu.Unlock()
	return v, nil
}

func (s *Server) stockNow(ctx context.Context) (map[string]int, error) { return s.st.ShopStock(ctx) }

// withStock adds the stock and dispatch days to the shop in discovery. The
// count per product is capped at what one order may take.
func withStock(out *gen.Shop, c *shop.Config, stock map[string]int, now time.Time) {
	for i := range out.Products {
		n := min(stock[out.Products[i].Id], out.Products[i].MaxQty)
		out.Products[i].InStock = ptr(n)
	}
	out.Stock = &struct {
		ShipsInStock    openapi_types.Date `json:"ships_in_stock"`
		ShipsOutOfStock openapi_types.Date `json:"ships_out_of_stock"`
	}{openapi_types.Date{Time: c.ShipsOn(now, true)}, openapi_types.Date{Time: c.ShipsOn(now, false)}}
}

func dateJSON(t *time.Time) *openapi_types.Date {
	if t == nil {
		return nil
	}
	return &openapi_types.Date{Time: *t}
}

// stockOrNotFound is the shop when it manages stock.
func (s *Server) stockOrNotFound() (*shop.Config, error) {
	c, err := s.shopOrNotFound()
	if err != nil {
		return nil, err
	}
	if c.Stock == nil {
		return nil, problem.New(problem.NotFound, "")
	}
	return c, nil
}

func (s *Server) OperatorListShopStock(ctx context.Context, _ gen.OperatorListShopStockRequestObject) (gen.OperatorListShopStockResponseObject, error) {
	if err := requireOperator(ctx); err != nil {
		return nil, err
	}
	c, err := s.stockOrNotFound()
	if err != nil {
		return nil, err
	}
	stock, err := s.stockNow(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]gen.ShopStockItem, len(c.Products))
	for i, p := range c.Products {
		out[i] = gen.ShopStockItem{ProductId: p.ID, Quantity: stock[p.ID]}
	}
	return ok(map[string]any{"stock": out}), nil
}

func (s *Server) OperatorSetShopStock(ctx context.Context, req gen.OperatorSetShopStockRequestObject) (gen.OperatorSetShopStockResponseObject, error) {
	if err := requireOperator(ctx); err != nil {
		return nil, err
	}
	c, err := s.stockOrNotFound()
	if err != nil {
		return nil, err
	}
	if _, found := c.Product(req.ProductId); !found {
		return nil, problem.New(problem.NotFound, "")
	}
	n := req.Body.Quantity
	if n < 0 || n > 100000 {
		return nil, problem.New(problem.ValidationFailed, "some reservation details are not valid").WithField("quantity", "maximum")
	}
	if err := s.st.SetShopStock(ctx, req.ProductId, n); err != nil {
		return nil, err
	}
	s.shopStock.forget()
	s.log.Info("shop stock set", "product", req.ProductId, "quantity", n)
	return ok(gen.ShopStockItem{ProductId: req.ProductId, Quantity: n}), nil
}
