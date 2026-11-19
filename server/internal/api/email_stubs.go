package api

// Temporary stubs until email to frame is implemented (docs/spec/email-inbox.md).

import (
	"context"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
)

func (s *Server) DeleteEmailAddress(ctx context.Context, request gen.DeleteEmailAddressRequestObject) (gen.DeleteEmailAddressResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) GetEmailAddress(ctx context.Context, request gen.GetEmailAddressRequestObject) (gen.GetEmailAddressResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) UpdateEmailAddress(ctx context.Context, request gen.UpdateEmailAddressRequestObject) (gen.UpdateEmailAddressResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) DeleteEmailHeldMessage(ctx context.Context, request gen.DeleteEmailHeldMessageRequestObject) (gen.DeleteEmailHeldMessageResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) GetEmailHeldMessage(ctx context.Context, request gen.GetEmailHeldMessageRequestObject) (gen.GetEmailHeldMessageResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) DeliverEmailHeldMessage(ctx context.Context, request gen.DeliverEmailHeldMessageRequestObject) (gen.DeliverEmailHeldMessageResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) ListEmailMessages(ctx context.Context, request gen.ListEmailMessagesRequestObject) (gen.ListEmailMessagesResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) ListEmailPendingSenders(ctx context.Context, request gen.ListEmailPendingSendersRequestObject) (gen.ListEmailPendingSendersResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) ApproveEmailSender(ctx context.Context, request gen.ApproveEmailSenderRequestObject) (gen.ApproveEmailSenderResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) RejectEmailSender(ctx context.Context, request gen.RejectEmailSenderRequestObject) (gen.RejectEmailSenderResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) RegenerateEmailAddress(ctx context.Context, request gen.RegenerateEmailAddressRequestObject) (gen.RegenerateEmailAddressResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) ListEmailSenders(ctx context.Context, request gen.ListEmailSendersRequestObject) (gen.ListEmailSendersResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) AddEmailSender(ctx context.Context, request gen.AddEmailSenderRequestObject) (gen.AddEmailSenderResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) RemoveEmailSender(ctx context.Context, request gen.RemoveEmailSenderRequestObject) (gen.RemoveEmailSenderResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) UpdateEmailSender(ctx context.Context, request gen.UpdateEmailSenderRequestObject) (gen.UpdateEmailSenderResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) ListFeedEmailAddresses(ctx context.Context, request gen.ListFeedEmailAddressesRequestObject) (gen.ListFeedEmailAddressesResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) CreateFeedEmailAddress(ctx context.Context, request gen.CreateFeedEmailAddressRequestObject) (gen.CreateFeedEmailAddressResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) ListFrameEmailAddresses(ctx context.Context, request gen.ListFrameEmailAddressesRequestObject) (gen.ListFrameEmailAddressesResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}

func (s *Server) CreateFrameEmailAddress(ctx context.Context, request gen.CreateFrameEmailAddressRequestObject) (gen.CreateFrameEmailAddressResponseObject, error) {
	return nil, problem.New(problem.FeatureDisabled, "not implemented yet")
}
