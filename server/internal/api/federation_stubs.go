package api

// Temporary stubs for families on different servers (server-federation), until they are built
// (docs/spec/multi-server.md, docs/spec/leaving-servers-work.md). Each answers 501 Not Implemented. The owning area
// replaces this whole file.

import (
	"context"
	"net/http"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
)

func (s *Server) BlockFeedServer(ctx context.Context, request gen.BlockFeedServerRequestObject) (gen.BlockFeedServerResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) CreateRemoteSubscription(ctx context.Context, request gen.CreateRemoteSubscriptionRequestObject) (gen.CreateRemoteSubscriptionResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) DeleteRemoteSubscription(ctx context.Context, request gen.DeleteRemoteSubscriptionRequestObject) (gen.DeleteRemoteSubscriptionResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ListFeedBlockedServers(ctx context.Context, request gen.ListFeedBlockedServersRequestObject) (gen.ListFeedBlockedServersResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ListRemoteSubscriptions(ctx context.Context, request gen.ListRemoteSubscriptionsRequestObject) (gen.ListRemoteSubscriptionsResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) OperatorBlockServer(ctx context.Context, request gen.OperatorBlockServerRequestObject) (gen.OperatorBlockServerResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) OperatorListBlockedServers(ctx context.Context, request gen.OperatorListBlockedServersRequestObject) (gen.OperatorListBlockedServersResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) OperatorListRemoteServers(ctx context.Context, request gen.OperatorListRemoteServersRequestObject) (gen.OperatorListRemoteServersResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) OperatorUnblockServer(ctx context.Context, request gen.OperatorUnblockServerRequestObject) (gen.OperatorUnblockServerResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) PreviewRemoteInvite(ctx context.Context, request gen.PreviewRemoteInviteRequestObject) (gen.PreviewRemoteInviteResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) UnblockFeedServer(ctx context.Context, request gen.UnblockFeedServerRequestObject) (gen.UnblockFeedServerResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) UpdateRemoteSubscription(ctx context.Context, request gen.UpdateRemoteSubscriptionRequestObject) (gen.UpdateRemoteSubscriptionResponseObject, error) {
	return nil, notImplemented()
}

// Hand-written server-to-server endpoints (excluded in gen/cfg.yaml, mounted in router.go): they verify the RFC 9421
// signature over the raw request before reading it (README §5.22 "Signatures").

// federationSubscribe is POST /federation/v1/subscriptions (operationId federationSubscribe).
func (s *Server) federationSubscribe(w http.ResponseWriter, r *http.Request) {
	writeNotImplemented(w, r)
}

// federationUnsubscribe is DELETE /federation/v1/subscriptions/{subscriptionId} (federationUnsubscribe).
func (s *Server) federationUnsubscribe(w http.ResponseWriter, r *http.Request) {
	writeNotImplemented(w, r)
}

// federationChanges is GET /federation/v1/subscriptions/{subscriptionId}/changes (federationChanges).
func (s *Server) federationChanges(w http.ResponseWriter, r *http.Request) { writeNotImplemented(w, r) }

// federationActivity is POST /federation/v1/subscriptions/{subscriptionId}/activity (federationActivity).
func (s *Server) federationActivity(w http.ResponseWriter, r *http.Request) {
	writeNotImplemented(w, r)
}

// federationPing is POST /federation/v1/ping (federationPing).
func (s *Server) federationPing(w http.ResponseWriter, r *http.Request) { writeNotImplemented(w, r) }
