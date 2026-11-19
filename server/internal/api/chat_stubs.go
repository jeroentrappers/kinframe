package api

// Temporary stubs for chats, blocks, reports and link previews (server-chat), until they are built
// (docs/spec/family-social.md, docs/spec/family-social-work.md). Each answers 501 Not Implemented.
// The owning area replaces this whole file.

import (
	"context"
	"net/http"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
)

func (s *Server) ListConversations(ctx context.Context, request gen.ListConversationsRequestObject) (gen.ListConversationsResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) CreateConversation(ctx context.Context, request gen.CreateConversationRequestObject) (gen.CreateConversationResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) GetConversation(ctx context.Context, request gen.GetConversationRequestObject) (gen.GetConversationResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) UpdateConversation(ctx context.Context, request gen.UpdateConversationRequestObject) (gen.UpdateConversationResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) DeleteConversationPhoto(ctx context.Context, request gen.DeleteConversationPhotoRequestObject) (gen.DeleteConversationPhotoResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) AddConversationMembers(ctx context.Context, request gen.AddConversationMembersRequestObject) (gen.AddConversationMembersResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) UpdateConversationMember(ctx context.Context, request gen.UpdateConversationMemberRequestObject) (gen.UpdateConversationMemberResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) RemoveConversationMember(ctx context.Context, request gen.RemoveConversationMemberRequestObject) (gen.RemoveConversationMemberResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) UpdateConversationPreferences(ctx context.Context, request gen.UpdateConversationPreferencesRequestObject) (gen.UpdateConversationPreferencesResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ListMessages(ctx context.Context, request gen.ListMessagesRequestObject) (gen.ListMessagesResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) SendMessage(ctx context.Context, request gen.SendMessageRequestObject) (gen.SendMessageResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) GetMessage(ctx context.Context, request gen.GetMessageRequestObject) (gen.GetMessageResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) UpdateMessage(ctx context.Context, request gen.UpdateMessageRequestObject) (gen.UpdateMessageResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) DeleteMessage(ctx context.Context, request gen.DeleteMessageRequestObject) (gen.DeleteMessageResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) PutMessageOnFrame(ctx context.Context, request gen.PutMessageOnFrameRequestObject) (gen.PutMessageOnFrameResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) MarkConversationRead(ctx context.Context, request gen.MarkConversationReadRequestObject) (gen.MarkConversationReadResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) SendTyping(ctx context.Context, request gen.SendTypingRequestObject) (gen.SendTypingResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ListContacts(ctx context.Context, request gen.ListContactsRequestObject) (gen.ListContactsResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) GetPerson(ctx context.Context, request gen.GetPersonRequestObject) (gen.GetPersonResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) GetBadge(ctx context.Context, request gen.GetBadgeRequestObject) (gen.GetBadgeResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) CreateLinkPreview(ctx context.Context, request gen.CreateLinkPreviewRequestObject) (gen.CreateLinkPreviewResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ListBlocks(ctx context.Context, request gen.ListBlocksRequestObject) (gen.ListBlocksResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) BlockUser(ctx context.Context, request gen.BlockUserRequestObject) (gen.BlockUserResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) UnblockUser(ctx context.Context, request gen.UnblockUserRequestObject) (gen.UnblockUserResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) CreateReport(ctx context.Context, request gen.CreateReportRequestObject) (gen.CreateReportResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ListFeedReports(ctx context.Context, request gen.ListFeedReportsRequestObject) (gen.ListFeedReportsResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ListOperatorReports(ctx context.Context, request gen.ListOperatorReportsRequestObject) (gen.ListOperatorReportsResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ResolveReport(ctx context.Context, request gen.ResolveReportRequestObject) (gen.ResolveReportResponseObject, error) {
	return nil, notImplemented()
}

// putConversationPhoto is hand-written (binary body, like putAvatar; excluded in gen/cfg.yaml) and mounted in
// router.go.
func (s *Server) putConversationPhoto(w http.ResponseWriter, r *http.Request) {
	writeNotImplemented(w, r)
}
