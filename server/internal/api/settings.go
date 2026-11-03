package api

import (
	"context"
	"encoding/json"
	"strconv"
	"strings"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/settings"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

var settingsSchema *openapi3.Schema

func init() {
	doc, err := openapi3.NewLoader().LoadFromData(gen.PreparedSpec)
	if err != nil {
		panic(err)
	}
	settingsSchema = doc.Components.Schemas["FrameSettings"].Value
}

// settingsDoc renders the stored settings with their version, as people see them
// (without the owner PIN hash, plugin secrets only by name).
func (s *Server) settingsDoc(f *store.Frame) json.RawMessage { return s.renderSettings(f, false) }

// deviceSettingsDoc renders the settings for the frame itself, including the owner PIN hash and plugin secrets.
func (s *Server) deviceSettingsDoc(f *store.Frame) json.RawMessage { return s.renderSettings(f, true) }

func (s *Server) renderSettings(f *store.Frame, forDevice bool) json.RawMessage {
	var m map[string]any
	_ = json.Unmarshal(f.Settings, &m)
	if m == nil {
		m = map[string]any{}
	}
	settings.DerivePinSet(m)
	if !forDevice {
		settings.StripPinHash(m)
	}
	settings.RenderPluginSecrets(m, s.pluginSealer, forDevice)
	m["version"] = f.SettingsVersion
	b, _ := json.Marshal(m)
	return b
}

func parseIfMatch(v string) (int, error) {
	v = strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(v), "W/"))
	n, err := strconv.Atoi(strings.Trim(v, `"`))
	if err != nil || n < 0 {
		return 0, problem.Validation("If-Match", "format", `If-Match must be a quoted version, e.g. "7"`).WithStatus(400)
	}
	return n, nil
}

// patchSettings applies a merge patch. A stale If-Match is accepted when none
// of the patched fields changed since that version; otherwise 412.
func (s *Server) patchSettings(ctx context.Context, frameID uuid.UUID, ifMatch string, patch map[string]any, actor settings.PinActor) (json.RawMessage, error) {
	base, err := parseIfMatch(ifMatch)
	if err != nil {
		return nil, err
	}
	if err := settings.ApplyPinRules(patch, actor); err != nil {
		if err == settings.ErrPinOwnerOnly {
			return nil, problem.New(problem.Forbidden, "%s", err.Error())
		}
		return nil, problem.Validation("kiosk.owner_pin", "pattern", err.Error())
	}
	render := s.settingsDoc
	if actor == settings.ActorDevice {
		render = s.deviceSettingsDoc
	}
	settings.Sanitize(patch)
	paths := settings.LeafPaths(patch)
	var out json.RawMessage
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		f, err := tx.LockFrame(ctx, frameID)
		if err != nil {
			return notFound(err, "frame")
		}
		if base > f.SettingsVersion || (base < f.SettingsVersion && settings.Conflicts(paths, base, f.SettingsFieldVersions)) {
			p := problem.New(problem.VersionConflict, "settings changed since version %d (now %d)", base, f.SettingsVersion)
			return p
		}
		if len(paths) == 0 {
			out = render(f)
			return nil
		}
		var doc map[string]any
		if err := json.Unmarshal(f.Settings, &doc); err != nil {
			return err
		}
		s.preparePlugins(patch, doc)
		doc = settings.MergePatch(doc, patch)
		settings.DerivePinSet(doc)
		if err := validateSettings(doc); err != nil {
			return err
		}
		if err := s.checkPlugins(ctx, tx, f, doc); err != nil {
			return err
		}
		if err := s.syncStations(ctx, tx, frameID, doc); err != nil {
			return err
		}
		version := f.SettingsVersion + 1
		fv := f.SettingsFieldVersions
		if fv == nil {
			fv = map[string]int{}
		}
		for _, p := range paths {
			fv[p] = version
		}
		raw, err := json.Marshal(doc)
		if err != nil {
			return err
		}
		if err := tx.SetFrameSettings(ctx, frameID, raw, version, fv); err != nil {
			return err
		}
		if err := tx.FeedPut(ctx, frameID, store.FeedSettings, frameID, false); err != nil {
			return err
		}
		f.Settings, f.SettingsVersion = raw, version
		out = render(f)
		return nil
	})
	if err == nil && s.weather != nil && touchesWeather(paths) {
		s.weather.Kick(frameID)
	}
	if err == nil && s.calendar != nil && touchesCalendar(paths) {
		s.calendar.RebuildLater(frameID)
	}
	return out, err
}

// touchesWeather reports whether a settings patch may change what weather a frame needs.
func touchesWeather(paths []string) bool {
	for _, p := range paths {
		if p == "location" || strings.HasPrefix(p, "location.") || p == "widgets" || p == "widgets.weather" || p == "widgets.calendar_sidebar" {
			return true
		}
	}
	return false
}

// touchesCalendar reports whether a settings patch may move a frame's calendar window or zone.
func touchesCalendar(paths []string) bool {
	for _, p := range paths {
		if p == "calendar" || p == "calendar.past_days" || p == "calendar.future_days" || p == "locale" || p == "locale.timezone" {
			return true
		}
	}
	return false
}

func validateSettings(doc map[string]any) error {
	// Round-trip through JSON so numbers are float64 as the validator expects.
	b, _ := json.Marshal(doc)
	var v any
	_ = json.Unmarshal(b, &v)
	if m, ok := v.(map[string]any); ok {
		m["version"] = 1
	}
	if err := settingsSchema.VisitJSON(v, openapi3.MultiErrors()); err != nil {
		p := problem.New(problem.ValidationFailed, "invalid settings")
		var me openapi3.MultiError
		if multi, ok := err.(openapi3.MultiError); ok {
			me = multi
		} else {
			me = openapi3.MultiError{err}
		}
		for _, e := range me {
			if se, ok := e.(*openapi3.SchemaError); ok {
				p.WithField(strings.Join(se.JSONPointer(), "."), se.SchemaField)
			} else {
				p.WithField("", e.Error())
			}
		}
		return p
	}
	return nil
}

func (s *Server) GetFrameSettings(ctx context.Context, req gen.GetFrameSettingsRequestObject) (gen.GetFrameSettingsResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	if err := requirePerm(m, m.Effective().ChangeSettings, "change_settings"); err != nil {
		return nil, err
	}
	f, err := s.st.GetFrame(ctx, s.st.Q(), req.FrameId)
	if err != nil {
		return nil, notFound(err, "frame")
	}
	return Response{Status: 200, Body: s.settingsDoc(f), Header: etag(f.SettingsVersion)}, nil
}

func etag(version int) map[string][]string {
	return map[string][]string{"ETag": {`"` + strconv.Itoa(version) + `"`}}
}

func (s *Server) UpdateFrameSettings(ctx context.Context, req gen.UpdateFrameSettingsRequestObject) (gen.UpdateFrameSettingsResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	if err := requirePerm(m, m.Effective().ChangeSettings, "change_settings"); err != nil {
		return nil, err
	}
	actor := settings.ActorOther
	if m.Role == "owner" {
		actor = settings.ActorOwner
	}
	doc, err := s.patchSettings(ctx, req.FrameId, req.Params.IfMatch, *req.Body, actor)
	if err != nil {
		return nil, err
	}
	return Response{Status: 200, Body: doc}, nil
}

func (s *Server) DevicePatchSettings(ctx context.Context, req gen.DevicePatchSettingsRequestObject) (gen.DevicePatchSettingsResponseObject, error) {
	f, err := s.deviceFrame(ctx)
	if err != nil {
		return nil, err
	}
	doc, err := s.patchSettings(ctx, f.ID, req.Params.IfMatch, *req.Body, settings.ActorDevice)
	if err != nil {
		return nil, err
	}
	return Response{Status: 200, Body: doc}, nil
}
