// Package ws implements the realtime hub for the device and user websockets
// (README §6). Online means "has a connected websocket".
package ws

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"
)

// PingInterval is the server ping period (README §6).
const PingInterval = 25 * time.Second

// StatusUnauthorized is the close code for revoked devices and expired user tokens.
const StatusUnauthorized websocket.StatusCode = 4401

var (
	ErrOffline = errors.New("frame offline")
	ErrTimeout = errors.New("command timeout")
)

// CommandResult is a frame's reply to a command.
type CommandResult struct {
	OK        bool    `json:"ok"`
	ErrorCode *string `json:"error_code"`
}

type conn struct {
	ws        *websocket.Conn
	send      chan []byte
	closeOnce sync.Once
	closed    chan struct{}
	closeCode websocket.StatusCode
	closeWhy  string
}

func newConn(c *websocket.Conn) *conn {
	return &conn{ws: c, send: make(chan []byte, 64), closed: make(chan struct{})}
}

func (c *conn) enqueue(b []byte) bool {
	select {
	case <-c.closed:
		return false
	default:
	}
	select {
	case c.send <- b:
		return true
	default:
		// Slow consumer: drop the connection; the client reconnects and resyncs.
		c.close(websocket.StatusPolicyViolation, "too slow")
		return false
	}
}

func (c *conn) close(code websocket.StatusCode, why string) {
	c.closeOnce.Do(func() {
		c.closeCode, c.closeWhy = code, why
		close(c.closed)
	})
}

// writer pumps queued messages and pings until the connection closes.
func (c *conn) writer(ctx context.Context) {
	ping := time.NewTicker(PingInterval)
	defer ping.Stop()
	for {
		select {
		case b := <-c.send:
			wctx, cancel := context.WithTimeout(ctx, 10*time.Second)
			err := c.ws.Write(wctx, websocket.MessageText, b)
			cancel()
			if err != nil {
				c.close(websocket.StatusGoingAway, "write failed")
			}
		case <-ping.C:
			pctx, cancel := context.WithTimeout(ctx, 15*time.Second)
			err := c.ws.Ping(pctx)
			cancel()
			if err != nil {
				c.close(websocket.StatusGoingAway, "ping timeout")
			}
		case <-c.closed:
			// Flush what is queued (e.g. "revoked") before closing.
		flush:
			for {
				select {
				case b := <-c.send:
					wctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
					_ = c.ws.Write(wctx, websocket.MessageText, b)
					cancel()
				default:
					break flush
				}
			}
			code := c.closeCode
			if code == 0 {
				code = websocket.StatusNormalClosure
			}
			_ = c.ws.Close(code, c.closeWhy)
			return
		case <-ctx.Done():
			c.close(websocket.StatusGoingAway, "server shutdown")
		}
	}
}

type deviceConn struct {
	*conn
	deviceID uuid.UUID
	frameID  *uuid.UUID
}

// Hub tracks connections. Callbacks may be nil.
type Hub struct {
	Log *slog.Logger
	// OnDevice is called when a device connects or disconnects.
	OnDevice func(deviceID uuid.UUID, frameID *uuid.UUID, online bool)
	// OnState receives "state" messages from frames.
	OnState func(deviceID uuid.UUID, msg json.RawMessage)

	mu      sync.Mutex
	devices map[uuid.UUID]*deviceConn
	frames  map[uuid.UUID]*deviceConn
	users   map[uuid.UUID]map[*conn]struct{}
	pending map[string]chan CommandResult
}

func NewHub(log *slog.Logger) *Hub {
	return &Hub{
		Log:     log,
		devices: map[uuid.UUID]*deviceConn{},
		frames:  map[uuid.UUID]*deviceConn{},
		users:   map[uuid.UUID]map[*conn]struct{}{},
		pending: map[string]chan CommandResult{},
	}
}

func marshal(msg any) []byte {
	b, err := json.Marshal(msg)
	if err != nil {
		panic(err)
	}
	return b
}

func accept(w http.ResponseWriter, r *http.Request) (*websocket.Conn, error) {
	// Tokens travel in headers or the query string, never cookies, so
	// cross-origin connections carry no ambient authority.
	return websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
}

// ServeDevice runs a device websocket until it closes. hello is the first message.
func (h *Hub) ServeDevice(w http.ResponseWriter, r *http.Request, deviceID uuid.UUID, frameID *uuid.UUID, hello any) {
	c, err := accept(w, r)
	if err != nil {
		return
	}
	c.SetReadLimit(64 << 10)
	dc := &deviceConn{conn: newConn(c), deviceID: deviceID, frameID: frameID}

	h.mu.Lock()
	if old := h.devices[deviceID]; old != nil {
		old.close(websocket.StatusPolicyViolation, "replaced by a newer connection")
	}
	h.devices[deviceID] = dc
	if frameID != nil {
		h.frames[*frameID] = dc
	}
	h.mu.Unlock()
	if h.OnDevice != nil {
		h.OnDevice(deviceID, frameID, true)
	}

	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	dc.enqueue(marshal(hello))
	go h.readDevice(ctx, dc)
	dc.writer(ctx)

	h.mu.Lock()
	current := h.devices[deviceID] == dc
	if current {
		delete(h.devices, deviceID)
	}
	if dc.frameID != nil && h.frames[*dc.frameID] == dc {
		delete(h.frames, *dc.frameID)
	}
	fid := dc.frameID
	h.mu.Unlock()
	if current && h.OnDevice != nil {
		h.OnDevice(deviceID, fid, false)
	}
}

func (h *Hub) readDevice(ctx context.Context, dc *deviceConn) {
	defer dc.close(websocket.StatusNormalClosure, "")
	for {
		_, b, err := dc.ws.Read(ctx)
		if err != nil {
			return
		}
		var msg struct {
			Type      string  `json:"type"`
			ID        string  `json:"id"`
			OK        bool    `json:"ok"`
			ErrorCode *string `json:"error_code"`
		}
		if json.Unmarshal(b, &msg) != nil {
			continue
		}
		switch msg.Type {
		case "state":
			if h.OnState != nil {
				h.OnState(dc.deviceID, b)
			}
		case "command_result":
			h.mu.Lock()
			ch := h.pending[msg.ID]
			delete(h.pending, msg.ID)
			h.mu.Unlock()
			if ch != nil {
				ch <- CommandResult{OK: msg.OK, ErrorCode: msg.ErrorCode}
			}
		}
	}
}

// BindFrame associates a connected device with a frame (after a claim or replace).
func (h *Hub) BindFrame(deviceID, frameID uuid.UUID) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if dc := h.devices[deviceID]; dc != nil {
		f := frameID
		dc.frameID = &f
		h.frames[frameID] = dc
	}
}

// SendToDevice queues a message for a device; false if it is not connected.
func (h *Hub) SendToDevice(deviceID uuid.UUID, msg any) bool {
	h.mu.Lock()
	dc := h.devices[deviceID]
	h.mu.Unlock()
	return dc != nil && dc.enqueue(marshal(msg))
}

// SendToFrame queues a message for a frame's connected device.
func (h *Hub) SendToFrame(frameID uuid.UUID, msg any) bool {
	h.mu.Lock()
	dc := h.frames[frameID]
	h.mu.Unlock()
	return dc != nil && dc.enqueue(marshal(msg))
}

// FrameOnline reports whether the frame's device is connected.
func (h *Hub) FrameOnline(frameID uuid.UUID) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.frames[frameID] != nil
}

// OnlineFrames counts connected, claimed frames.
func (h *Hub) OnlineFrames() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.frames)
}

// Revoke tells a device it was removed and closes it with 4401.
func (h *Hub) Revoke(deviceID uuid.UUID) {
	h.mu.Lock()
	dc := h.devices[deviceID]
	h.mu.Unlock()
	if dc == nil {
		return
	}
	dc.enqueue(marshal(map[string]string{"type": "revoked"}))
	dc.close(StatusUnauthorized, "device revoked")
}

// Command sends a command to the frame and waits for its result.
func (h *Hub) Command(ctx context.Context, frameID uuid.UUID, commandType string, args map[string]any, timeout time.Duration) (CommandResult, error) {
	id := uuid.NewString()
	ch := make(chan CommandResult, 1)
	h.mu.Lock()
	dc := h.frames[frameID]
	if dc != nil {
		h.pending[id] = ch
	}
	h.mu.Unlock()
	if dc == nil {
		return CommandResult{}, ErrOffline
	}
	defer func() {
		h.mu.Lock()
		delete(h.pending, id)
		h.mu.Unlock()
	}()
	if args == nil {
		args = map[string]any{}
	}
	if !dc.enqueue(marshal(map[string]any{"type": "command", "id": id, "command_type": commandType, "args": args})) {
		return CommandResult{}, ErrOffline
	}
	t := time.NewTimer(timeout)
	defer t.Stop()
	select {
	case res := <-ch:
		return res, nil
	case <-t.C:
		return CommandResult{}, ErrTimeout
	case <-dc.closed:
		return CommandResult{}, ErrOffline
	case <-ctx.Done():
		return CommandResult{}, ctx.Err()
	}
}

// ServeUser runs a user websocket until it closes or the token expires.
func (h *Hub) ServeUser(w http.ResponseWriter, r *http.Request, userID uuid.UUID, expires time.Time) {
	c, err := accept(w, r)
	if err != nil {
		return
	}
	uc := newConn(c)
	h.mu.Lock()
	if h.users[userID] == nil {
		h.users[userID] = map[*conn]struct{}{}
	}
	h.users[userID][uc] = struct{}{}
	h.mu.Unlock()

	ctx := c.CloseRead(r.Context())
	go func() {
		t := time.NewTimer(time.Until(expires))
		defer t.Stop()
		select {
		case <-t.C:
			uc.close(StatusUnauthorized, "token expired")
		case <-ctx.Done():
			uc.close(websocket.StatusNormalClosure, "")
		case <-uc.closed:
		}
	}()
	uc.writer(ctx)

	h.mu.Lock()
	delete(h.users[userID], uc)
	if len(h.users[userID]) == 0 {
		delete(h.users, userID)
	}
	h.mu.Unlock()
}

// SendToUser queues a message on all of the user's connections; false if none.
func (h *Hub) SendToUser(userID uuid.UUID, msg any) bool {
	h.mu.Lock()
	conns := make([]*conn, 0, len(h.users[userID]))
	for c := range h.users[userID] {
		conns = append(conns, c)
	}
	h.mu.Unlock()
	b := marshal(msg)
	sent := false
	for _, c := range conns {
		if c.enqueue(b) {
			sent = true
		}
	}
	return sent
}
