package api

// Temporary stubs until the Telegram bridge is implemented (docs/spec/telegram.md).

import (
	"context"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
)

func (s *Server) ListFeedTelegramChats(ctx context.Context, request gen.ListFeedTelegramChatsRequestObject) (gen.ListFeedTelegramChatsResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) CreateFeedTelegramCode(ctx context.Context, request gen.CreateFeedTelegramCodeRequestObject) (gen.CreateFeedTelegramCodeResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) ListFrameTelegramChats(ctx context.Context, request gen.ListFrameTelegramChatsRequestObject) (gen.ListFrameTelegramChatsResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) CreateFrameTelegramCode(ctx context.Context, request gen.CreateFrameTelegramCodeRequestObject) (gen.CreateFrameTelegramCodeResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) UnlinkTelegram(ctx context.Context, request gen.UnlinkTelegramRequestObject) (gen.UnlinkTelegramResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) GetTelegramLink(ctx context.Context, request gen.GetTelegramLinkRequestObject) (gen.GetTelegramLinkResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) CreateTelegramLinkCode(ctx context.Context, request gen.CreateTelegramLinkCodeRequestObject) (gen.CreateTelegramLinkCodeResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) DisconnectTelegramChat(ctx context.Context, request gen.DisconnectTelegramChatRequestObject) (gen.DisconnectTelegramChatResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) GetTelegramChat(ctx context.Context, request gen.GetTelegramChatRequestObject) (gen.GetTelegramChatResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) UpdateTelegramChat(ctx context.Context, request gen.UpdateTelegramChatRequestObject) (gen.UpdateTelegramChatResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) ListTelegramContributors(ctx context.Context, request gen.ListTelegramContributorsRequestObject) (gen.ListTelegramContributorsResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) UpdateTelegramContributor(ctx context.Context, request gen.UpdateTelegramContributorRequestObject) (gen.UpdateTelegramContributorResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) RevokeTelegramCode(ctx context.Context, request gen.RevokeTelegramCodeRequestObject) (gen.RevokeTelegramCodeResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) GetTelegramCode(ctx context.Context, request gen.GetTelegramCodeRequestObject) (gen.GetTelegramCodeResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}
