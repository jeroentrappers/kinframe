package api

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/jeroentrappers/kinframe/server/internal/shop"
)

const jsonShop = `
enabled: true
seller: { name: Example BV, email: shop@example.org }
notify_email: orders@example.org
products: [{ id: frame-10, name: 'Familijst 10.1"', price_cents: 7900, max_qty: 5 }]
shipping: { countries: [BE], flat_cents: 695 }
`

// Discovery publishes the notice, the DIY texts and secondary links, and
// leaves them out when the shop file has none.
func TestShopJSONNoticeDIY(t *testing.T) {
	encode := func(src string) map[string]any {
		t.Helper()
		c, err := shop.Parse([]byte(src), "kinframe")
		if err != nil {
			t.Fatal(err)
		}
		b, err := json.Marshal(shopJSON(c))
		if err != nil {
			t.Fatal(err)
		}
		var m map[string]any
		if err := json.Unmarshal(b, &m); err != nil {
			t.Fatal(err)
		}
		return m
	}
	plain := encode(jsonShop)
	for _, k := range []string{"notice", "diy", "elsewhere"} {
		if _, ok := plain[k]; ok {
			t.Errorf("%s present without config: %v", k, plain[k])
		}
	}

	m := encode(jsonShop + `
notice: { en: "We resell converted Denver frames.\n\nOr convert one yourself.", nl: Wij verkopen omgezette Denver-kaders. }
diy: { body: { en: Buy a Denver frame. } }
elsewhere:
  - { countries: [BE], retailer: Amazon, product: Denver PFF-1024, url: "https://www.amazon.com.be/dp/B1", affiliate: true }
  - { countries: [BE], retailer: bol.com, product: Denver PFF-1024, url: "https://www.bol.com/be/p/1", secondary: true }
`)
	notice := m["notice"].(map[string]any)
	if notice["en"] != "We resell converted Denver frames.\n\nOr convert one yourself." || notice["nl"] != "Wij verkopen omgezette Denver-kaders." {
		t.Errorf("notice: %v", notice)
	}
	diy := m["diy"].(map[string]any)
	if _, ok := diy["title"]; ok || diy["body"].(map[string]any)["en"] != "Buy a Denver frame." {
		t.Errorf("diy: %v", diy)
	}
	links := m["elsewhere"].([]any)
	first, second := links[0].(map[string]any), links[1].(map[string]any)
	if first["affiliate"] != true || first["secondary"] != nil || second["secondary"] != true || second["affiliate"] != false {
		t.Errorf("elsewhere: %v", links)
	}
	// An empty diy block is left out.
	if _, ok := encode(jsonShop + "diy: {}\n")["diy"]; ok {
		t.Error("empty diy published")
	}
	b, _ := json.Marshal(m)
	if strings.Contains(string(b), "orders@example.org") {
		t.Error("notify address published")
	}
}
