package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/netguard"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/photosource"
	"github.com/jeroentrappers/kinframe/server/internal/ratelimit"
	"github.com/jeroentrappers/kinframe/server/internal/secrets"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// The sync of one photo source (docs/spec/photo-sources.md §6.2): list the
// outside album, compare with the source's rows, download what is new,
// apply edits and removals, place the new items (one send per run; feeds
// debounced), and record the outcome.

func (s *Server) setupPhotoSources(g *netguard.Guard) error {
	if g == nil {
		var err error
		g, err = netguard.New(netguard.Config{PrivateNetworks: s.cfg.SourcesPrivateNetworks, AllowedHosts: s.cfg.SourcesAllowedHosts,
			CAFile: s.cfg.SourcesCAFile, PublicURL: s.cfg.PublicURL, AllowLoopback: s.cfg.SourcesAllowLoopback})
		if err != nil {
			return fmt.Errorf("network guard: %w", err)
		}
	}
	s.guard = g
	sealer, err := secrets.New(s.cfg.SecretsKey, s.cfg.SigningKey, "kinframe photo source secrets v1", "kinframe photo source secrets from signing key v1")
	if err != nil {
		return fmt.Errorf("photo source secrets: %w", err)
	}
	s.psSealer = sealer
	photosource.UserAgent = "kinframe/" + s.cfg.Version
	s.ps = &photoSources{
		immich:  &photosource.Immich{Guard: g},
		icloud:  &photosource.ICloud{Guard: g},
		google:  &photosource.Google{Guard: g, ClientID: s.cfg.GoogleClientID, ClientSecret: s.cfg.GoogleClientSecret, RedirectURI: s.cfg.PublicURL + "/v1/oauth/google/callback"},
		confirm: map[uuid.UUID]bool{},
	}
	s.ps.sched = &photosource.Scheduler{
		Log: s.log.With("component", "photo-sources"),
		Due: func(ctx context.Context, limit int) ([]photosource.Due, error) {
			providers := s.providersEnabled()
			if len(providers) == 0 {
				return nil, nil
			}
			rows, err := s.st.DuePhotoSources(ctx, s.now(), providers, limit)
			out := make([]photosource.Due, len(rows))
			for i, r := range rows {
				out[i] = photosource.Due{ID: r.ID, Host: r.Host}
			}
			return out, err
		},
		Run: func(ctx context.Context, id uuid.UUID) { s.RunPhotoSource(ctx, id) },
	}
	s.gi = &googleImports{running: map[uuid.UUID]bool{}, pollMin: time.Second}
	s.sourceTestUser = ratelimit.New(30, time.Hour)
	s.sourceCreateUser = ratelimit.New(30, time.Hour)
	s.sourceSyncUser = ratelimit.New(30, time.Hour)
	s.googleStartUser = ratelimit.New(20, time.Hour)
	return nil
}

// RunPhotoSources runs the sync scheduler and resumes Google imports until
// ctx is done.
func (s *Server) RunPhotoSources(ctx context.Context) {
	if err := s.st.ResetStaleRuns(ctx); err != nil && ctx.Err() == nil {
		s.log.Warn("photo sources: reset runs", "err", err)
	}
	s.resumeGoogleImports(ctx)
	s.ps.sched.Loop(ctx)
	s.gi.wg.Wait()
}

// runResult is what one run found and did.
type runResult struct {
	added, updated, removed, skipped, failed int
	bytes                                    int64

	err       *photosource.Error // the run failed with this code
	internal  error              // kinframe's own failure (database): retried soon
	listing   *photosource.Listing
	backlog   bool // pending items remain (byte or time budget)
	massN     int  // removals held back by the guard
	limitHit  bool
	holdSince *time.Time
	lastNewAt *time.Time
	flushAt   *time.Time
	firstDone bool // the first import is complete (nothing pending)
	sends     map[uuid.UUID]uuid.UUID
}

// RunPhotoSource syncs one source now (the scheduler calls it; tests too).
// A source that is running already is left alone.
func (s *Server) RunPhotoSource(ctx context.Context, id uuid.UUID) {
	started := s.now()
	claimed, err := s.st.ClaimPhotoSourceRun(ctx, id, started, time.Hour)
	if err != nil || !claimed {
		return
	}
	defer func() {
		if err := s.st.EndPhotoSourceRun(context.WithoutCancel(ctx), id); err != nil {
			s.log.Warn("photo source: end run", "source", id, "err", err)
		}
		s.publishSource(context.WithoutCancel(ctx), id, nil)
	}()
	v, err := s.st.GetPhotoSource(ctx, s.st.Q(), id)
	if err != nil || v.Status == "paused" {
		return
	}
	s.publishSource(ctx, id, nil) // running: true
	rctx, cancel := context.WithTimeout(ctx, photosource.RunBudget+5*time.Minute)
	defer cancel()
	res := s.syncSource(rctx, &v.PhotoSource, s.takeConfirm(id))
	if ctx.Err() != nil {
		return // shutting down: the next start runs it again
	}
	push := s.finishRun(context.WithoutCancel(ctx), id, started, res)
	if len(res.sends) > 0 {
		s.publishSendUpdates(ctx, res.sends, "")
	}
	if push != nil && v.OwnerUserID != nil {
		s.publishSource(ctx, id, map[uuid.UUID]*notify.Push{*v.OwnerUserID: push})
	}
}

// ownerCanManage re-checks the owner's rights (§4.3).
func (s *Server) ownerCanManage(ctx context.Context, p *store.PhotoSource) (bool, error) {
	if p.OwnerUserID == nil {
		return false, nil
	}
	u, err := s.st.GetUser(ctx, s.st.Q(), *p.OwnerUserID)
	if err == store.ErrNotFound || (err == nil && u.DeletedAt != nil) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if p.FeedID != nil {
		f, err := s.st.GetFeed(ctx, s.st.Q(), *p.FeedID)
		if err != nil || f.DeletedAt != nil {
			return false, err
		}
	}
	return s.canManageTarget(ctx, s.st.Q(), *p.OwnerUserID, targetOf(p))
}

func (s *Server) sourceConn(p *store.PhotoSource) (photosource.Conn, error) {
	u, err := s.psSealer.Open(p.URLEnc)
	if err != nil {
		return photosource.Conn{}, err
	}
	key, err := s.psSealer.Open(p.APIKeyEnc)
	if err != nil {
		return photosource.Conn{}, err
	}
	pass, err := s.psSealer.Open(p.PasswordEnc)
	if err != nil {
		return photosource.Conn{}, err
	}
	c := photosource.Conn{Provider: p.Provider, URL: u, APIKey: key, Password: pass, AlbumID: derefStr(p.UpstreamAlbumID)}
	if p.ResolvedHost != nil {
		c.ResolvedHost = *p.ResolvedHost
	}
	return c, nil
}

func captionOf(a *photosource.Asset) *string {
	c := strings.TrimSpace(a.Caption)
	if c == "" {
		return nil
	}
	if r := []rune(c); len(r) > 280 {
		c = string(r[:280])
	}
	return &c
}

func strOrNil(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// record keeps what the listing said about an asset on its row.
func record(r *store.SourceItem, a *photosource.Asset) {
	r.UpstreamChecksum = strOrNil(a.Checksum)
	r.UpstreamCaption = captionOf(a)
	r.UpstreamTakenAt = a.TakenAt
	r.UpstreamAddedAt = a.AddedAt
	r.UpstreamUpdated = strOrNil(a.Updated)
	r.Kind = strOrNil(a.Kind)
	if a.Size > 0 {
		r.Bytes = &a.Size
	}
}

func sameTime(a, b *time.Time) bool {
	if a == nil || b == nil {
		return a == nil && b == nil
	}
	return a.Equal(*b)
}

func takenOf(r *store.SourceItem) time.Time {
	if r.UpstreamTakenAt != nil {
		return *r.UpstreamTakenAt
	}
	return r.FirstSeenAt
}

func newestFirst(rows []*store.SourceItem) {
	sort.SliceStable(rows, func(i, j int) bool {
		ti, tj := takenOf(rows[i]), takenOf(rows[j])
		if !ti.Equal(tj) {
			return ti.After(tj)
		}
		return rows[i].UpstreamID > rows[j].UpstreamID
	})
}

func skip(r *store.SourceItem, reason string) {
	r.State, r.SkipReason = "skipped", &reason
}

// syncSource runs one sync and reports what happened; finishRun records it.
func (s *Server) syncSource(ctx context.Context, src *store.PhotoSource, confirm bool) *runResult {
	res := &runResult{sends: map[uuid.UUID]uuid.UUID{}, holdSince: src.HoldSince, lastNewAt: src.LastNewAt}
	fail := func(code, detail string) *runResult {
		res.err = &photosource.Error{Code: code, Detail: detail}
		return res
	}
	okOwner, err := s.ownerCanManage(ctx, src)
	if err != nil {
		res.internal = err
		return res
	}
	if !okOwner {
		return fail(photosource.CodeOwnerLostAccess, "the owner can no longer manage this album or feed")
	}
	conn, err := s.sourceConn(src)
	if err != nil {
		return fail(photosource.CodeCredentialsUnreadable, "the stored credentials can't be decrypted")
	}
	l, lerr := s.providerFor(src.Provider).List(ctx, conn)
	if lerr != nil {
		res.err = photosource.AsError(lerr)
		return res
	}
	res.listing = l
	now := s.now()
	limits := s.Limits(ctx).WithDefaults()
	stored, err := s.st.SourceItems(ctx, s.st.Q(), src.ID)
	if err != nil {
		res.internal = err
		return res
	}
	firstListing := len(stored) == 0
	rows := make(map[string]*store.SourceItem, len(stored))
	for i := range stored {
		rows[stored[i].UpstreamID] = &stored[i]
	}
	assets := make(map[string]*photosource.Asset, len(l.Assets))
	changed := map[string]bool{}
	type edit struct {
		row   *store.SourceItem
		asset *photosource.Asset
	}
	var edits []edit
	newAssets := 0
	for i := range l.Assets {
		a := &l.Assets[i]
		assets[a.ID] = a
		r := rows[a.ID]
		if r == nil {
			r = &store.SourceItem{SourceID: src.ID, UpstreamID: a.ID, State: "pending", FirstSeenAt: now}
			record(r, a)
			rows[a.ID], changed[a.ID] = r, true
			newAssets++
			continue
		}
		switch r.State {
		case "gone_upstream":
			// Back upstream: still here (keep) or imported again (mirror removed it).
			if r.ItemID != nil && s.itemLive(ctx, src, *r.ItemID) {
				r.State = "imported"
				if r.SendID == nil && src.FeedID == nil {
					r.State = "duplicate"
				}
			} else {
				r.State, r.ItemID, r.SendID, r.Attempts, r.FailureCode = "pending", nil, nil, 0, nil
				newAssets++
			}
			r.GoneAt = nil
			record(r, a)
			changed[a.ID] = true
		case "skipped":
			if r.SkipReason != nil && (*r.SkipReason == "video_off" && src.IncludeVideos || *r.SkipReason == "over_limit") {
				r.State, r.SkipReason = "pending", nil
				record(r, a)
				changed[a.ID] = true
			}
		case "failed":
			if r.RetryAt != nil && !r.RetryAt.After(now) && now.Sub(r.FirstSeenAt) < 7*24*time.Hour {
				r.State, r.FailureCode = "pending", nil
				changed[a.ID] = true
			}
		case "imported", "duplicate":
			if r.ItemID != nil && r.State == "imported" && (!sameStr(r.UpstreamCaption, captionOf(a)) || !sameTime(r.UpstreamTakenAt, a.TakenAt) ||
				(a.Checksum != "" && r.UpstreamChecksum != nil && *r.UpstreamChecksum != a.Checksum)) {
				edits = append(edits, edit{r, a})
			}
		}
	}
	// Gone: only a complete listing gets here (errors never remove anything).
	var removals []*store.SourceItem
	live := 0
	for id, r := range rows {
		if r.State == "imported" || r.State == "duplicate" {
			live++
		}
		if assets[id] != nil {
			continue
		}
		switch r.State {
		case "pending", "skipped", "failed":
			r.State, r.GoneAt = "gone_upstream", &now
			changed[id] = true
		case "imported", "duplicate":
			if src.OnUpstreamDelete == "mirror" && (r.SendID != nil || r.State == "duplicate") {
				removals = append(removals, r)
			} else {
				r.State, r.GoneAt = "gone_upstream", &now
				changed[id] = true
			}
		}
	}
	if len(removals) > 0 && photosource.MassRemoval(len(removals), live) && !confirm {
		res.massN, removals = len(removals), nil
	}
	// The pending rows, newest first.
	var pending []*store.SourceItem
	for _, r := range rows {
		if r.State == "pending" {
			pending = append(pending, r)
		}
	}
	newestFirst(pending)
	if firstListing {
		keep := len(pending)
		switch src.History {
		case "none":
			keep = 0
		case "last_n":
			if src.HistoryCount != nil {
				keep = min(keep, *src.HistoryCount)
			}
		}
		for _, r := range pending[keep:] {
			skip(r, "history")
			changed[r.UpstreamID] = true
		}
		pending = pending[:keep]
	}
	room := limits.MaxPhotoSourceItems - (live - len(removals))
	var wanted []*store.SourceItem
	for _, r := range pending {
		a := assets[r.UpstreamID]
		switch {
		case a.Kind == "video" && !src.IncludeVideos:
			skip(r, "video_off")
		case a.Kind == "video" && a.DurationMS > limits.MaxVideoSeconds*1000:
			skip(r, "too_long")
		case room <= 0:
			skip(r, "over_limit")
			res.limitHit = true
		default:
			room--
			wanted = append(wanted, r)
			continue
		}
		changed[r.UpstreamID] = true
	}
	for id := range changed {
		r := rows[id]
		if r.State == "skipped" {
			res.skipped++
		}
		if err := s.st.PutSourceItem(ctx, s.st.Q(), r); err != nil {
			res.internal = err
			return res
		}
	}
	if newAssets > 0 && src.FeedID != nil && !firstListing {
		res.lastNewAt = &now
	}

	// Downloads, newest first, within the run's budgets.
	deadline := time.Now().Add(photosource.RunBudget)
	for i, r := range wanted {
		if res.bytes >= limits.MaxPhotoSourceRunBytes || time.Now().After(deadline) {
			res.backlog = true
			break
		}
		a := assets[r.UpstreamID]
		if a.Offline {
			continue // can't be read right now: next run
		}
		if i%25 == 0 {
			batch := make([]photosource.Asset, 0, 25)
			for _, w := range wanted[i:min(i+25, len(wanted))] {
				batch = append(batch, *assets[w.UpstreamID])
			}
			if err := l.Prefetch(ctx, batch); err != nil {
				res.err = photosource.AsError(err)
				return res
			}
		}
		if err := s.importAsset(ctx, src, l, a, r, limits.MaxUploadBytes, limits.MaxVideoSeconds, res); err != nil {
			if ctx.Err() != nil {
				res.backlog = true
				return res
			}
			if pe := (*photosource.Error)(nil); errors.As(err, &pe) {
				res.err = pe
				return res
			}
			res.internal = err
			return res
		}
	}

	// Edits.
	for _, e := range edits {
		if err := s.applyEdit(ctx, src, l, e.row, e.asset, limits.MaxUploadBytes, res); err != nil {
			if pe := (*photosource.Error)(nil); errors.As(err, &pe) {
				res.err = pe
				return res
			}
			res.internal = err
			return res
		}
	}
	if err := s.swapReplacements(ctx, src); err != nil {
		res.internal = err
		return res
	}

	// Removals.
	if len(removals) > 0 {
		only := map[string]bool{}
		for _, r := range removals {
			only[r.UpstreamID] = true
		}
		err := s.st.InTx(ctx, func(tx *store.Tx) error {
			sends, err := s.removeSourceItems(ctx, tx, src, only)
			if err != nil {
				return err
			}
			for k, v := range sends {
				res.sends[k] = v
			}
			for _, r := range removals {
				r.State, r.GoneAt = "gone_upstream", &now
				if err := s.st.PutSourceItem(ctx, tx, r); err != nil {
					return err
				}
			}
			return nil
		})
		if err != nil {
			res.internal = err
			return res
		}
		res.removed += len(removals)
	}

	// Placing what this run (or an interrupted one) imported.
	if err := s.placeImported(ctx, src, res, now); err != nil {
		res.internal = err
		return res
	}
	res.firstDone = !res.backlog
	if !src.InitialDone && res.firstDone {
		for _, r := range rows {
			if r.State == "pending" {
				res.firstDone = false
			}
		}
	}
	return res
}

func sameStr(a, b *string) bool {
	if a == nil || b == nil {
		return a == nil && b == nil
	}
	return *a == *b
}

// itemLive: the item still shows in the target (placement on the album's
// frame, or a live post item / held item of the feed).
func (s *Server) itemLive(ctx context.Context, src *store.PhotoSource, itemID uuid.UUID) bool {
	var ok bool
	if src.FrameID != nil {
		_ = s.st.Pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM placements WHERE item_id = $1 AND frame_id = $2 AND removed_at IS NULL)`,
			itemID, *src.FrameID).Scan(&ok)
		return ok
	}
	_ = s.st.Pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM feed_post_items pi JOIN feed_posts p ON p.id = pi.post_id
		WHERE pi.item_id = $1 AND p.feed_id = $2 AND pi.removed_at IS NULL AND p.removed_at IS NULL)`, itemID, *src.FeedID).Scan(&ok)
	return ok
}

// scratchPath is a fresh file in the scratch volume.
func (s *Server) scratchPath() string {
	dir := s.scratch
	if dir == "" {
		dir = os.TempDir()
	}
	return filepath.Join(dir, "source-"+store.NewID().String())
}

// fetched is a downloaded file the pipeline can read.
type fetched struct {
	path string
	kind media.Kind
	mime string
	sha  string
	size int64
	dur  int
}

// fetchReadable downloads an asset and checks it like an upload: sniffed by
// magic bytes, videos probed. Unreadable originals fall back to Immich's
// renderings. skipReason is set (and no file kept) when it can't be used.
func (s *Server) fetchReadable(ctx context.Context, src *store.PhotoSource, l *photosource.Listing, a *photosource.Asset, variant string,
	maxBytes int64, maxVideoS int) (*fetched, string, error) {
	path := s.scratchPath()
	tries := []string{variant}
	if variant == "" && (src.Provider == photosource.ImmichLink || src.Provider == photosource.ImmichAPI) && a.Kind != "video" {
		tries = []string{"", "fullsize", "preview"}
	}
	var lastSkip string
	for _, v := range tries {
		d, err := l.Download(ctx, *a, v, path, maxBytes)
		if err != nil {
			pe := photosource.AsError(err)
			switch pe.Code {
			case photosource.CodeTooLarge:
				lastSkip = "too_large"
				continue
			case photosource.CodeFormatChanged:
				lastSkip = "unavailable"
				continue
			}
			return nil, "", pe
		}
		kind, mime, err := media.Sniff(d.Path)
		if err != nil {
			_ = os.Remove(d.Path)
			lastSkip = "unsupported"
			continue
		}
		f := &fetched{path: d.Path, kind: kind, mime: mime, sha: d.SHA256, size: d.Bytes}
		if kind == media.Video {
			info, err := s.tools.ProbeVideo(ctx, d.Path)
			if err != nil {
				_ = os.Remove(d.Path)
				return nil, "corrupt", nil
			}
			if info.DurationMS > maxVideoS*1000 {
				_ = os.Remove(d.Path)
				return nil, "too_long", nil
			}
			f.dur = info.DurationMS
		}
		return f, "", nil
	}
	return nil, lastSkip, nil
}

// importAsset downloads one pending asset and makes it an item of the source
// (or marks it duplicate/skipped/failed). Provider errors that are not about
// this one file are returned and stop the run.
func (s *Server) importAsset(ctx context.Context, src *store.PhotoSource, l *photosource.Listing, a *photosource.Asset, r *store.SourceItem,
	maxBytes int64, maxVideoS int, res *runResult) error {
	now := s.now()
	save := func() error { return s.st.PutSourceItem(ctx, s.st.Q(), r) }
	// The same upstream file another source already brought into the target (§8.1, before downloading).
	if a.Checksum != "" {
		if itemID, found, err := s.duplicateInTarget(ctx, src, "", a.Checksum); err != nil {
			return err
		} else if found {
			return s.markDuplicate(ctx, src, r, itemID)
		}
	}
	f, skipReason, err := s.fetchReadable(ctx, src, l, a, "", maxBytes, maxVideoS)
	if err != nil {
		return err
	}
	switch skipReason {
	case "":
	case "too_large", "too_long", "unsupported":
		skip(r, skipReason)
		res.skipped++
		return save()
	default: // unavailable, corrupt: retried, then failed (daily for a week)
		r.Attempts++
		if r.Attempts >= 3 {
			r.State, r.FailureCode = "failed", &skipReason
			next := now.Add(24 * time.Hour)
			r.RetryAt = &next
			res.failed++
		}
		return save()
	}
	res.bytes += f.size
	r.ContentSHA256 = &f.sha
	if itemID, found, err := s.duplicateInTarget(ctx, src, f.sha, ""); err != nil {
		_ = os.Remove(f.path)
		return err
	} else if found {
		_ = os.Remove(f.path)
		return s.markDuplicate(ctx, src, r, itemID)
	}
	var motion *fetched
	if a.Kind == "live" && a.MotionID != "" {
		if m, reason, err := s.fetchReadable(ctx, src, l, a, "motion", maxBytes, maxVideoS); err == nil && reason == "" && m.kind == media.Video {
			motion = m
			res.bytes += m.size
		}
	}
	service, _ := serviceOf(src.Provider)
	item := store.Item{ID: store.NewID(), SenderUserID: src.OwnerUserID, Caption: captionOf(a), CapturedOverride: a.TakenAt,
		Via: &service, ViaSourceID: &src.ID}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		mediaID, err := s.mediaFor(ctx, tx, *src.OwnerUserID, f)
		if err != nil {
			return err
		}
		item.MediaID = mediaID
		if motion != nil {
			mid := store.NewID()
			path, mime := motion.path, motion.mime
			if err := tx.CreateMedia(ctx, store.Media{ID: mid, OwnerUserID: src.OwnerUserID, Kind: string(media.Video), SourcePath: &path,
				SourceMime: &mime, Motion: true, SourceSHA256: &motion.sha}); err != nil {
				return err
			}
			if err := tx.Enqueue(ctx, store.JobProcessMedia, map[string]any{"media_id": mid}); err != nil {
				return err
			}
			item.MotionMediaID = &mid
		}
		if err := tx.CreateItem(ctx, item); err != nil {
			return err
		}
		r.State, r.ItemID, r.SendID, r.ImportedAt, r.FailureCode = "imported", &item.ID, nil, &now, nil
		r.Bytes = &f.size
		record(r, a)
		return s.st.PutSourceItem(ctx, tx, r)
	})
	if err != nil {
		_ = os.Remove(f.path)
		if motion != nil {
			_ = os.Remove(motion.path)
		}
		return err
	}
	res.added++
	return nil
}

// mediaFor creates the media for a downloaded file, or reuses the owner's
// media from the same file (§8.1 step 3), deleting the download then.
func (s *Server) mediaFor(ctx context.Context, tx *store.Tx, owner uuid.UUID, f *fetched) (uuid.UUID, error) {
	if dup, err := s.st.FindDuplicateItem(ctx, owner, f.sha); err != nil {
		return uuid.Nil, err
	} else if dup != nil {
		if row, err := s.st.GetItemRow(ctx, tx, *dup); err == nil {
			_ = os.Remove(f.path)
			if err := tx.Enqueue(ctx, store.JobEnsureRenditions, map[string]any{"media_id": row.MediaID}); err != nil {
				return uuid.Nil, err
			}
			return row.MediaID, nil
		}
	}
	id := store.NewID()
	path, mime := f.path, f.mime
	md := store.Media{ID: id, OwnerUserID: &owner, Kind: string(f.kind), SourcePath: &path, SourceMime: &mime, IncludeAudio: true, SourceSHA256: &f.sha}
	if f.kind == media.Video {
		md.DurationMS = &f.dur
	}
	if err := tx.CreateMedia(ctx, md); err != nil {
		return uuid.Nil, err
	}
	return id, tx.Enqueue(ctx, store.JobProcessMedia, map[string]any{"media_id": id})
}

// duplicateInTarget looks for the same photo in the target (§8.1 step 2).
func (s *Server) duplicateInTarget(ctx context.Context, src *store.PhotoSource, sha, checksum string) (uuid.UUID, bool, error) {
	if src.FrameID != nil {
		_, item, found, err := s.st.DuplicateOnFrame(ctx, s.st.Q(), *src.FrameID, sha, checksum)
		return item, found, err
	}
	return s.st.DuplicateInFeed(ctx, s.st.Q(), *src.FeedID, sha, checksum)
}

// markDuplicate records a duplicate; for an album the existing photo on the
// frame joins the album.
func (s *Server) markDuplicate(ctx context.Context, src *store.PhotoSource, r *store.SourceItem, itemID uuid.UUID) error {
	now := s.now()
	return s.st.InTx(ctx, func(tx *store.Tx) error {
		if src.AlbumID != nil {
			f, err := tx.LockFrame(ctx, *src.FrameID)
			if err != nil {
				return err
			}
			var pid uuid.UUID
			if err := tx.QueryRow(ctx, `SELECT id FROM placements WHERE frame_id = $1 AND item_id = $2 AND removed_at IS NULL`, f.ID, itemID).Scan(&pid); err == nil {
				if err := tx.AddToAlbum(ctx, f.ID, *src.AlbumID, []uuid.UUID{pid}); err != nil {
					return err
				}
			}
		}
		r.State, r.ItemID, r.ImportedAt = "duplicate", &itemID, &now
		return s.st.PutSourceItem(ctx, tx, r)
	})
}

// applyEdit follows upstream changes of an imported item (§8.4).
func (s *Server) applyEdit(ctx context.Context, src *store.PhotoSource, l *photosource.Listing, r *store.SourceItem, a *photosource.Asset,
	maxBytes int64, res *runResult) error {
	fileChanged := a.Checksum != "" && r.UpstreamChecksum != nil && *r.UpstreamChecksum != a.Checksum
	var repl *fetched
	if fileChanged && r.ReplacementMediaID == nil {
		f, reason, err := s.fetchReadable(ctx, src, l, a, "", maxBytes, s.Limits(ctx).MaxVideoSeconds)
		if err != nil {
			return err
		}
		if reason == "" {
			repl = f
			res.bytes += f.size
		}
	}
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		touched := false
		if !sameStr(r.UpstreamCaption, captionOf(a)) {
			ch, err := tx.SetUpstreamCaption(ctx, *r.ItemID, captionOf(a))
			if err != nil {
				return err
			}
			touched = touched || ch
		}
		if !sameTime(r.UpstreamTakenAt, a.TakenAt) && a.TakenAt != nil {
			ch, err := tx.SetCapturedOverride(ctx, *r.ItemID, a.TakenAt)
			if err != nil {
				return err
			}
			touched = touched || ch
		}
		if repl != nil {
			id := store.NewID()
			path, mime := repl.path, repl.mime
			if err := tx.CreateMedia(ctx, store.Media{ID: id, OwnerUserID: src.OwnerUserID, Kind: string(repl.kind), SourcePath: &path,
				SourceMime: &mime, IncludeAudio: true, SourceSHA256: &repl.sha}); err != nil {
				return err
			}
			if err := tx.Enqueue(ctx, store.JobProcessMedia, map[string]any{"media_id": id}); err != nil {
				return err
			}
			r.ReplacementMediaID, r.ContentSHA256 = &id, &repl.sha
		}
		if touched {
			if err := s.touchItemPlacements(ctx, tx, *r.ItemID); err != nil {
				return err
			}
		}
		if !fileChanged || repl != nil {
			record(r, a)
		} else {
			// Keep the old checksum so the file change is tried again next run.
			old := r.UpstreamChecksum
			record(r, a)
			r.UpstreamChecksum = old
		}
		return s.st.PutSourceItem(ctx, tx, r)
	})
	if err != nil {
		if repl != nil {
			_ = os.Remove(repl.path)
		}
		return err
	}
	res.updated++
	return nil
}

// touchItemPlacements re-emits an item's placements on every frame (caption,
// capture time changed).
func (s *Server) touchItemPlacements(ctx context.Context, tx *store.Tx, itemID uuid.UUID) error {
	pls, err := s.st.LivePlacementsOfItem(ctx, tx, itemID)
	if err != nil {
		return err
	}
	for _, p := range pls {
		if err := tx.FeedPut(ctx, p[1], store.FeedPlacement, p[0], false); err != nil {
			return err
		}
	}
	return nil
}

// swapReplacements puts processed replacement files in place: same item,
// same placements; frames re-download the new renditions.
func (s *Server) swapReplacements(ctx context.Context, src *store.PhotoSource) error {
	rows, err := s.st.SourceItems(ctx, s.st.Q(), src.ID)
	if err != nil {
		return err
	}
	for i := range rows {
		r := &rows[i]
		if r.ReplacementMediaID == nil || r.ItemID == nil {
			continue
		}
		st, err := s.st.MediaStatus(ctx, s.st.Q(), *r.ReplacementMediaID)
		if err != nil && err != store.ErrNotFound {
			return err
		}
		if st == "processing" {
			continue
		}
		err = s.st.InTx(ctx, func(tx *store.Tx) error {
			if st == "ready" {
				if err := tx.SwapItemMedia(ctx, *r.ItemID, *r.ReplacementMediaID); err != nil {
					return err
				}
				if err := tx.Enqueue(ctx, store.JobThumbs, map[string]any{"item_id": *r.ItemID}); err != nil {
					return err
				}
				// Renditions for every frame; the job re-emits the placements once they exist.
				if err := tx.Enqueue(ctx, store.JobEnsureRenditions, map[string]any{"media_id": *r.ReplacementMediaID}); err != nil {
					return err
				}
			}
			r.ReplacementMediaID = nil
			return s.st.PutSourceItem(ctx, tx, r)
		})
		if err != nil {
			return err
		}
	}
	return nil
}

// placeImported makes sends of the imported items not placed yet: album
// targets at once (quietly on the first import); feed targets at once on
// the first import, else once the upstream album has been quiet (debounce).
func (s *Server) placeImported(ctx context.Context, src *store.PhotoSource, res *runResult, now time.Time) error {
	rows, err := s.st.SourceItems(ctx, s.st.Q(), src.ID)
	if err != nil {
		return err
	}
	var todo []*store.SourceItem
	for i := range rows {
		if rows[i].State == "imported" && rows[i].SendID == nil && rows[i].ItemID != nil {
			todo = append(todo, &rows[i])
		}
	}
	quiet := !src.InitialDone
	if len(todo) == 0 {
		res.holdSince, res.lastNewAt = nil, nil
		return nil
	}
	if src.FeedID != nil && !quiet {
		if res.holdSince == nil {
			res.holdSince = &now
		}
		last := res.lastNewAt
		if last == nil {
			last = res.holdSince
		}
		at := photosource.FlushAt(*res.holdSince, *last)
		if now.Before(at) {
			res.flushAt = &at
			return nil
		}
	}
	// Capture order: oldest first.
	sort.SliceStable(todo, func(i, j int) bool { return takenOf(todo[i]).Before(takenOf(todo[j])) })
	maxPer := s.Limits(ctx).MaxItemsPerSend
	for start := 0; start < len(todo); start += maxPer {
		chunk := todo[start:min(start+maxPer, len(todo))]
		if err := s.placeChunk(ctx, src, chunk, quiet, res); err != nil {
			return err
		}
	}
	res.holdSince, res.lastNewAt, res.flushAt = nil, nil, nil
	return nil
}

// placeChunk is one source send: placements on the album's frame (appended
// to the album), or one post in the feed.
func (s *Server) placeChunk(ctx context.Context, src *store.PhotoSource, chunk []*store.SourceItem, quiet bool, res *runResult) error {
	service, _ := serviceOf(src.Provider)
	sendID := store.NewID()
	return s.st.InTx(ctx, func(tx *store.Tx) error {
		items := make([]store.SourceSendItem, 0, len(chunk))
		var kept []*store.SourceItem
		for _, r := range chunk {
			row, err := s.st.GetItemRow(ctx, tx, *r.ItemID)
			if err != nil {
				continue // the item is gone (owner deleted it): leave the row
			}
			it := store.SourceSendItem{ItemID: row.ItemID, MediaID: row.MediaID, MotionID: row.MotionMediaID, TakenAt: r.UpstreamTakenAt}
			it.ReceivedAt = r.UpstreamAddedAt
			if it.ReceivedAt == nil {
				it.ReceivedAt = r.UpstreamTakenAt
			}
			items = append(items, it)
			kept = append(kept, r)
		}
		if len(items) == 0 {
			return nil
		}
		if err := tx.CreateSourceSend(ctx, sendID, *src.OwnerUserID, service, &src.ID, nil, items); err != nil {
			return err
		}
		if src.AlbumID != nil {
			if err := s.placeInAlbum(ctx, tx, *src.FrameID, *src.AlbumID, *src.OwnerUserID, sendID, items, quiet); err != nil {
				return err
			}
		} else {
			ids := make([]uuid.UUID, len(items))
			for i, it := range items {
				ids[i] = it.ItemID
			}
			postID := store.NewID()
			if err := tx.CreateFeedPost(ctx, postID, *src.FeedID, &sendID, *src.OwnerUserID, ids); err != nil {
				return err
			}
			if quiet {
				if _, err := tx.Exec(ctx, `UPDATE feed_posts SET quiet = true WHERE id = $1`, postID); err != nil {
					return err
				}
			}
			if err := tx.Enqueue(ctx, store.JobFeedFanout, store.PostJob{PostID: postID}); err != nil {
				return err
			}
		}
		for _, r := range kept {
			r.SendID = &sendID
			if err := s.st.PutSourceItem(ctx, tx, r); err != nil {
				return err
			}
		}
		res.sends[sendID] = *src.OwnerUserID
		return nil
	})
}

// placeInAlbum places a source send's items on the album's frame and
// appends them to the album. First-import photos are history (backfill,
// received at their upstream time). The frame's moderation applies unless
// the owner manages the frame.
func (s *Server) placeInAlbum(ctx context.Context, tx *store.Tx, frameID, albumID, owner, sendID uuid.UUID, items []store.SourceSendItem, quiet bool) error {
	f, err := tx.LockFrame(ctx, frameID)
	if err != nil {
		return err
	}
	moderated := false
	if f.Moderation {
		m, err := s.st.GetMembership(ctx, tx, frameID, owner)
		moderated = err != nil || !m.IsManager()
	}
	var pids []uuid.UUID
	var created []placed
	for _, it := range items {
		var existing uuid.UUID
		if err := tx.QueryRow(ctx, `SELECT id FROM placements WHERE frame_id = $1 AND item_id = $2 AND removed_at IS NULL`, frameID, it.ItemID).Scan(&existing); err == nil {
			pids = append(pids, existing)
			continue
		}
		p := store.Placement{ID: store.NewID(), FrameID: frameID, ItemID: it.ItemID, SendID: &sendID, Status: "pending",
			PendingModeration: moderated, Backfill: quiet}
		if quiet && it.ReceivedAt != nil {
			p.ReceivedAt = *it.ReceivedAt
		}
		if err := tx.CreatePlacement(ctx, p); err != nil {
			return err
		}
		pids = append(pids, p.ID)
		created = append(created, placed{PlacementID: p.ID, FrameID: frameID, ItemID: it.ItemID, MediaID: it.MediaID, MotionID: it.MotionID, SendID: &sendID})
	}
	if err := tx.AddToAlbum(ctx, frameID, albumID, pids); err != nil {
		return err
	}
	return ensureRenditionsFor(ctx, tx, created)
}

// ---------------------------------------------------------------- outcome

// finishRun records a run (§5.1 transitions) and returns the attention push
// for the owner, if one is due.
func (s *Server) finishRun(ctx context.Context, id uuid.UUID, started time.Time, res *runResult) *notify.Push {
	now := s.now()
	var push *notify.Push
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		src, err := tx.LockPhotoSource(ctx, id)
		if err != nil {
			return err
		}
		run := runJSON{StartedAt: started, FinishedAt: &now, Added: res.added, Updated: res.updated, Removed: res.removed,
			Skipped: res.skipped, Failed: res.failed, Bytes: res.bytes}
		if l := res.listing; l != nil {
			warnings := append([]string{}, l.Warnings...)
			if res.limitHit {
				warnings = append(warnings, photosource.WarnItemLimit)
			}
			src.Warnings = warnings
			src.ChangeHint = strOrNil(l.Hint)
			n := len(l.Assets)
			src.UpstreamCount = &n
			src.FullListingAt = &now
			if l.ResolvedHost != "" {
				src.ResolvedHost = &l.ResolvedHost
			}
		}
		src.HoldSince, src.LastNewAt = res.holdSince, res.lastNewAt
		paused := src.Status == "paused"
		code := ""
		switch {
		case res.internal != nil:
			s.log.Error("photo source run", "source", id, "err", res.internal)
			src.NextSyncAt = now.Add(5 * time.Minute)
		case res.err != nil:
			code = res.err.Code
			run.ErrorCode = &code
			src.ErrorCode, src.ErrorDetail = &code, ptr(res.err.Detail)
			if src.ErrorSince == nil {
				src.ErrorSince = &now
			}
			src.Failures++
			if photosource.Transient(code) {
				if src.Failures >= photosource.ErrorRuns || now.Sub(*src.ErrorSince) >= photosource.ErrorAfter {
					src.Status = "error"
				}
				src.NextSyncAt = now.Add(photosource.Backoff(src.Failures))
			} else {
				src.Status = "needs_attention"
				src.NextSyncAt = farFuture
				if photosource.Probed(code, src.Provider) {
					src.NextSyncAt = now.Add(photosource.ProbeInterval)
				}
			}
		case res.massN > 0:
			code = photosource.CodeMassRemoval
			src.Status, src.ErrorCode, src.PendingRemovals = "needs_attention", &code, res.massN
			src.ErrorDetail = ptr(fmt.Sprintf("%d photos disappeared from the album", res.massN))
			src.ErrorSince, src.Failures, src.NextSyncAt = &now, 0, farFuture
			src.LastSyncedAt = &now
		default:
			src.Status, src.ErrorCode, src.ErrorDetail, src.ErrorSince, src.Failures, src.PendingRemovals = "active", nil, nil, nil, 0, 0
			src.LastSyncedAt = &now
			if res.firstDone {
				src.InitialDone = true
			}
			src.NextSyncAt = photosource.NextRegular(src.Provider, s.cfg.PhotoSourceSync, now)
			if res.backlog {
				src.NextSyncAt = now.Add(photosource.CatchUp)
			}
			if res.flushAt != nil && res.flushAt.Before(src.NextSyncAt) {
				src.NextSyncAt = *res.flushAt
			}
		}
		if paused {
			src.Status = "paused"
		}
		raw, _ := json.Marshal(run)
		src.LastRun = raw
		// Pushes (§10): needing attention, or 3 days in error; once per code per week.
		if code != "" && !paused && (src.Status == "needs_attention" || (src.Status == "error" && now.Sub(*src.ErrorSince) >= photosource.ErrorPushAfter)) {
			sent := map[string]time.Time{}
			_ = json.Unmarshal(src.AttentionPushed, &sent)
			if last, ok := sent[code]; !ok || now.Sub(last) >= photosource.AttentionEvery {
				sent[code] = now
				src.AttentionPushed, _ = json.Marshal(sent)
				push = s.attentionPush(src, code)
			}
		}
		return tx.SavePhotoSource(ctx, src)
	})
	if err != nil && err != store.ErrNotFound {
		s.log.Error("photo source: record run", "source", id, "err", err)
	}
	return push
}

func (s *Server) attentionPush(src *store.PhotoSource, code string) *notify.Push {
	ids := map[string]string{"source_id": src.ID.String()}
	if src.AlbumID != nil {
		ids["album_id"], ids["frame_id"] = src.AlbumID.String(), src.FrameID.String()
	}
	if src.FeedID != nil {
		ids["feed_id"] = src.FeedID.String()
	}
	p := &notify.Push{Key: "photo_source_attention", Args: map[string]string{"name": src.Name, "host": src.UpstreamHost}, IDs: ids}
	if photosource.Transient(code) {
		p.Text = "photo_source_unreachable"
	}
	return p
}
