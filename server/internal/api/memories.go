package api

// Memories that leave people out (docs/spec/leaving.md §4.7, README §5.21): the private filter and where it applies
// (On this day, the monthly recap as rendered for the user, the weekly summary; the "Send something to Oma's
// frame" suggestions use On this day). Frames never: they show what the family sent.

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

const (
	maxHiddenPeople   = 200
	maxHiddenMemories = 5000
	recapCandidates   = 50 // posts looked at for the next most-loved one
)

func (s *Server) GetMemoryPreferences(ctx context.Context, _ gen.GetMemoryPreferencesRequestObject) (gen.GetMemoryPreferencesResponseObject, error) {
	out, err := s.memoryPreferences(ctx, me(ctx).ID)
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

func (s *Server) memoryPreferences(ctx context.Context, userID uuid.UUID) (gen.MemoryPreferences, error) {
	people, posts, err := s.st.MemoryHidden(ctx, s.st.Q(), userID)
	if err != nil {
		return gen.MemoryPreferences{}, err
	}
	ps, err := s.st.People(ctx, s.st.Q(), people)
	if err != nil {
		return gen.MemoryPreferences{}, err
	}
	out := gen.MemoryPreferences{HiddenPeople: make([]gen.PersonRef, 0, len(people)), HiddenMemories: posts}
	if out.HiddenMemories == nil {
		out.HiddenMemories = []uuid.UUID{}
	}
	for _, id := range people {
		p, ok := ps[id]
		if !ok {
			continue
		}
		out.HiddenPeople = append(out.HiddenPeople, s.personRef(id, p.DisplayName, p.AvatarKey, p.DeletedAt != nil))
	}
	return out, nil
}

func (s *Server) UpdateMemoryPreferences(ctx context.Context, req gen.UpdateMemoryPreferencesRequestObject) (gen.UpdateMemoryPreferencesResponseObject, error) {
	u := me(ctx)
	b := req.Body
	if b.HiddenPeople != nil && len(*b.HiddenPeople) > maxHiddenPeople {
		return nil, problem.Validation("hidden_people", "too_many", "you can leave out at most 200 people")
	}
	if b.HiddenMemories != nil && len(*b.HiddenMemories) > maxHiddenMemories {
		return nil, problem.Validation("hidden_memories", "too_many", "you can hide at most 5000 memories")
	}
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		if b.HiddenPeople != nil {
			if err := tx.SetMemoryHiddenPeople(ctx, u.ID, *b.HiddenPeople); err != nil {
				return err
			}
		}
		if b.HiddenMemories != nil {
			return tx.SetMemoryHiddenPosts(ctx, u.ID, *b.HiddenMemories)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	out, err := s.memoryPreferences(ctx, u.ID)
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

// ---------------------------------------------------------------- where the filter applies

// filterMemoryPosts drops On this day posts the user left out (by hidden people, or hidden memories).
func (s *Server) filterMemoryPosts(ctx context.Context, userID uuid.UUID, posts []store.MemoryPost) []store.MemoryPost {
	f, err := s.memoryFilterFor(ctx, s.st.Q(), userID)
	if err != nil {
		s.log.Warn("memories filter", "err", err)
		return posts
	}
	if f.empty() || len(posts) == 0 {
		return posts
	}
	ids := make([]uuid.UUID, len(posts))
	for i, p := range posts {
		ids[i] = p.PostID
	}
	posters, err := s.st.PostPosters(ctx, ids)
	if err != nil {
		s.log.Warn("memories posters", "err", err)
		return posts
	}
	out := posts[:0:0]
	for _, p := range posts {
		var poster *uuid.UUID
		if id, ok := posters[p.PostID]; ok {
			poster = &id
		}
		if !f.Hides(poster, p.PostID) {
			out = append(out, p)
		}
	}
	return out
}

// filterWeeklySummary leaves the user's hidden people out of the weekly summary's reactions and comments, and
// hidden memories out of its top item.
func (s *Server) filterWeeklySummary(ctx context.Context, userID uuid.UUID, from, to time.Time, sum *gen.WeeklySummary) error {
	f, err := s.memoryFilterFor(ctx, s.st.Q(), userID)
	if err != nil || f.empty() {
		return err
	}
	people := make([]uuid.UUID, 0, len(f.HiddenPeople))
	for id := range f.HiddenPeople {
		people = append(people, id)
	}
	posts := make([]uuid.UUID, 0, len(f.HiddenPosts))
	for id := range f.HiddenPosts {
		posts = append(posts, id)
	}
	if sum.ReactionCount, sum.CommentCount, err = s.st.PostReactionsAndCommentsExcept(ctx, userID, from, to, people); err != nil {
		return err
	}
	sum.TopItem = nil
	id, err := s.st.TopItemExcept(ctx, userID, from, to, people, posts)
	if err != nil || id == nil {
		return err
	}
	rows, err := s.st.ItemRowsByIDs(ctx, s.st.Q(), []uuid.UUID{*id})
	if err != nil {
		return err
	}
	if r, ok := rows[*id]; ok {
		it := s.itemJSON(&r)
		sum.TopItem = &it
	}
	return nil
}

// recapForViewer renders a family's monthly recap for one person: when its most-loved post is one they left out,
// the next most-loved post they may see takes its place (or none).
func (s *Server) recapForViewer(ctx context.Context, viewerID uuid.UUID, rc *gen.MonthlyRecap) {
	if rc == nil || rc.MostLoved == nil {
		return
	}
	f, err := s.memoryFilterFor(ctx, s.st.Q(), viewerID)
	if err != nil || f.empty() {
		return
	}
	posters, err := s.st.PostPosters(ctx, []uuid.UUID{rc.MostLoved.PostId})
	if err != nil {
		return
	}
	poster := func(id uuid.UUID) *uuid.UUID {
		if p, ok := posters[id]; ok {
			return &p
		}
		return nil
	}
	if !f.Hides(poster(rc.MostLoved.PostId), rc.MostLoved.PostId) {
		return
	}
	rc.MostLoved = nil
	month, err := time.Parse("2006-01", rc.Month)
	if err != nil {
		return
	}
	loc := userZone(nil)
	if fe, err := s.st.GetFeedEngagement(ctx, s.st.Q(), rc.FeedId); err == nil {
		loc = feedZone(fe)
	}
	from := time.Date(month.Year(), month.Month(), 1, 0, 0, 0, 0, loc)
	to := time.Date(month.Year(), month.Month()+1, 1, 0, 0, 0, 0, loc)
	cands, err := s.st.MostLovedPosts(ctx, rc.FeedId, from, to, recapCandidates)
	if err != nil {
		return
	}
	for _, c := range cands {
		if f.Hides(c.PosterID, c.PostID) {
			continue
		}
		rows, err := s.st.ItemRowsByIDs(ctx, s.st.Q(), []uuid.UUID{c.ItemID})
		if err != nil {
			return
		}
		r, ok := rows[c.ItemID]
		if !ok {
			continue
		}
		rc.MostLoved = &struct {
			Item          gen.Item `json:"item"`
			PostId        gen.Id   `json:"post_id"`
			ReactionCount *int     `json:"reaction_count,omitempty"`
		}{Item: s.itemJSON(&r), PostId: c.PostID, ReactionCount: ptr(c.Reactions)}
		return
	}
}

// recapJSONForViewer is recapForViewer for a stored recap (getFeedRecap).
func (s *Server) recapJSONForViewer(ctx context.Context, viewerID, feedID uuid.UUID, month time.Time, raw []byte) any {
	var rc gen.MonthlyRecap
	if json.Unmarshal(raw, &rc) != nil || rc.MostLoved == nil {
		return json.RawMessage(raw)
	}
	if rc.FeedId == uuid.Nil {
		rc.FeedId = feedID
	}
	if rc.Month == "" {
		rc.Month = month.Format("2006-01")
	}
	before := rc.MostLoved.PostId
	s.recapForViewer(ctx, viewerID, &rc)
	if rc.MostLoved != nil && rc.MostLoved.PostId == before {
		return json.RawMessage(raw)
	}
	// Keep fields this server version doesn't model: patch most_loved into the stored document.
	var doc map[string]json.RawMessage
	if json.Unmarshal(raw, &doc) != nil {
		return rc
	}
	if rc.MostLoved == nil {
		delete(doc, "most_loved")
	} else if b, err := json.Marshal(rc.MostLoved); err == nil {
		doc["most_loved"] = b
	}
	return doc
}
