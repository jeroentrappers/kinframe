package api

// Blocks and reports (README §5.19 "Blocks and reports"). A block hides the blocked person's messages,
// comments and reactions from the blocker and stops their direct messages and group invitations; nobody is
// told. Reports in a family go to its owner and admins, others to the server's operators.

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// ---------------------------------------------------------------- blocks

func (s *Server) ListBlocks(ctx context.Context, _ gen.ListBlocksRequestObject) (gen.ListBlocksResponseObject, error) {
	rows, err := s.st.ListBlocks(ctx, me(ctx).ID)
	if err != nil {
		return nil, err
	}
	out := make([]gen.Block, len(rows))
	for i, r := range rows {
		out[i] = gen.Block{Person: s.personRef(r.UserID, r.DisplayName, r.AvatarKey, r.Deleted), CreatedAt: r.CreatedAt}
	}
	return ok(out), nil
}

func (s *Server) BlockUser(ctx context.Context, req gen.BlockUserRequestObject) (gen.BlockUserResponseObject, error) {
	u := me(ctx)
	if req.UserId == u.ID {
		return nil, problem.New(problem.ValidationFailed, "you can't block yourself")
	}
	if _, err := s.st.GetUser(ctx, s.st.Q(), req.UserId); err != nil {
		return nil, notFound(err, "member")
	}
	if err := s.st.Block(ctx, u.ID, req.UserId); err != nil {
		return nil, err
	}
	s.afterBlockChange(ctx, u.ID)
	return noContent(), nil
}

func (s *Server) UnblockUser(ctx context.Context, req gen.UnblockUserRequestObject) (gen.UnblockUserResponseObject, error) {
	u := me(ctx)
	if err := s.st.Unblock(ctx, u.ID, req.UserId); err != nil {
		return nil, err
	}
	s.afterBlockChange(ctx, u.ID)
	return noContent(), nil
}

// afterBlockChange: unread counts change with blocks.
func (s *Server) afterBlockChange(ctx context.Context, userID uuid.UUID) {
	s.notifyBadge(ctx, userID)
}

// ---------------------------------------------------------------- reports

// reportTarget is what a report is about, as the reporter may see it.
type reportTarget struct {
	author         *uuid.UUID
	postID         *uuid.UUID
	conversationID *uuid.UUID
	feedID         *uuid.UUID // the family that handles it; nil: the operator
	excerpt        string
}

func (s *Server) reportTargetOf(ctx context.Context, kind gen.ReportTargetKind, id uuid.UUID) (*reportTarget, error) {
	u := me(ctx)
	switch kind {
	case gen.ReportTargetKindMessage:
		var convID uuid.UUID
		if err := s.st.Q().QueryRow(ctx, `SELECT conversation_id FROM messages WHERE id = $1`, id).Scan(&convID); err != nil {
			if db.NotFound(err) {
				return nil, problem.New(problem.NotFound, "message not found")
			}
			return nil, err
		}
		v, err := s.st.ConversationOf(ctx, s.st.Q(), u.ID, convID)
		if err == store.ErrNotFound {
			return nil, problem.New(problem.NotFound, "message not found")
		}
		if err != nil {
			return nil, err
		}
		_, m, err := s.messageFor(ctx, v, id)
		if err != nil {
			return nil, err
		}
		if m.Kind != "person" || m.DeletedAt != nil {
			return nil, problem.New(problem.NotFound, "message not found")
		}
		t := &reportTarget{author: m.AuthorUserID, conversationID: &convID, excerpt: s.messageExcerpt(ctx, m)}
		if v.Kind == "family" || v.Kind == "circle" {
			t.feedID = v.FeedID
		}
		return t, nil
	case gen.ReportTargetKindComment:
		var postID uuid.UUID
		var author *uuid.UUID
		var body *string
		var enc []byte
		err := s.st.Q().QueryRow(ctx, `SELECT post_id, author_user_id, body, body_enc FROM comments
			WHERE id = $1 AND kind = 'person' AND deleted_at IS NULL AND removed_at IS NULL`, id).Scan(&postID, &author, &body, &enc)
		if db.NotFound(err) {
			return nil, problem.New(problem.NotFound, "")
		}
		if err != nil {
			return nil, err
		}
		a, err := s.chatPostAccess(ctx, u.ID, postID)
		if err != nil {
			return nil, err
		}
		if !a.Visible || (author != nil && s.blockedByMe(ctx, *author)) {
			return nil, problem.New(problem.NotFound, "")
		}
		t := &reportTarget{author: author, postID: &postID, excerpt: deref(s.openBody(body, enc))}
		if a.Kind == "feed" {
			t.feedID = a.FeedID
		}
		return t, nil
	default: // post
		a, err := s.chatPostAccess(ctx, u.ID, id)
		if err != nil {
			return nil, err
		}
		if !a.Visible {
			return nil, problem.New(problem.NotFound, "post not found")
		}
		poster := a.PosterID
		t := &reportTarget{author: &poster, postID: &id}
		if a.Kind == "feed" {
			t.feedID = a.FeedID
		}
		var n int
		var caption *string
		_ = s.st.Q().QueryRow(ctx, `SELECT count(*), (array_agg(i.caption ORDER BY x.pos) FILTER (WHERE i.caption IS NOT NULL))[1] FROM (
				SELECT pi.item_id, pi.position AS pos FROM feed_post_items pi WHERE pi.post_id = $1 AND pi.removed_at IS NULL
				UNION ALL SELECT si.item_id, si.position FROM frame_posts fp JOIN send_items si ON si.send_id = fp.send_id WHERE fp.id = $1
			) x JOIN items i ON i.id = x.item_id`, id).Scan(&n, &caption)
		t.excerpt = fmt.Sprintf("%d photos or videos", n)
		if caption != nil {
			t.excerpt += ": " + *caption
		}
		return t, nil
	}
}

func (s *Server) blockedByMe(ctx context.Context, other uuid.UUID) bool {
	b, err := s.st.HasBlocked(ctx, s.st.Q(), me(ctx).ID, other)
	return err == nil && b
}

// messageExcerpt is a message's text as reported, or a description of its media.
func (s *Server) messageExcerpt(ctx context.Context, m *store.Message) string {
	var parts []string
	if b := s.openBody(m.Body, m.BodyEnc); b != nil {
		parts = append(parts, *b)
	}
	var n int
	_ = s.st.Q().QueryRow(ctx, `SELECT count(*) FROM message_items WHERE message_id = $1`, m.ID).Scan(&n)
	if n > 0 {
		parts = append(parts, fmt.Sprintf("[%d photos or videos]", n))
	}
	if m.VoiceItemID != nil {
		parts = append(parts, "[voice message]")
	}
	if m.SharedPostID != nil {
		parts = append(parts, "[shared post "+m.SharedPostID.String()+"]")
	}
	return strings.Join(parts, " ")
}

func (s *Server) CreateReport(ctx context.Context, req gen.CreateReportRequestObject) (gen.CreateReportResponseObject, error) {
	u := me(ctx)
	b := req.Body
	if prev, err := s.st.OpenReportOf(ctx, s.st.Q(), u.ID, string(b.TargetKind), b.TargetId); err == nil {
		return created(s.reportJSON(prev, u.ID, false)), nil
	} else if err != store.ErrNotFound {
		return nil, err
	}
	t, err := s.reportTargetOf(ctx, b.TargetKind, b.TargetId)
	if err != nil {
		return nil, err
	}
	var note *string
	if b.Note != nil {
		if n := strings.TrimSpace(*b.Note); n != "" {
			note = &n
		}
	}
	ex := excerpt(t.excerpt, 2000)
	plain, enc := s.sealBody(&ex)
	r := store.Report{ID: store.NewID(), ReporterUserID: &u.ID, TargetKind: string(b.TargetKind), TargetID: b.TargetId,
		TargetUserID: t.author, PostID: t.postID, ConversationID: t.conversationID, FeedID: t.feedID, Reason: string(b.Reason),
		Note: note, Excerpt: plain, ExcerptEnc: enc}
	err = s.st.InTx(ctx, func(tx *store.Tx) error { return tx.CreateReport(ctx, r) })
	if db.IsUniqueViolation(err, "reports_one_open") {
		prev, err := s.st.OpenReportOf(ctx, s.st.Q(), u.ID, string(b.TargetKind), b.TargetId)
		if err != nil {
			return nil, err
		}
		return created(s.reportJSON(prev, u.ID, false)), nil
	}
	if err != nil {
		return nil, err
	}
	saved, err := s.st.GetReport(ctx, s.st.Q(), r.ID)
	if err != nil {
		return nil, err
	}
	s.publishReport(ctx, saved)
	return created(s.reportJSON(saved, u.ID, false)), nil
}

// reportHandlers: the feed's owner and admins, or the recent operators.
func (s *Server) reportHandlers(ctx context.Context, r *store.Report) ([]uuid.UUID, error) {
	if r.FeedID != nil {
		return s.st.FeedAdminIDs(ctx, s.st.Q(), *r.FeedID)
	}
	return s.st.RecentOperators(ctx)
}

// publishReport sends report.created (push report) to its handlers, never to the reported person.
func (s *Server) publishReport(ctx context.Context, r *store.Report) {
	ids, err := s.reportHandlers(ctx, r)
	if err != nil {
		s.log.Warn("report handlers", "err", err)
		return
	}
	pushIDs := map[string]string{"report_id": r.ID.String()}
	push := &notify.Push{Key: "report", Text: "chat_report_operator", IDs: pushIDs}
	if r.FeedID != nil {
		pushIDs["feed_id"] = r.FeedID.String()
		name := ""
		_ = s.st.Q().QueryRow(ctx, `SELECT name FROM feeds WHERE id = $1`, *r.FeedID).Scan(&name)
		push = &notify.Push{Key: "report", Text: "chat_report", Args: map[string]string{"feed": name}, IDs: pushIDs}
	}
	for _, id := range ids {
		if r.TargetUserID != nil && *r.TargetUserID == id {
			continue
		}
		s.notify.User(id, notifyEvent("report.created", map[string]any{"report": s.reportJSON(r, id, true)}), push)
	}
}

// reportJSON: handlers see everything except who reported; the reporter sees their own report without the
// excerpt, author and note.
func (s *Server) reportJSON(r *store.Report, viewer uuid.UUID, handler bool) gen.Report {
	out := gen.Report{Id: r.ID, TargetKind: gen.ReportTargetKind(r.TargetKind), TargetId: r.TargetID, PostId: r.PostID,
		ConversationId: r.ConversationID, FeedId: r.FeedID, Reason: gen.ReportReason(r.Reason), Status: gen.ReportStatus(r.Status),
		CreatedAt: r.CreatedAt, ResolvedAt: r.ResolvedAt, Mine: ptr(r.ReporterUserID != nil && *r.ReporterUserID == viewer)}
	if handler {
		out.Note = r.Note
		out.Excerpt = s.openBody(r.Excerpt, r.ExcerptEnc)
		if r.TargetUserID != nil {
			out.Author = ptr(s.personRef(*r.TargetUserID, deref(r.AuthorName), r.AuthorAvatarKey, r.AuthorDeleted))
		}
	}
	return out
}

func (s *Server) ListFeedReports(ctx context.Context, req gen.ListFeedReportsRequestObject) (gen.ListFeedReportsResponseObject, error) {
	v, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	if !v.IsAdmin() {
		return nil, problem.New(problem.Forbidden, "only the family's owner and admins handle these reports")
	}
	rows, err := s.st.ListFeedReports(ctx, req.FeedId)
	if err != nil {
		return nil, err
	}
	out := make([]gen.Report, len(rows))
	for i := range rows {
		out[i] = s.reportJSON(&rows[i], me(ctx).ID, true)
	}
	return ok(out), nil
}

func (s *Server) ListOperatorReports(ctx context.Context, req gen.ListOperatorReportsRequestObject) (gen.ListOperatorReportsResponseObject, error) {
	if !isOperator(ctx) {
		return nil, problem.New(problem.Forbidden, "operator access required")
	}
	s.noteOperator(ctx)
	status := "open"
	if req.Params.Status != nil {
		status = string(*req.Params.Status)
	}
	rows, err := s.st.ListOperatorReports(ctx, status)
	if err != nil {
		return nil, err
	}
	out := make([]gen.Report, len(rows))
	for i := range rows {
		out[i] = s.reportJSON(&rows[i], me(ctx).ID, true)
	}
	return ok(out), nil
}

func (s *Server) ResolveReport(ctx context.Context, req gen.ResolveReportRequestObject) (gen.ResolveReportResponseObject, error) {
	u := me(ctx)
	r, err := s.st.GetReport(ctx, s.st.Q(), req.ReportId)
	if err == store.ErrNotFound {
		return nil, problem.New(problem.NotFound, "report not found")
	}
	if err != nil {
		return nil, err
	}
	if r.FeedID != nil {
		v, err := s.st.GetFeedView(ctx, s.st.Q(), u.ID, *r.FeedID)
		if err != nil || !v.IsAdmin() {
			return nil, problem.New(problem.NotFound, "report not found")
		}
	} else if !isOperator(ctx) {
		return nil, problem.New(problem.NotFound, "report not found")
	}
	if r.Status != "open" {
		return nil, problem.New(problem.AlreadyDecided, "")
	}
	status := "dismissed"
	if req.Body.Action == gen.Remove {
		status = "removed"
	}
	var won bool
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		won, err = tx.ResolveReport(ctx, r.ID, status, u.ID)
		return err
	})
	if err != nil {
		return nil, err
	}
	if !won {
		return nil, problem.New(problem.AlreadyDecided, "")
	}
	if status == "removed" {
		if err := s.removeReported(ctx, r, u.ID); err != nil {
			return nil, err
		}
	}
	saved, err := s.st.GetReport(ctx, s.st.Q(), r.ID)
	if err != nil {
		return nil, err
	}
	return ok(s.reportJSON(saved, u.ID, true)), nil
}

// removeReported removes reported content like a moderator delete.
func (s *Server) removeReported(ctx context.Context, r *store.Report, by uuid.UUID) error {
	switch r.TargetKind {
	case "message":
		m, err := s.st.MessagesByID(ctx, s.st.Q(), []uuid.UUID{r.TargetID})
		if err != nil {
			return err
		}
		if msg, found := m[r.TargetID]; found {
			return s.removeMessage(ctx, &msg, &by)
		}
		return nil
	case "comment":
		// A moderator's removal hides the comment and its replies (README §5.18); the thread's count follows.
		return s.st.InTx(ctx, func(tx *store.Tx) error {
			var postID uuid.UUID
			err := tx.QueryRow(ctx, `UPDATE comments SET removed_at = now(), removed_by = $2 WHERE id = $1 AND removed_at IS NULL RETURNING post_id`,
				r.TargetID, by).Scan(&postID)
			if db.NotFound(err) {
				return nil
			}
			if err != nil {
				return err
			}
			if _, err := tx.Exec(ctx, `UPDATE comments SET removed_at = now(), removed_by = $2 WHERE parent_id = $1 AND removed_at IS NULL`, r.TargetID, by); err != nil {
				return err
			}
			if _, err := tx.Exec(ctx, `UPDATE threads t SET comment_count = (SELECT count(*) FROM comments c WHERE c.post_id = t.post_id
				AND c.kind = 'person' AND c.deleted_at IS NULL AND c.removed_at IS NULL) WHERE t.post_id = $1`, postID); err != nil {
				return err
			}
			return tx.ResolveOpenReportsOn(ctx, "comment", r.TargetID, by)
		})
	default: // post
		var feedPost bool
		var sendID, frameID *uuid.UUID
		var poster *uuid.UUID
		err := s.st.Q().QueryRow(ctx, `SELECT true, send_id, NULL::uuid, poster_user_id FROM feed_posts WHERE id = $1
			UNION ALL SELECT false, send_id, frame_id, sender_user_id FROM frame_posts WHERE id = $1`, r.TargetID).Scan(&feedPost, &sendID, &frameID, &poster)
		if db.NotFound(err) {
			return nil
		}
		if err != nil {
			return err
		}
		err = s.st.InTx(ctx, func(tx *store.Tx) error {
			if feedPost {
				_, subs, err := tx.RemovePostItems(ctx, r.TargetID, nil, &by)
				if err != nil {
					return err
				}
				if err := enqueueSweeps(ctx, tx, subs); err != nil {
					return err
				}
			} else if sendID != nil && frameID != nil {
				if _, err := tx.RemovePlacements(ctx, `p.send_id = $1 AND p.frame_id = $2`, *sendID, *frameID); err != nil {
					return err
				}
			}
			return tx.ResolveOpenReportsOn(ctx, "post", r.TargetID, by)
		})
		if err != nil {
			return err
		}
		if sendID != nil && poster != nil {
			s.publishSendUpdates(ctx, map[uuid.UUID]uuid.UUID{*sendID: *poster}, "")
		}
		return nil
	}
}

// pruneOperators forgets operators not seen for a year.
func (s *Server) pruneOperators(ctx context.Context) {
	if _, err := s.st.Pool.Exec(ctx, `DELETE FROM chat_operators WHERE seen_at < $1`, time.Now().AddDate(-1, 0, 0)); err != nil {
		s.log.Warn("prune chat operators", "err", err)
	}
}
