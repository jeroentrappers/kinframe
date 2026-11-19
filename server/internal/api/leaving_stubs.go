package api

// Temporary stubs for leaving a family (server-leaving), until they are built (docs/spec/leaving.md,
// docs/spec/leaving-servers-work.md). Each answers 501 Not Implemented. The owning area replaces this whole file.

import (
	"context"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
)

func (s *Server) AnswerKeepRequest(ctx context.Context, request gen.AnswerKeepRequestRequestObject) (gen.AnswerKeepRequestResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) CreateKeepRequest(ctx context.Context, request gen.CreateKeepRequestRequestObject) (gen.CreateKeepRequestResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) GetKeepRequest(ctx context.Context, request gen.GetKeepRequestRequestObject) (gen.GetKeepRequestResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) GetLeavePreview(ctx context.Context, request gen.GetLeavePreviewRequestObject) (gen.GetLeavePreviewResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) GetMemoryPreferences(ctx context.Context, request gen.GetMemoryPreferencesRequestObject) (gen.GetMemoryPreferencesResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ListFeedKeepRequests(ctx context.Context, request gen.ListFeedKeepRequestsRequestObject) (gen.ListFeedKeepRequestsResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ListFeedTakenAway(ctx context.Context, request gen.ListFeedTakenAwayRequestObject) (gen.ListFeedTakenAwayResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ListFormerPlaces(ctx context.Context, request gen.ListFormerPlacesRequestObject) (gen.ListFormerPlacesResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ListFrameKeepRequests(ctx context.Context, request gen.ListFrameKeepRequestsRequestObject) (gen.ListFrameKeepRequestsResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ListFrameTakenAway(ctx context.Context, request gen.ListFrameTakenAwayRequestObject) (gen.ListFrameTakenAwayResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ListMyKeepRequests(ctx context.Context, request gen.ListMyKeepRequestsRequestObject) (gen.ListMyKeepRequestsResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) OperatorListOwnershipLog(ctx context.Context, request gen.OperatorListOwnershipLogRequestObject) (gen.OperatorListOwnershipLogResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) OperatorSearchOwnership(ctx context.Context, request gen.OperatorSearchOwnershipRequestObject) (gen.OperatorSearchOwnershipResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) OperatorTransferFeedOwner(ctx context.Context, request gen.OperatorTransferFeedOwnerRequestObject) (gen.OperatorTransferFeedOwnerResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) OperatorTransferFrameOwner(ctx context.Context, request gen.OperatorTransferFrameOwnerRequestObject) (gen.OperatorTransferFrameOwnerResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) TakeAwayFromFeed(ctx context.Context, request gen.TakeAwayFromFeedRequestObject) (gen.TakeAwayFromFeedResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) TakeAwayFromFrame(ctx context.Context, request gen.TakeAwayFromFrameRequestObject) (gen.TakeAwayFromFrameResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) UpdateMemoryPreferences(ctx context.Context, request gen.UpdateMemoryPreferencesRequestObject) (gen.UpdateMemoryPreferencesResponseObject, error) {
	return nil, notImplemented()
}
