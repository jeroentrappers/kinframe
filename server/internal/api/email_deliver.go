package api

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/blob"
	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

type configLimits = config.Limits

// ---------------------------------------------------------------- delivery (§8.6)

// deliverGroup makes the sends of one group of addresses (same account and
// credit): items in mail order, placements on frame targets, posts on feed
// targets, split at max_items_per_send. Files already on a target are skipped
// there (duplicate). Fills each decision's items, skipped and send ids.
func (s *Server) deliverGroup(ctx context.Context, g []*emailDecision, files []*mailFile, caption *string, hint *time.Time, limits configLimits) error {
	if len(g) == 0 || len(files) == 0 {
		return nil
	}
	first := g[0]
	// Which file goes where.
	use := make([]map[uuid.UUID]bool, len(files)) // file -> address ids it goes to
	var used []int
	for i, f := range files {
		use[i] = map[uuid.UUID]bool{}
		for _, d := range g {
			dup, err := s.onTarget(ctx, d.a, f.sha)
			if err != nil {
				return err
			}
			if dup {
				d.skipped["duplicate"]++
				continue
			}
			use[i][d.a.ID] = true
		}
		if len(use[i]) > 0 {
			used = append(used, i)
		}
	}
	if len(used) == 0 {
		return nil
	}
	var sendIDs []uuid.UUID
	var fresh []string // scratch copies to remove if the transaction fails
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		sendIDs, fresh = nil, nil
		items := make([]uuid.UUID, len(used))
		reused := map[uuid.UUID]bool{}
		for k, i := range used {
			f := files[i]
			mediaID, isNew, path, err := s.emailMedia(ctx, tx, first.by, f)
			if err != nil {
				return err
			}
			if path != "" {
				fresh = append(fresh, path)
			}
			it := store.Item{ID: store.NewID(), MediaID: mediaID, SenderUserID: &first.by, Caption: caption, CapturedHint: hint,
				Via: ptr("email"), ViaLabel: &first.label}
			if first.onBehalf {
				it.ViaEmailAddressID = &first.a.ID
			}
			if err := tx.CreateItem(ctx, it); err != nil {
				return err
			}
			items[k] = it.ID
			if !isNew {
				reused[it.ID] = true
				if err := tx.Enqueue(ctx, store.JobThumbs, map[string]any{"item_id": it.ID}); err != nil {
					return err
				}
				if err := tx.Enqueue(ctx, store.JobEnsureRenditions, map[string]any{"media_id": mediaID}); err != nil {
					return err
				}
			}
		}
		per := max(1, limits.MaxItemsPerSend)
		for start := 0; start < len(items); start += per {
			end := min(start+per, len(items))
			sendID := store.NewID()
			if err := tx.CreateEmailSend(ctx, sendID, first.by, first.a.ID, first.label, items[start:end]); err != nil {
				return err
			}
			sendIDs = append(sendIDs, sendID)
			for _, d := range g {
				var mine []uuid.UUID
				var mineMedia []mailFile
				for k := start; k < end; k++ {
					if use[used[k]][d.a.ID] {
						mine = append(mine, items[k])
						mineMedia = append(mineMedia, *files[used[k]])
					}
				}
				if len(mine) == 0 {
					continue
				}
				switch {
				case d.a.FrameID != nil:
					if err := s.placeEmailItems(ctx, tx, *d.a.FrameID, first, sendID, mine); err != nil {
						return err
					}
				case d.a.FeedID != nil:
					postID := store.NewID()
					if err := tx.CreateFeedPost(ctx, postID, *d.a.FeedID, &sendID, first.by, mine); err != nil {
						return err
					}
					if err := tx.Enqueue(ctx, store.JobFeedFanout, store.PostJob{PostID: postID}); err != nil {
						return err
					}
				}
				for _, mf := range mineMedia {
					d.items++
					if mf.kind == media.Video {
						d.videos++
					} else {
						d.photos++
					}
				}
			}
		}
		if first.account != nil {
			var frames []uuid.UUID
			for _, d := range g {
				if d.a.FrameID != nil {
					frames = append(frames, *d.a.FrameID)
				}
			}
			if err := tx.TouchLastSent(ctx, frames, first.by); err != nil {
				return err
			}
		}
		// Held media now belong to items.
		for _, i := range used {
			if files[i].mediaID != nil {
				if _, err := tx.Exec(ctx, `UPDATE media SET held_message_id = NULL WHERE id = $1`, *files[i].mediaID); err != nil {
					return err
				}
			}
		}
		return nil
	})
	if err != nil {
		for _, p := range fresh {
			_ = os.Remove(p)
		}
		return err
	}
	for _, d := range g {
		if d.msg == nil {
			d.msg = &store.EmailMessage{}
		}
		d.msg.SendIDs = sendIDs
	}
	return nil
}

// onTarget: the same file is live on the frame / in the feed (§8.4 step 5).
func (s *Server) onTarget(ctx context.Context, a *store.EmailAddress, sha string) (bool, error) {
	if a.FrameID != nil {
		_, _, found, err := s.st.DuplicateOnFrame(ctx, s.st.Q(), *a.FrameID, sha, "")
		return found, err
	}
	_, found, err := s.st.DuplicateInFeed(ctx, s.st.Q(), *a.FeedID, sha, "")
	return found, err
}

// emailMedia returns the media for a file: held media as they are, the
// sender's earlier media from the same file, or new media from a copy of the
// scratch file (returned so a failed transaction can remove it).
func (s *Server) emailMedia(ctx context.Context, tx *store.Tx, owner uuid.UUID, f *mailFile) (uuid.UUID, bool, string, error) {
	if f.mediaID != nil {
		return *f.mediaID, false, "", nil
	}
	if dup, err := s.st.FindDuplicateItem(ctx, owner, f.sha); err != nil {
		return uuid.Nil, false, "", err
	} else if dup != nil {
		if row, err := s.st.GetItemRow(ctx, tx, *dup); err == nil {
			return row.MediaID, false, "", nil
		}
	}
	path, err := s.copyScratch(f.path)
	if err != nil {
		return uuid.Nil, false, "", err
	}
	id, err := s.newEmailMedia(ctx, tx, owner, f, path)
	return id, true, path, err
}

func (s *Server) newEmailMedia(ctx context.Context, tx *store.Tx, owner uuid.UUID, f *mailFile, path string) (uuid.UUID, error) {
	id := store.NewID()
	mime := f.mime
	md := store.Media{ID: id, OwnerUserID: &owner, Kind: string(f.kind), SourcePath: &path, SourceMime: &mime, IncludeAudio: true, SourceSHA256: &f.sha}
	if f.kind == media.Video {
		md.DurationMS = &f.durMS
	}
	if err := tx.CreateMedia(ctx, md); err != nil {
		return uuid.Nil, err
	}
	return id, tx.Enqueue(ctx, store.JobProcessMedia, map[string]any{"media_id": id})
}

// placeEmailItems places items on a frame. Moderation applies as for any send,
// except that mail the owner sends for a non-account sender is always
// moderated on a moderated frame (§9.3).
func (s *Server) placeEmailItems(ctx context.Context, tx *store.Tx, frameID uuid.UUID, d *emailDecision, sendID uuid.UUID, items []uuid.UUID) error {
	f, err := tx.LockFrame(ctx, frameID)
	if err != nil {
		return err
	}
	moderated := false
	if f.Moderation {
		if d.onBehalf {
			moderated = true
		} else {
			m, err := s.st.GetMembership(ctx, tx, frameID, d.by)
			moderated = err != nil || !m.IsManager()
		}
	}
	var created []placed
	for _, itemID := range items {
		var existing uuid.UUID
		if err := tx.QueryRow(ctx, `SELECT id FROM placements WHERE frame_id = $1 AND item_id = $2 AND removed_at IS NULL`, frameID, itemID).Scan(&existing); err == nil {
			continue
		}
		row, err := s.st.GetItemRow(ctx, tx, itemID)
		if err != nil {
			return err
		}
		p := store.Placement{ID: store.NewID(), FrameID: frameID, ItemID: itemID, SendID: &sendID, Status: "pending", PendingModeration: moderated}
		if err := tx.CreatePlacement(ctx, p); err != nil {
			return err
		}
		created = append(created, placed{PlacementID: p.ID, FrameID: frameID, ItemID: itemID, MediaID: row.MediaID, MotionID: row.MotionMediaID, SendID: &sendID})
	}
	return ensureRenditionsFor(ctx, tx, created)
}

// ---------------------------------------------------------------- holding (§5.3)

// holdMail keeps a mail for a manager's decision: its files processed into
// media without items, the sender pending (unless they are listed already).
func (s *Server) holdMail(ctx context.Context, j *mailJob, d *emailDecision, limits configLimits) error {
	a := d.a
	from := j.auth.From
	if from == "" {
		from = "unknown@invalid"
	}
	var files []*mailFile
	for _, f := range j.files {
		dup, err := s.onTarget(ctx, a, f.sha)
		if err != nil {
			return err
		}
		if dup {
			d.skipped["duplicate"]++
			continue
		}
		files = append(files, f)
	}
	d.skipped.add(j.skipped)
	if len(files) == 0 {
		s.logMessage(ctx, j, a, d, "duplicate")
		return nil
	}
	authJSON, err := json.Marshal(j.auth)
	if err != nil {
		return err
	}
	newPending := false
	var fresh []string
	h := &store.HeldMessage{ID: store.NewID(), AddressID: a.ID, Reason: d.reason, MessageKey: j.key, SenderEmail: from,
		Subject: strOrNil(j.parsed.Subject), Caption: j.caption, Auth: authJSON, Skipped: map[string]int(d.skipped),
		ReceivedAt: j.now, ExpiresAt: j.now.Add(time.Duration(s.em.cfg.HeldDays) * 24 * time.Hour)}
	if n := heldLabel(j); n != "" {
		h.SenderName = &n
	}
	var blobs store.MediaBlobs
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		newPending, fresh, blobs.Keys = false, nil, nil
		row, err := s.st.EmailSenderByEmail(ctx, tx, a.ID, from)
		if err == store.ErrNotFound {
			row = &store.EmailSender{ID: store.NewID(), AddressID: a.ID, Email: from, DisplayName: h.SenderName, State: "pending", Origin: "mail",
				LastMessageAt: &j.now}
			if err := tx.CreateEmailSender(ctx, row); err != nil {
				return err
			}
			newPending = true
		} else if err != nil {
			return err
		} else {
			row.LastMessageAt = &j.now
			if row.DisplayName == nil && row.State == "pending" {
				row.DisplayName = h.SenderName
			}
			if err := tx.SaveEmailSender(ctx, row); err != nil {
				return err
			}
		}
		d.row = row
		h.SenderID = row.ID
		h.Media = nil
		for _, f := range files {
			path, err := s.copyScratch(f.path)
			if err != nil {
				return err
			}
			fresh = append(fresh, path)
			id, err := s.newEmailMedia(ctx, tx, *a.OwnerUserID, f, path)
			if err != nil {
				return err
			}
			hm := store.HeldMedia{MediaID: id, Kind: string(f.kind), Bytes: f.size, SHA256: f.sha, CapturedHint: j.hint}
			if f.width > 0 {
				hm.Width, hm.Height = ptr(f.width), ptr(f.height)
			}
			if f.durMS > 0 {
				hm.DurationMS = ptr(f.durMS)
			}
			if f.filename != "" {
				hm.Filename = ptr(f.filename)
			}
			h.Media = append(h.Media, hm)
		}
		if err := tx.CreateHeldMessage(ctx, h); err != nil {
			return err
		}
		// Caps: 10 per pending sender, max_email_held_messages per address; the oldest go first.
		for _, c := range []struct {
			sender *uuid.UUID
			max    int
		}{{&row.ID, 10}, {nil, limits.MaxEmailHeldMessages}} {
			for {
				n, err := s.st.CountHeldMessages(ctx, tx, a.ID, c.sender)
				if err != nil {
					return err
				}
				if n <= c.max {
					break
				}
				old, err := s.st.OldestHeldMessage(ctx, tx, a.ID, c.sender)
				if err != nil {
					return err
				}
				b, err := tx.DeleteHeldMessage(ctx, old, false)
				if err != nil {
					return err
				}
				blobs.Keys = append(blobs.Keys, b.Keys...)
			}
		}
		return nil
	})
	if err != nil {
		for _, p := range fresh {
			_ = os.Remove(p)
		}
		return err
	}
	s.deleteBlobs(ctx, blobs.Keys)
	d.items = len(files)
	s.logMessage(ctx, j, a, d, "held")
	_ = s.st.TouchEmailAddress(ctx, a.ID, j.now)
	s.announcePending(ctx, a, d.row, len(files))
	s.publishEmailAddress(ctx, a.ID)
	if newPending && j.auth.Authenticated {
		s.replyHeld(ctx, a, d, from, j.parsed.Subject, j.parsed.MessageID)
	}
	return nil
}

// heldLabel is the credited name a held mail shows (the claimed display name).
func heldLabel(j *mailJob) string {
	if j.auth.FromName == "" {
		return ""
	}
	return labelOf(j.auth.FromName, "", j.auth.From)
}

// heldFiles turns held media into deliverable files (processed media only;
// failed ones are skipped).
func (s *Server) heldFiles(ctx context.Context, h *store.HeldMessage) []*mailFile {
	var out []*mailFile
	for _, m := range h.Media {
		row, err := s.st.MediaRow(ctx, s.st.Q(), m.MediaID)
		if err != nil || row.Status == "failed" {
			continue
		}
		id := m.MediaID
		f := &mailFile{mediaID: &id, kind: media.Kind(m.Kind), sha: m.SHA256, size: m.Bytes}
		if m.DurationMS != nil {
			f.durMS = *m.DurationMS
		}
		out = append(out, f)
	}
	return out
}

// deliverHeld delivers one held mail (approval or "deliver this one"). The
// credit is the claimed name; matched accounts send as themselves when the
// mail was authenticated.
func (s *Server) deliverHeld(ctx context.Context, a *store.EmailAddress, h *store.HeldMessage, row *store.EmailSender) (*emailDecision, error) {
	if err := s.ensureEmailOwner(ctx, a); err != nil {
		return nil, err
	}
	var auth struct {
		Authenticated bool `json:"authenticated"`
	}
	_ = json.Unmarshal(h.Auth, &auth)
	d := &emailDecision{a: a, action: "deliver", by: *a.OwnerUserID, onBehalf: true, row: row, skipped: skipCounts{}, noReply: !auth.Authenticated}
	d.label = labelOf(deref(h.SenderName), deref(rowName(row)), h.SenderEmail)
	if auth.Authenticated {
		if u := s.matchedAccount(ctx, h.SenderEmail, a); u != nil {
			d.by, d.account, d.onBehalf = u.ID, u, false
			d.label = labelOf(u.DisplayName, "", h.SenderEmail)
		}
	}
	files := s.heldFiles(ctx, h)
	if err := s.deliverGroup(ctx, []*emailDecision{d}, files, h.Caption, firstHint(h), s.Limits(ctx)); err != nil {
		return nil, err
	}
	var blobs *store.MediaBlobs
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		var err error
		blobs, err = tx.DeleteHeldMessage(ctx, h, true)
		if err != nil {
			return err
		}
		// Media no item uses (failed, or duplicates on the target) go with it.
		var unused []store.HeldMedia
		for _, m := range h.Media {
			var usedBy bool
			if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM items WHERE media_id = $1)`, m.MediaID).Scan(&usedBy); err != nil {
				return err
			}
			if !usedBy {
				m.ThumbKey = nil
				unused = append(unused, m)
			}
		}
		if len(unused) > 0 {
			hh := &store.HeldMessage{ID: uuid.Nil, Media: unused}
			b, err := tx.DeleteHeldMessage(ctx, hh, false)
			if err != nil {
				return err
			}
			blobs.Keys = append(blobs.Keys, b.Keys...)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	s.deleteBlobs(ctx, blobs.Keys)
	m := &store.EmailMessage{ID: store.NewID(), AddressID: a.ID, MessageKey: h.MessageKey, Authenticated: auth.Authenticated,
		Outcome: "delivered", Items: d.items, Skipped: map[string]int(d.skipped), ReceivedAt: s.now()}
	if d.items == 0 {
		m.Outcome = "duplicate"
	}
	m.SenderEmail, m.SenderName = &h.SenderEmail, h.SenderName
	if d.msg != nil {
		m.SendIDs = d.msg.SendIDs
	}
	if err := s.st.InsertEmailMessage(ctx, s.st.Q(), m); err != nil {
		return nil, err
	}
	d.msg = m
	return d, nil
}

func firstHint(h *store.HeldMessage) *time.Time {
	for _, m := range h.Media {
		if m.CapturedHint != nil {
			return m.CapturedHint
		}
	}
	return &h.ReceivedAt
}

// ---------------------------------------------------------------- held previews

// fillHeldThumbs makes 256 px thumbnails of processed held media (managers'
// previews); until then the previews use the master or poster.
func (s *Server) fillHeldThumbs(ctx context.Context) {
	rows, err := s.st.Pool.Query(ctx, `SELECT id, address_id FROM email_held_messages h
		WHERE EXISTS (SELECT 1 FROM jsonb_array_elements(h.media) e WHERE e->>'thumb_key' IS NULL) LIMIT 50`)
	if err != nil {
		return
	}
	type ref struct{ id, addr uuid.UUID }
	var refs []ref
	for rows.Next() {
		var r ref
		if rows.Scan(&r.id, &r.addr) == nil {
			refs = append(refs, r)
		}
	}
	rows.Close()
	for _, r := range refs {
		h, err := s.st.GetHeldMessage(ctx, s.st.Q(), r.addr, r.id)
		if err != nil {
			continue
		}
		changed := false
		for i := range h.Media {
			m := &h.Media[i]
			if m.ThumbKey != nil {
				continue
			}
			if key := s.heldThumb(ctx, h.ID, m); key != "" {
				m.ThumbKey = &key
				changed = true
			}
		}
		if changed {
			b, _ := json.Marshal(h.Media)
			if _, err := s.st.Pool.Exec(ctx, `UPDATE email_held_messages SET media = $2 WHERE id = $1`, h.ID, b); err != nil {
				s.log.Warn("email: held thumbnails", "err", err)
			}
		}
	}
}

func (s *Server) heldThumb(ctx context.Context, heldID uuid.UUID, m *store.HeldMedia) string {
	row, err := s.st.MediaRow(ctx, s.st.Q(), m.MediaID)
	if err != nil || row.Status != "ready" || row.Width == nil || row.Height == nil {
		return ""
	}
	src := row.MasterKey
	if row.Kind == string(media.Video) {
		src = row.PosterKey
	}
	if src == nil {
		return ""
	}
	tmp, err := os.MkdirTemp(s.scratchDir(), "email-thumb-")
	if err != nil {
		return ""
	}
	defer os.RemoveAll(tmp)
	local, err := blob.LocalFile(ctx, s.blob, *src, tmp)
	if err != nil {
		return ""
	}
	out := filepath.Join(tmp, "t.webp")
	if err := s.tools.Thumb(ctx, local, media.Size{W: *row.Width, H: *row.Height}, 256, 0.5, 0.5, out); err != nil {
		return ""
	}
	key := fmt.Sprintf("email-held/%s/%s-%s.webp", heldID, m.MediaID, store.NewID().String()[:8])
	if err := s.blob.PutFile(ctx, key, out, blob.PutOptions{ContentType: "image/webp"}); err != nil {
		return ""
	}
	return key
}

// heldPreviewURL is a held file's preview: its thumbnail, else the processed
// master (photo) or poster (video), else nothing yet.
func (s *Server) heldPreviewURL(ctx context.Context, m store.HeldMedia) *string {
	if m.ThumbKey != nil {
		return ptr(s.signedURL(*m.ThumbKey, thumbURLTTL))
	}
	row, err := s.st.MediaRow(ctx, s.st.Q(), m.MediaID)
	if err != nil || row.Status != "ready" {
		return nil
	}
	key := row.MasterKey
	if row.Kind == string(media.Video) {
		key = row.PosterKey
	}
	if key == nil {
		return nil
	}
	return ptr(s.signedURL(*key, thumbURLTTL))
}

func (s *Server) deleteBlobs(ctx context.Context, keys []string) {
	for _, k := range keys {
		if err := s.blob.Delete(ctx, k); err != nil {
			s.log.Warn("email: delete stored file", "key", k, "err", err)
		}
	}
}
