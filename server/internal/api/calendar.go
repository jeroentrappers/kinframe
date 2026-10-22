package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	openapi_types "github.com/oapi-codegen/runtime/types"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/blob"
	"github.com/jeroentrappers/kinframe/server/internal/calendar"
	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// ---------------------------------------------------------------- helpers

func (s *Server) calendarEnabled() error {
	if s.calendar == nil {
		return problem.New(problem.FeatureDisabled, "calendars are turned off on this server")
	}
	return nil
}

// calendarManager checks the feature and the caller's change_settings permission.
func (s *Server) calendarManager(ctx context.Context, frameID uuid.UUID) (*store.Membership, error) {
	if err := s.calendarEnabled(); err != nil {
		return nil, err
	}
	m, err := s.membership(ctx, frameID)
	if err != nil {
		return nil, err
	}
	if err := requirePerm(m, m.Effective().ChangeSettings, "change_settings"); err != nil {
		return nil, err
	}
	return m, nil
}

func (s *Server) frameSource(ctx context.Context, frameID, id uuid.UUID) (*store.CalendarSource, error) {
	src, err := s.st.GetCalendarSource(ctx, id)
	if err != nil || src.FrameID != frameID {
		return nil, problem.New(problem.NotFound, "calendar not found")
	}
	return src, nil
}

func calendarSourceJSON(src *store.CalendarSource) map[string]any {
	created := src.CreatedAt.UTC()
	out := map[string]any{
		"id": src.ID, "kind": src.Kind, "name": src.Name, "color": src.Color, "enabled": src.Enabled,
		"url_hint": src.URLHint, "username": src.Username, "has_password": len(src.PasswordEnc) > 0,
		"status": src.Status, "last_error": src.LastError, "last_synced_at": src.LastSyncedAt,
		"last_attempt_at": src.LastAttemptAt, "next_sync_at": nil, "event_count": src.EventCount, "created_at": created,
	}
	if !src.Enabled {
		out["status"] = "disabled"
	}
	if src.Kind != "local" && src.Enabled {
		out["next_sync_at"] = src.NextSyncAt.UTC()
	}
	return out
}

func trimmed(p *string) string {
	if p == nil {
		return ""
	}
	return strings.TrimSpace(*p)
}

func tooLong(s string, n int) bool { return utf8.RuneCountInString(s) > n }

// ---------------------------------------------------------------- sources

func (s *Server) ListCalendars(ctx context.Context, req gen.ListCalendarsRequestObject) (gen.ListCalendarsResponseObject, error) {
	if _, err := s.calendarManager(ctx, req.FrameId); err != nil {
		return nil, err
	}
	srcs, err := s.st.ListCalendarSources(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	out := make([]map[string]any, len(srcs))
	for i := range srcs {
		out[i] = calendarSourceJSON(&srcs[i])
	}
	return ok(out), nil
}

type sourceInput struct {
	url, username, password, collection string
}

// connection validates a create/update body's connection fields.
func validateConnection(kind string, in sourceInput) (sourceInput, error) {
	u, err := calendar.NormalizeURL(in.url)
	if err != nil {
		return in, problem.Validation("url", "format", err.Error())
	}
	in.url = u
	if in.collection != "" {
		if kind != "caldav" {
			return in, problem.Validation("calendar_url", "kind", "only for CalDAV")
		}
		c, err := calendar.NormalizeURL(in.collection)
		if err != nil {
			return in, problem.Validation("calendar_url", "format", err.Error())
		}
		in.collection = c
	}
	return in, nil
}

func (s *Server) CreateCalendar(ctx context.Context, req gen.CreateCalendarRequestObject) (gen.CreateCalendarResponseObject, error) {
	if _, err := s.calendarManager(ctx, req.FrameId); err != nil {
		return nil, err
	}
	u := me(ctx)
	b := req.Body
	key := ""
	if req.Params.IdempotencyKey != nil {
		key = "calendar:" + req.FrameId.String() + ":" + *req.Params.IdempotencyKey
		var prev map[string]any
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, "calendar", key, &prev); err != nil {
			return nil, err
		} else if found {
			return created(prev), nil
		}
	}
	kind := string(b.Kind)
	if kind != "ics" && kind != "caldav" {
		return nil, problem.Validation("kind", "enum", "must be ics or caldav")
	}
	in, err := validateConnection(kind, sourceInput{url: b.Url, username: trimmed(b.Username), password: deref(b.Password), collection: trimmed(b.CalendarUrl)})
	if err != nil {
		return nil, err
	}
	if kind == "ics" && (in.username != "" || in.password != "") {
		// Allowed (some self-hosted feeds use basic auth), nothing to validate.
	}
	name := trimmed(b.Name)
	if tooLong(name, 60) {
		return nil, problem.Validation("name", "maxLength", "at most 60 characters")
	}
	if name == "" {
		name = calendar.DefaultName(in.url)
	}
	existing, err := s.st.ListCalendarSources(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	color := ""
	if b.Color != nil {
		color = string(*b.Color)
		if !calendar.ValidColor(color) {
			return nil, problem.Validation("color", "enum", "not a palette colour")
		}
	} else {
		var used []string
		for _, e := range existing {
			used = append(used, e.Color)
		}
		color = calendar.NextColor(used)
	}
	enabled := b.Enabled == nil || *b.Enabled
	status := "syncing"
	if !enabled {
		status = "disabled"
	}
	hint := calendar.RedactURL(in.url)
	src := store.CalendarSource{
		ID: store.NewID(), FrameID: req.FrameId, Kind: kind, Name: name, Color: color, Enabled: enabled,
		URLEnc: s.sealer.Seal(in.url), URLHint: &hint, PasswordEnc: s.sealer.Seal(in.password),
		CollectionURLEnc: s.sealer.Seal(in.collection), Status: status, CreatedBy: &u.ID,
	}
	if in.username != "" {
		src.Username = &in.username
	}
	row, err := s.st.CreateCalendarSource(ctx, src)
	if err != nil {
		return nil, err
	}
	out := calendarSourceJSON(row)
	if key != "" {
		_ = s.st.InTx(ctx, func(tx *store.Tx) error { return tx.SaveIdempotent(ctx, u.ID, "calendar", key, out) })
	}
	if enabled {
		s.calendar.KickSource(row.ID)
	}
	return created(out), nil
}

func (s *Server) TestCalendar(ctx context.Context, req gen.TestCalendarRequestObject) (gen.TestCalendarResponseObject, error) {
	if _, err := s.calendarManager(ctx, req.FrameId); err != nil {
		return nil, err
	}
	if allowed, retry := s.calendarTestUser.Allow(me(ctx).ID.String()); !allowed {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return nil, p
	}
	b := req.Body
	kind := string(b.Kind)
	in, err := validateConnection(kind, sourceInput{url: b.Url, username: trimmed(b.Username), password: deref(b.Password), collection: trimmed(b.CalendarUrl)})
	if err != nil {
		return nil, err
	}
	f, err := s.st.GetFrame(ctx, s.st.Q(), req.FrameId)
	if err != nil {
		return nil, notFound(err, "frame")
	}
	tctx, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()
	r := s.calendar.Test(tctx, kind, in.url, in.username, in.password, in.collection, calendar.Options(f.Settings).Loc)
	status := "ok"
	if !r.OK {
		status = "failed"
		if r.Auth {
			status = "auth_failed"
		}
	}
	out := map[string]any{"ok": r.OK, "status": status, "error": nil, "calendar_name": nil, "event_count": r.EventCount}
	if r.Error != "" {
		out["error"] = r.Error
	}
	if r.CalendarName != "" {
		out["calendar_name"] = r.CalendarName
	}
	cals := make([]map[string]any, 0, len(r.Calendars))
	for _, c := range r.Calendars {
		cals = append(cals, map[string]any{"url": c.URL, "name": c.Name, "color": c.Color})
	}
	if kind == "caldav" {
		out["calendars"] = cals
	}
	return ok(out), nil
}

func (s *Server) UpdateCalendar(ctx context.Context, req gen.UpdateCalendarRequestObject) (gen.UpdateCalendarResponseObject, error) {
	if _, err := s.calendarManager(ctx, req.FrameId); err != nil {
		return nil, err
	}
	src, err := s.frameSource(ctx, req.FrameId, req.CalendarId)
	if err != nil {
		return nil, err
	}
	b := req.Body
	wasEnabled := src.Enabled
	if b.Name != nil {
		name := strings.TrimSpace(*b.Name)
		if tooLong(name, 60) {
			return nil, problem.Validation("name", "maxLength", "at most 60 characters")
		}
		if name == "" && src.Kind != "local" {
			return nil, problem.Validation("name", "minLength", "a calendar needs a name")
		}
		src.Name = name
	}
	if b.Color != nil {
		if !calendar.ValidColor(string(*b.Color)) {
			return nil, problem.Validation("color", "enum", "not a palette colour")
		}
		src.Color = string(*b.Color)
	}
	if b.Enabled != nil {
		src.Enabled = *b.Enabled
	}
	connChanged := b.Url != nil || b.Username != nil || b.Password != nil || b.CalendarUrl != nil
	if connChanged {
		if src.Kind == "local" {
			return nil, problem.Validation("url", "kind", "the received-events calendar has no link")
		}
		cur, user, pass, coll, err := s.calendar.Credentials(src)
		if err != nil {
			// Unreadable (key changed): the client must send the URL again.
			if b.Url == nil {
				return nil, problem.Validation("url", "required", "enter the link again")
			}
			cur, user, pass, coll = "", deref(src.Username), "", ""
		}
		in := sourceInput{url: cur, username: user, password: pass, collection: coll}
		if b.Url != nil {
			in.url = *b.Url
			if b.CalendarUrl == nil {
				in.collection = ""
			}
		}
		if b.Username != nil {
			in.username = strings.TrimSpace(*b.Username)
		}
		if b.Password != nil {
			in.password = *b.Password
		}
		if b.CalendarUrl != nil {
			in.collection = strings.TrimSpace(*b.CalendarUrl)
		}
		if in, err = validateConnection(src.Kind, in); err != nil {
			return nil, err
		}
		hint := calendar.RedactURL(in.url)
		src.URLEnc, src.URLHint = s.sealer.Seal(in.url), &hint
		src.PasswordEnc, src.CollectionURLEnc = s.sealer.Seal(in.password), s.sealer.Seal(in.collection)
		src.Username = nil
		if in.username != "" {
			src.Username = &in.username
		}
	}
	row, err := s.st.UpdateCalendarSource(ctx, src, connChanged)
	if err != nil {
		return nil, err
	}
	if row.Kind != "local" && row.Enabled && (connChanged || !wasEnabled) {
		s.calendar.KickSource(row.ID)
	} else if err := s.calendar.Rebuild(ctx, req.FrameId); err != nil {
		return nil, err
	}
	return ok(calendarSourceJSON(row)), nil
}

func (s *Server) DeleteCalendar(ctx context.Context, req gen.DeleteCalendarRequestObject) (gen.DeleteCalendarResponseObject, error) {
	if _, err := s.calendarManager(ctx, req.FrameId); err != nil {
		return nil, err
	}
	src, err := s.frameSource(ctx, req.FrameId, req.CalendarId)
	if err != nil {
		return nil, err
	}
	if err := s.st.InTx(ctx, func(tx *store.Tx) error {
		if err := tx.DeleteCalendarSource(ctx, src); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, `DELETE FROM calendar_countdowns WHERE frame_id = $1 AND source_id = $2`, src.FrameID, src.ID)
		return err
	}); err != nil {
		return nil, err
	}
	if err := s.calendar.Rebuild(ctx, req.FrameId); err != nil {
		return nil, err
	}
	return noContent(), nil
}

func (s *Server) SyncCalendar(ctx context.Context, req gen.SyncCalendarRequestObject) (gen.SyncCalendarResponseObject, error) {
	if _, err := s.calendarManager(ctx, req.FrameId); err != nil {
		return nil, err
	}
	src, err := s.frameSource(ctx, req.FrameId, req.CalendarId)
	if err != nil {
		return nil, err
	}
	if src.Kind != "local" && src.Enabled {
		if err := s.st.RequestCalendarSync(ctx, req.FrameId, &src.ID); err != nil {
			return nil, err
		}
		src.Status = "syncing"
		s.calendar.KickSource(src.ID)
	}
	return accepted(calendarSourceJSON(src)), nil
}

// ---------------------------------------------------------------- merged view and countdowns

// renderCalendar returns the frame's current merged calendar (never nil).
func (s *Server) renderCalendar(ctx context.Context, f *store.Frame) (*calendar.Payload, error) {
	p, _, err := s.calendar.Render(ctx, f.ID, false)
	if err != nil {
		return nil, err
	}
	if p == nil {
		p, _ = calendar.Merge(nil, nil, calendar.Options(f.Settings), time.Now())
	}
	return p, nil
}

func (s *Server) GetFrameCalendar(ctx context.Context, req gen.GetFrameCalendarRequestObject) (gen.GetFrameCalendarResponseObject, error) {
	if _, err := s.calendarManager(ctx, req.FrameId); err != nil {
		return nil, err
	}
	f, err := s.st.GetFrame(ctx, s.st.Q(), req.FrameId)
	if err != nil {
		return nil, notFound(err, "frame")
	}
	p, err := s.renderCalendar(ctx, f)
	if err != nil {
		return nil, err
	}
	w, err := s.frameWeather(ctx, f)
	if err != nil {
		return nil, err
	}
	b, _ := json.Marshal(p)
	var m map[string]any
	_ = json.Unmarshal(b, &m)
	m["weather"] = w
	return ok(m), nil
}

// markCountdown marks an event of the frame's merged calendar as a countdown.
func (s *Server) markCountdown(ctx context.Context, f *store.Frame, eventID string) error {
	p, err := s.renderCalendar(ctx, f)
	if err != nil {
		return err
	}
	for _, c := range p.Countdowns {
		if c.EventID == eventID {
			return nil // already marked
		}
	}
	var found *calendar.Event
	for i := range p.Events {
		if p.Events[i].ID == eventID {
			found = &p.Events[i]
			break
		}
	}
	if found == nil {
		return problem.New(problem.NotFound, "event not found in the frame's calendar")
	}
	color := calendar.LocalColor
	for _, src := range p.Sources {
		if src.ID == found.SourceID {
			color = src.Color
		}
	}
	src := found.SourceID
	c := store.Countdown{EventID: found.ID, SourceID: &src, Title: found.Title, AllDay: found.AllDay, StartAt: found.StartAt, Color: color}
	if found.AllDay {
		d, _ := time.Parse("2006-01-02", found.StartDate)
		c.StartDate = &d
	}
	if err := s.st.PutCountdown(ctx, s.st.Q(), f.ID, c); err != nil {
		return err
	}
	return s.calendar.Rebuild(ctx, f.ID)
}

func (s *Server) clearCountdown(ctx context.Context, frameID uuid.UUID, eventID string) error {
	if err := s.st.DeleteCountdown(ctx, frameID, eventID); err != nil {
		return err
	}
	return s.calendar.Rebuild(ctx, frameID)
}

func (s *Server) SetCountdown(ctx context.Context, req gen.SetCountdownRequestObject) (gen.SetCountdownResponseObject, error) {
	if _, err := s.calendarManager(ctx, req.FrameId); err != nil {
		return nil, err
	}
	f, err := s.st.GetFrame(ctx, s.st.Q(), req.FrameId)
	if err != nil {
		return nil, notFound(err, "frame")
	}
	if err := s.markCountdown(ctx, f, req.EventId); err != nil {
		return nil, err
	}
	return noContent(), nil
}

func (s *Server) ClearCountdown(ctx context.Context, req gen.ClearCountdownRequestObject) (gen.ClearCountdownResponseObject, error) {
	if _, err := s.calendarManager(ctx, req.FrameId); err != nil {
		return nil, err
	}
	if err := s.clearCountdown(ctx, req.FrameId, req.EventId); err != nil {
		return nil, err
	}
	return noContent(), nil
}

// ---------------------------------------------------------------- sent events

func dateJSON(t *time.Time) *openapi_types.Date {
	if t == nil {
		return nil
	}
	return &openapi_types.Date{Time: *t}
}

func invitationJSON(inv *store.Invitation) map[string]any {
	var sender any
	if inv.SenderID != nil {
		sender = *inv.SenderID
	} else {
		sender = uuid.Nil
	}
	day := func(t *time.Time) any {
		if t == nil {
			return nil
		}
		return t.Format("2006-01-02")
	}
	utc := func(t *time.Time) any {
		if t == nil {
			return nil
		}
		return t.UTC()
	}
	return map[string]any{
		"id": inv.ID, "frame_id": inv.FrameID, "sender_id": sender, "sender_name": inv.SenderName, "status": inv.Status,
		"title": inv.Title, "all_day": inv.AllDay, "start_at": utc(inv.StartAt), "end_at": utc(inv.EndAt),
		"start_date": day(inv.StartDate), "end_date": day(inv.EndDate), "location": inv.Location, "notes": inv.Notes,
		"has_photo": inv.PhotoKey != nil, "photo_placement": inv.PhotoPlacement, "created_at": inv.CreatedAt.UTC(),
		"decided_at": utc(inv.DecidedAt),
	}
}

func (s *Server) ListEventInvitations(ctx context.Context, req gen.ListEventInvitationsRequestObject) (gen.ListEventInvitationsResponseObject, error) {
	if err := s.calendarEnabled(); err != nil {
		return nil, err
	}
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	var sender *uuid.UUID
	if !m.IsManager() {
		sender = &m.UserID
	}
	invs, err := s.st.ListInvitations(ctx, req.FrameId, sender)
	if err != nil {
		return nil, err
	}
	out := make([]map[string]any, len(invs))
	for i := range invs {
		out[i] = invitationJSON(&invs[i])
	}
	return ok(out), nil
}

func (s *Server) SendEventToFrame(ctx context.Context, req gen.SendEventToFrameRequestObject) (gen.SendEventToFrameResponseObject, error) {
	if err := s.calendarEnabled(); err != nil {
		return nil, err
	}
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	if err := requirePerm(m, m.Effective().Send, "send"); err != nil {
		return nil, err
	}
	u := me(ctx)
	key := ""
	if req.Params.IdempotencyKey != nil {
		key = "event:" + req.FrameId.String() + ":" + *req.Params.IdempotencyKey
		var prev map[string]any
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, "event", key, &prev); err != nil {
			return nil, err
		} else if found {
			return created(prev), nil
		}
	}
	b := req.Body
	inv := store.Invitation{ID: store.NewID(), FrameID: req.FrameId, SenderID: &u.ID, SenderName: u.DisplayName, PhotoPlacement: "top"}
	inv.Title = strings.Join(strings.Fields(b.Title), " ")
	if inv.Title == "" || tooLong(inv.Title, 200) {
		return nil, problem.Validation("title", "length", "a title of 1 to 200 characters is required")
	}
	inv.AllDay = b.AllDay != nil && *b.AllDay
	if inv.AllDay {
		if b.StartDate == nil {
			return nil, problem.Validation("start_date", "required", "all-day events need a start date")
		}
		sd := b.StartDate.Time
		ed := sd
		if b.EndDate != nil {
			ed = b.EndDate.Time
		}
		if ed.Before(sd) {
			return nil, problem.Validation("end_date", "range", "the end is before the start")
		}
		if ed.Sub(sd) > 366*24*time.Hour {
			return nil, problem.Validation("end_date", "range", "events can last at most a year")
		}
		inv.StartDate, inv.EndDate = &sd, &ed
	} else {
		if b.StartAt == nil || b.EndAt == nil {
			return nil, problem.Validation("start_at", "required", "timed events need a start and an end")
		}
		sa, ea := b.StartAt.UTC(), b.EndAt.UTC()
		if ea.Before(sa) {
			return nil, problem.Validation("end_at", "range", "the end is before the start")
		}
		if ea.Sub(sa) > 31*24*time.Hour {
			return nil, problem.Validation("end_at", "range", "timed events can last at most 31 days")
		}
		inv.StartAt, inv.EndAt = &sa, &ea
	}
	if l := trimmed(b.Location); l != "" {
		if tooLong(l, 200) {
			return nil, problem.Validation("location", "maxLength", "at most 200 characters")
		}
		inv.Location = &l
	}
	if n := strings.TrimSpace(deref(b.Notes)); n != "" {
		if tooLong(n, 2000) {
			return nil, problem.Validation("notes", "maxLength", "at most 2000 characters")
		}
		inv.Notes = &n
	}
	if b.PhotoPlacement != nil {
		inv.PhotoPlacement = string(*b.PhotoPlacement)
	}
	var uploadID string
	if b.PhotoUploadId != nil && *b.PhotoUploadId != "" {
		uploadID = *b.PhotoUploadId
		up, err := s.st.GetUpload(ctx, s.st.Q(), uploadID)
		if err != nil || up.UserID != u.ID || up.CompletedAt == nil || up.ConsumedAt != nil {
			return nil, problem.New(problem.UploadNotFound, "upload not found, incomplete or already used").WithField("photo_upload_id", "upload_not_found")
		}
		photoKey, sha, err := s.storeInvitationPhoto(ctx, inv.ID, req.FrameId, s.uploadPath(up.ID))
		if err != nil {
			return nil, err
		}
		inv.PhotoKey, inv.PhotoSHA256 = &photoKey, &sha
	}
	var row *store.Invitation
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if uploadID != "" {
			consumed, err := tx.ConsumeUpload(ctx, uploadID, u.ID)
			if err != nil {
				return err
			}
			if !consumed {
				return problem.New(problem.UploadNotFound, "upload already used").WithField("photo_upload_id", "upload_not_found")
			}
		}
		var err error
		if row, err = s.st.CreateInvitation(ctx, tx, inv); err != nil {
			return err
		}
		if key != "" {
			return tx.SaveIdempotent(ctx, u.ID, "event", key, invitationJSON(row))
		}
		return nil
	})
	if err != nil {
		if inv.PhotoKey != nil {
			s.deleteBlob(ctx, *inv.PhotoKey)
		}
		return nil, err
	}
	if uploadID != "" {
		s.RemoveUpload(s.uploadPath(uploadID))
	}
	out := invitationJSON(row)
	s.hub.SendToFrame(req.FrameId, map[string]any{"type": "event_invitation", "invitation": out})
	return created(out), nil
}

// storeInvitationPhoto turns an uploaded image into a WebP of at most 1280 px.
func (s *Server) storeInvitationPhoto(ctx context.Context, invID, frameID uuid.UUID, src string) (string, string, error) {
	if kind, _, err := media.Sniff(src); err != nil || kind != media.Photo {
		return "", "", problem.New(problem.UnsupportedMedia, "the photo must be an image").WithField("photo_upload_id", "unsupported_media")
	}
	tmp, err := s.tempDir()
	if err != nil {
		return "", "", err
	}
	defer os.RemoveAll(tmp)
	norm := filepath.Join(tmp, "norm.webp")
	if _, _, err := s.tools.PhotoMaster(ctx, src, norm); err != nil {
		return "", "", problem.New(problem.CorruptMedia, "the photo could not be read").WithField("photo_upload_id", "corrupt_media")
	}
	out := filepath.Join(tmp, "photo.webp")
	if err := s.tools.ResizeImage(ctx, norm, media.Size{W: 1280, H: 1280}, 82, out); err != nil {
		return "", "", problem.New(problem.CorruptMedia, "the photo could not be read").WithField("photo_upload_id", "corrupt_media")
	}
	sha, _, err := media.FileDigest(out)
	if err != nil {
		return "", "", err
	}
	key := fmt.Sprintf("invitations/%s/%s.webp", frameID, invID)
	if err := s.blob.PutFile(ctx, key, out, blob.PutOptions{ContentType: "image/webp"}); err != nil {
		return "", "", err
	}
	return key, sha, nil
}

func (s *Server) WithdrawEventInvitation(ctx context.Context, req gen.WithdrawEventInvitationRequestObject) (gen.WithdrawEventInvitationResponseObject, error) {
	if err := s.calendarEnabled(); err != nil {
		return nil, err
	}
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	inv, err := s.st.GetInvitation(ctx, req.InvitationId)
	if err != nil || inv.FrameID != req.FrameId || (!m.IsManager() && (inv.SenderID == nil || *inv.SenderID != m.UserID)) {
		return nil, problem.New(problem.NotFound, "event not found")
	}
	row, err := s.st.DecideInvitation(ctx, s.st.Q(), inv.ID, inv.FrameID, "withdrawn")
	if err != nil {
		return nil, err
	}
	if row == nil {
		return nil, problem.New(problem.AlreadyDecided, "the frame already answered")
	}
	s.hub.SendToFrame(req.FrameId, map[string]any{"type": "event_invitation", "invitation": invitationJSON(row)})
	return noContent(), nil
}

// getEventInvitationPhoto serves an invitation's photo to its sender or a manager.
func (s *Server) getEventInvitationPhoto(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	frameID, err1 := uuid.Parse(r.PathValue("frameId"))
	invID, err2 := uuid.Parse(r.PathValue("invitationId"))
	if err1 != nil || err2 != nil {
		problem.Write(w, problem.New(problem.NotFound, ""))
		return
	}
	m, err := s.membership(ctx, frameID)
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	inv, err := s.st.GetInvitation(ctx, invID)
	if err != nil || inv.FrameID != frameID || inv.PhotoKey == nil || (!m.IsManager() && (inv.SenderID == nil || *inv.SenderID != m.UserID)) {
		problem.Write(w, problem.New(problem.NotFound, "photo not found"))
		return
	}
	s.serveBlob(w, r, *inv.PhotoKey, "image/webp", `"`+deref(inv.PhotoSHA256)+`"`, "private, max-age=86400")
}

// ---------------------------------------------------------------- device side

func (s *Server) DeviceSyncCalendars(ctx context.Context, _ gen.DeviceSyncCalendarsRequestObject) (gen.DeviceSyncCalendarsResponseObject, error) {
	f, err := s.deviceFrame(ctx)
	if err != nil {
		return nil, err
	}
	if s.calendar != nil {
		if err := s.st.RequestCalendarSync(ctx, f.ID, nil); err != nil {
			return nil, err
		}
		s.calendar.KickFrame(f.ID)
	}
	return Response{Status: http.StatusAccepted}, nil
}

func (s *Server) DeviceSetCountdown(ctx context.Context, req gen.DeviceSetCountdownRequestObject) (gen.DeviceSetCountdownResponseObject, error) {
	f, err := s.deviceFrame(ctx)
	if err != nil {
		return nil, err
	}
	if err := s.calendarEnabled(); err != nil {
		return nil, err
	}
	if err := s.markCountdown(ctx, f, req.EventId); err != nil {
		return nil, err
	}
	return noContent(), nil
}

func (s *Server) DeviceClearCountdown(ctx context.Context, req gen.DeviceClearCountdownRequestObject) (gen.DeviceClearCountdownResponseObject, error) {
	f, err := s.deviceFrame(ctx)
	if err != nil {
		return nil, err
	}
	if err := s.calendarEnabled(); err != nil {
		return nil, err
	}
	if err := s.clearCountdown(ctx, f.ID, req.EventId); err != nil {
		return nil, err
	}
	return noContent(), nil
}

func (s *Server) DeviceListInvitations(ctx context.Context, _ gen.DeviceListInvitationsRequestObject) (gen.DeviceListInvitationsResponseObject, error) {
	f, err := s.deviceFrame(ctx)
	if err != nil {
		return nil, err
	}
	invs, err := s.st.PendingInvitations(ctx, f.ID)
	if err != nil {
		return nil, err
	}
	out := make([]map[string]any, len(invs))
	for i := range invs {
		out[i] = invitationJSON(&invs[i])
	}
	return ok(out), nil
}

func (s *Server) DeviceDecideInvitation(ctx context.Context, req gen.DeviceDecideInvitationRequestObject) (gen.DeviceDecideInvitationResponseObject, error) {
	f, err := s.deviceFrame(ctx)
	if err != nil {
		return nil, err
	}
	if err := s.calendarEnabled(); err != nil {
		return nil, err
	}
	status := "declined"
	if req.Body.Decision == gen.Accept {
		status = "accepted"
	}
	var row *store.Invitation
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		var err error
		if row, err = s.st.DecideInvitation(ctx, tx, req.InvitationId, f.ID, status); err != nil || row == nil {
			return err
		}
		if status == "accepted" {
			_, err = s.st.EnsureLocalCalendar(ctx, tx, f.ID, calendar.LocalColor)
		}
		return err
	})
	if err != nil {
		return nil, err
	}
	if row == nil {
		if inv, err := s.st.GetInvitation(ctx, req.InvitationId); err != nil || inv.FrameID != f.ID {
			return nil, problem.New(problem.NotFound, "invitation not found")
		}
		return nil, problem.New(problem.AlreadyDecided, "this invitation was already answered or withdrawn")
	}
	if status == "accepted" {
		if err := s.calendar.Rebuild(ctx, f.ID); err != nil {
			return nil, err
		}
	}
	if row.SenderID != nil {
		pushKey := "event_declined"
		if status == "accepted" {
			pushKey = "event_accepted"
		}
		s.notify.User(*row.SenderID, notify.Event{"type": "event_invitation.decided", "frame_id": f.ID, "invitation": invitationJSON(row)},
			&notify.Push{Key: pushKey, Args: map[string]string{"frame": f.Name, "title": row.Title},
				IDs: map[string]string{"frame_id": f.ID.String(), "invitation_id": row.ID.String()}})
	}
	return noContent(), nil
}

// deviceGetInvitationPhoto serves an invitation's photo to the frame it was sent to.
func (s *Server) deviceGetInvitationPhoto(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	f, err := s.deviceFrame(ctx)
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	invID, err := uuid.Parse(r.PathValue("invitationId"))
	if err != nil {
		problem.Write(w, problem.New(problem.NotFound, ""))
		return
	}
	key, sha, err := s.st.InvitationPhotoKey(ctx, invID, f.ID)
	if err != nil || key == nil {
		if err != nil && !errors.Is(err, store.ErrNotFound) {
			s.writeError(w, r, err)
			return
		}
		problem.Write(w, problem.New(problem.NotFound, "photo not found"))
		return
	}
	s.serveBlob(w, r, *key, "image/webp", `"`+deref(sha)+`"`, "private, max-age=31536000, immutable")
}

// frameCalendar renders a frame's `calendar` change payload (nil: no calendar).
func (s *Server) frameCalendar(ctx context.Context, f *store.Frame) (json.RawMessage, error) {
	if s.calendar == nil {
		return nil, nil
	}
	st, err := s.st.GetFrameCalendar(ctx, s.st.Q(), f.ID)
	if err != nil || st == nil || len(st.Payload) == 0 || string(st.Payload) == "null" {
		return nil, err
	}
	return st.Payload, nil
}

var _ = dateJSON

func deref(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}
