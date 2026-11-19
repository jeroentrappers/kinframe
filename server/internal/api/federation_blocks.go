package api

import (
	"context"
	"strings"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/federation"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Blocked servers (README §5.22 "Trust"): a family's owner and admins block a server for their family, the
// operator for the whole instance; both end the subscriptions with it.

func blockedServerJSON(b *store.BlockedServer) gen.BlockedServer {
	return gen.BlockedServer{Id: b.ID, Host: b.Host, FeedId: b.FeedID, ServerName: b.ServerName, Reason: b.Reason,
		CreatedByName: b.CreatedByName, CreatedAt: b.CreatedAt}
}

// blockHost reads BlockedServerCreate: a host or a server URL, as host[:port] in lower case.
func blockHost(b *gen.BlockedServerCreate) (string, error) {
	raw := ""
	switch {
	case b.ServerUrl != nil && strings.TrimSpace(*b.ServerUrl) != "":
		raw = strings.TrimSpace(*b.ServerUrl)
	case b.Host != nil && strings.TrimSpace(*b.Host) != "":
		raw = "https://" + strings.TrimSpace(*b.Host)
	}
	_, host, err := federation.NormalizeBaseURL(raw)
	if raw == "" || err != nil {
		return "", problem.Validation("host", "required", "give a host or a server URL")
	}
	return host, nil
}

func (s *Server) knownServerName(ctx context.Context, host string) *string {
	var name *string
	_ = s.st.Q().QueryRow(ctx, `SELECT server_name FROM remote_servers WHERE host = $1 AND server_name IS NOT NULL LIMIT 1`, host).Scan(&name)
	return name
}

func (s *Server) ListFeedBlockedServers(ctx context.Context, request gen.ListFeedBlockedServersRequestObject) (gen.ListFeedBlockedServersResponseObject, error) {
	v, err := s.feedView(ctx, s.st.Q(), request.FeedId)
	if err != nil {
		return nil, err
	}
	if err := requireFeedAdmin(v); err != nil {
		return nil, err
	}
	rows, err := s.st.ListBlockedServers(ctx, &request.FeedId)
	if err != nil {
		return nil, err
	}
	out := make([]gen.BlockedServer, len(rows))
	for i := range rows {
		out[i] = blockedServerJSON(&rows[i])
	}
	return ok(out), nil
}

func (s *Server) BlockFeedServer(ctx context.Context, request gen.BlockFeedServerRequestObject) (gen.BlockFeedServerResponseObject, error) {
	v, err := s.feedView(ctx, s.st.Q(), request.FeedId)
	if err != nil {
		return nil, err
	}
	if err := requireFeedAdmin(v); err != nil {
		return nil, err
	}
	host, err := blockHost(request.Body)
	if err != nil {
		return nil, err
	}
	feedID := request.FeedId
	b, ended, err := s.block(ctx, host, &feedID, request.Body.Reason)
	if err != nil {
		return nil, err
	}
	for _, id := range ended {
		s.publishRemoteSubscriber(ctx, id, nil)
	}
	s.wakeFederation()
	return created(blockedServerJSON(b)), nil
}

// block records a block and ends the remote subscriptions with that host here (feed nil: every family).
func (s *Server) block(ctx context.Context, host string, feedID *uuid.UUID, reason *string) (*store.BlockedServer, []uuid.UUID, error) {
	if reason != nil && strings.TrimSpace(*reason) == "" {
		reason = nil
	}
	var b *store.BlockedServer
	var ended []uuid.UUID
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		var err error
		b, err = tx.BlockServer(ctx, store.BlockedServer{ID: store.NewID(), Host: host, FeedID: feedID, ServerName: s.knownServerName(ctx, host),
			Reason: reason, CreatedBy: &me(ctx).ID})
		if err != nil {
			return err
		}
		if feedID != nil {
			ended, err = s.endRemoteSubscribersWhere(ctx, tx, "server_blocked", `x.feed_id = $1 AND rs.host = $2`, *feedID, host)
		} else {
			ended, err = s.endRemoteSubscribersWhere(ctx, tx, "server_blocked", `rs.host = $1`, host)
		}
		return err
	})
	return b, ended, err
}

func (s *Server) UnblockFeedServer(ctx context.Context, request gen.UnblockFeedServerRequestObject) (gen.UnblockFeedServerResponseObject, error) {
	v, err := s.feedView(ctx, s.st.Q(), request.FeedId)
	if err != nil {
		return nil, err
	}
	if err := requireFeedAdmin(v); err != nil {
		return nil, err
	}
	feedID := request.FeedId
	if _, err := s.st.UnblockServer(ctx, &feedID, request.BlockedServerId); err != nil {
		return nil, err
	}
	return noContent(), nil
}

func (s *Server) OperatorListBlockedServers(ctx context.Context, _ gen.OperatorListBlockedServersRequestObject) (gen.OperatorListBlockedServersResponseObject, error) {
	if err := requireOperator(ctx); err != nil {
		return nil, err
	}
	rows, err := s.st.ListBlockedServers(ctx, nil)
	if err != nil {
		return nil, err
	}
	out := make([]gen.BlockedServer, len(rows))
	for i := range rows {
		out[i] = blockedServerJSON(&rows[i])
	}
	return ok(out), nil
}

func (s *Server) OperatorBlockServer(ctx context.Context, request gen.OperatorBlockServerRequestObject) (gen.OperatorBlockServerResponseObject, error) {
	if err := requireOperator(ctx); err != nil {
		return nil, err
	}
	host, err := blockHost(request.Body)
	if err != nil {
		return nil, err
	}
	b, ended, err := s.block(ctx, host, nil, request.Body.Reason)
	if err != nil {
		return nil, err
	}
	for _, id := range ended {
		s.publishRemoteSubscriber(ctx, id, nil)
	}
	// Frames here following families there end too (A is told).
	follows, err := s.st.RemoteFollowsWhere(ctx, s.st.Q(), `rs.host = $1 AND f.status IN ('pending', 'active', 'error')`, host)
	if err != nil {
		return nil, err
	}
	for i := range follows {
		s.endFollow(ctx, follows[i].ID, "server_blocked", nil, true)
	}
	s.wakeFederation()
	return created(blockedServerJSON(b)), nil
}

func (s *Server) OperatorUnblockServer(ctx context.Context, request gen.OperatorUnblockServerRequestObject) (gen.OperatorUnblockServerResponseObject, error) {
	if err := requireOperator(ctx); err != nil {
		return nil, err
	}
	if _, err := s.st.UnblockServer(ctx, nil, request.BlockedServerId); err != nil {
		return nil, err
	}
	return noContent(), nil
}

func (s *Server) OperatorListRemoteServers(ctx context.Context, _ gen.OperatorListRemoteServersRequestObject) (gen.OperatorListRemoteServersResponseObject, error) {
	if err := requireOperator(ctx); err != nil {
		return nil, err
	}
	rows, err := s.st.ListRemoteServers(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]gen.RemoteServer, len(rows))
	for i := range rows {
		r := &rows[i]
		o := remoteServerJSON(r.BaseURL, r.Host, r.ServerID, r.ServerName)
		o.LastContactAt, o.LastError = r.LastContactAt, r.LastError
		o.Subscribers, o.Follows, o.Blocked = ptr(r.Subscribers), ptr(r.Follows), ptr(r.Blocked)
		out[i] = o
	}
	return ok(out), nil
}
