package api

// Temporary stubs for family social: timeline, threads, reactions and circles (server-social), until they are built
// (docs/spec/family-social.md, docs/spec/family-social-work.md). Each answers 501 Not Implemented.
// The owning area replaces this whole file.

import (
	"context"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
)

func (s *Server) GetTimeline(ctx context.Context, request gen.GetTimelineRequestObject) (gen.GetTimelineResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) GetPost(ctx context.Context, request gen.GetPostRequestObject) (gen.GetPostResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ListComments(ctx context.Context, request gen.ListCommentsRequestObject) (gen.ListCommentsResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) CreateComment(ctx context.Context, request gen.CreateCommentRequestObject) (gen.CreateCommentResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) UpdateComment(ctx context.Context, request gen.UpdateCommentRequestObject) (gen.UpdateCommentResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) DeleteComment(ctx context.Context, request gen.DeleteCommentRequestObject) (gen.DeleteCommentResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) SetThreadState(ctx context.Context, request gen.SetThreadStateRequestObject) (gen.SetThreadStateResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) MarkThreadRead(ctx context.Context, request gen.MarkThreadReadRequestObject) (gen.MarkThreadReadResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) SetThreadMute(ctx context.Context, request gen.SetThreadMuteRequestObject) (gen.SetThreadMuteResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ListReactions(ctx context.Context, request gen.ListReactionsRequestObject) (gen.ListReactionsResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) SetReaction(ctx context.Context, request gen.SetReactionRequestObject) (gen.SetReactionResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ListCircles(ctx context.Context, request gen.ListCirclesRequestObject) (gen.ListCirclesResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) CreateCircle(ctx context.Context, request gen.CreateCircleRequestObject) (gen.CreateCircleResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) GetCircle(ctx context.Context, request gen.GetCircleRequestObject) (gen.GetCircleResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) UpdateCircle(ctx context.Context, request gen.UpdateCircleRequestObject) (gen.UpdateCircleResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) DeleteCircle(ctx context.Context, request gen.DeleteCircleRequestObject) (gen.DeleteCircleResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) AddCircleMembers(ctx context.Context, request gen.AddCircleMembersRequestObject) (gen.AddCircleMembersResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) UpdateCircleMember(ctx context.Context, request gen.UpdateCircleMemberRequestObject) (gen.UpdateCircleMemberResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) RemoveCircleMember(ctx context.Context, request gen.RemoveCircleMemberRequestObject) (gen.RemoveCircleMemberResponseObject, error) {
	return nil, notImplemented()
}
