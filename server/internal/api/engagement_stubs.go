package api

// Temporary stubs for encouragement to share: freshness, photo requests, reminders, streaks, prompts, summaries and milestones (server-engagement), until they are built
// (docs/spec/family-social.md, docs/spec/family-social-work.md). Each answers 501 Not Implemented.
// The owning area replaces this whole file.

import (
	"context"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
)

func (s *Server) TagPostPrompt(ctx context.Context, request gen.TagPostPromptRequestObject) (gen.TagPostPromptResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) GetFeedEngagement(ctx context.Context, request gen.GetFeedEngagementRequestObject) (gen.GetFeedEngagementResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) UpdateFeedEngagement(ctx context.Context, request gen.UpdateFeedEngagementRequestObject) (gen.UpdateFeedEngagementResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) GetFeedStreak(ctx context.Context, request gen.GetFeedStreakRequestObject) (gen.GetFeedStreakResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ListFeedPrompts(ctx context.Context, request gen.ListFeedPromptsRequestObject) (gen.ListFeedPromptsResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) SetFeedPrompt(ctx context.Context, request gen.SetFeedPromptRequestObject) (gen.SetFeedPromptResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ResetFeedPrompt(ctx context.Context, request gen.ResetFeedPromptRequestObject) (gen.ResetFeedPromptResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ListPromptLibrary(ctx context.Context, request gen.ListPromptLibraryRequestObject) (gen.ListPromptLibraryResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) GetOnThisDay(ctx context.Context, request gen.GetOnThisDayRequestObject) (gen.GetOnThisDayResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) GetWeeklySummary(ctx context.Context, request gen.GetWeeklySummaryRequestObject) (gen.GetWeeklySummaryResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) GetFeedRecap(ctx context.Context, request gen.GetFeedRecapRequestObject) (gen.GetFeedRecapResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ListMyMilestones(ctx context.Context, request gen.ListMyMilestonesRequestObject) (gen.ListMyMilestonesResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) UpdateMyMilestone(ctx context.Context, request gen.UpdateMyMilestoneRequestObject) (gen.UpdateMyMilestoneResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) GetReminderPreferences(ctx context.Context, request gen.GetReminderPreferencesRequestObject) (gen.GetReminderPreferencesResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) UpdateReminderPreferences(ctx context.Context, request gen.UpdateReminderPreferencesRequestObject) (gen.UpdateReminderPreferencesResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) GetPhotoRequest(ctx context.Context, request gen.GetPhotoRequestRequestObject) (gen.GetPhotoRequestResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) ListFramePhotoRequests(ctx context.Context, request gen.ListFramePhotoRequestsRequestObject) (gen.ListFramePhotoRequestsResponseObject, error) {
	return nil, notImplemented()
}

func (s *Server) DeviceRequestPhotos(ctx context.Context, request gen.DeviceRequestPhotosRequestObject) (gen.DeviceRequestPhotosResponseObject, error) {
	return nil, notImplemented()
}
