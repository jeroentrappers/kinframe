package api

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"os"
	"strconv"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/plugins"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/registry"
	"github.com/jeroentrappers/kinframe/server/internal/settings"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// What a frame may store about its plugins: a catalog with translations of up
// to 100 manifests, and a status with the entities of up to 8 Home Assistant
// connections.
const (
	maxPluginCatalog = 1 << 20
	maxPluginStatus  = 2 << 20
)

// GetFramePlugins: the catalog and the status, for the apps' Plugins section.
func (s *Server) GetFramePlugins(ctx context.Context, req gen.GetFramePluginsRequestObject) (gen.GetFramePluginsResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	if err := requirePerm(m, m.Effective().ChangeSettings, "change_settings"); err != nil {
		return nil, err
	}
	p, err := s.st.GetFramePlugins(ctx, s.st.Q(), req.FrameId)
	if err != nil {
		return nil, notFound(err, "frame")
	}
	out := gen.FramePlugins{Catalog: []gen.CatalogPlugin{}, CatalogAt: p.CatalogAt, StatusAt: p.StatusAt}
	if len(p.Catalog) > 0 {
		var c gen.PluginCatalog
		if err := json.Unmarshal(p.Catalog, &c); err == nil {
			out.Catalog = c.Plugins
		}
	}
	// The community registry's plugins (newest version each), unless the frame has one built in.
	if s.registry != nil && p.CatalogAt != nil {
		if _, _, ix := s.registry.Signed(); ix != nil {
			have := map[string]bool{}
			for _, c := range out.Catalog {
				if id, _ := c.Manifest["id"].(string); id != "" {
					have[id] = true
				}
			}
			builtin := false
			for _, v := range ix.Latest() {
				if id, _ := v.Manifest["id"].(string); id == "" || have[id] {
					continue
				}
				locales := v.Locales
				out.Catalog = append(out.Catalog, gen.CatalogPlugin{Manifest: v.Manifest, Locales: &locales, Builtin: &builtin})
			}
		}
	}
	if len(p.Status) > 0 {
		var st gen.PluginStatusReport
		if err := json.Unmarshal(p.Status, &st); err == nil {
			out.Instances, out.Services = st.Instances, st.Services
		}
	}
	return Response{Status: 200, Body: out}, nil
}

func (s *Server) DevicePutPluginCatalog(ctx context.Context, req gen.DevicePutPluginCatalogRequestObject) (gen.DevicePutPluginCatalogResponseObject, error) {
	f, err := s.deviceFrame(ctx)
	if err != nil {
		return nil, err
	}
	raw, err := json.Marshal(req.Body)
	if err != nil {
		return nil, err
	}
	if len(raw) > maxPluginCatalog {
		return nil, problem.Validation("plugins", "maxLength", "body too large")
	}
	if err := s.st.SetPluginCatalog(ctx, f.ID, raw); err != nil {
		return nil, err
	}
	return noContent(), nil
}

func (s *Server) DevicePutPluginStatus(ctx context.Context, req gen.DevicePutPluginStatusRequestObject) (gen.DevicePutPluginStatusResponseObject, error) {
	f, err := s.deviceFrame(ctx)
	if err != nil {
		return nil, err
	}
	raw, err := json.Marshal(req.Body)
	if err != nil {
		return nil, err
	}
	if len(raw) > maxPluginStatus {
		return nil, problem.Validation("instances", "maxLength", "body too large")
	}
	if err := s.st.SetPluginStatus(ctx, f.ID, raw); err != nil {
		return nil, err
	}
	return noContent(), nil
}

// preparePlugins seals the secrets of a settings patch that sets
// plugins.instances, keeping the ones each instance already has.
func (s *Server) preparePlugins(patch, current map[string]any) {
	settings.ApplyPluginSecrets(patch, current, s.pluginSealer)
}

// checkPlugins validates the instances of a merged settings document against
// the frame's catalog.
func (s *Server) checkPlugins(ctx context.Context, tx *store.Tx, f *store.Frame, doc map[string]any) error {
	instances, ok := settings.PluginInstances(doc)
	if !ok {
		return nil
	}
	var lookup plugins.Lookup
	if p, err := s.st.GetFramePlugins(ctx, tx, f.ID); err == nil && len(p.Catalog) > 0 {
		catalog, _ := plugins.ParseCatalog(p.Catalog)
		lookup = s.pluginLookup(catalog)
	}
	problems := plugins.Check(instances, lookup)
	if len(problems) == 0 {
		return nil
	}
	pr := problem.New(problem.ValidationFailed, "invalid settings")
	for _, p := range problems {
		pr.WithField(p.Path, p.Reason)
	}
	return pr
}

// pluginLookup finds an instance's manifest: built into the frame, else the registry version it names (or the newest
// one when it names none).
func (s *Server) pluginLookup(builtin plugins.Catalog) plugins.Lookup {
	var ix *registry.Index
	if s.registry != nil {
		_, _, ix = s.registry.Signed()
	}
	return func(id, version string) *plugins.Manifest {
		if m := builtin[id]; m != nil {
			return m
		}
		if ix == nil {
			return nil
		}
		if version != "" && version != "builtin" {
			if v := ix.Find(id, version); v != nil && !ix.IsRevoked(id, version) {
				return plugins.ParseManifest(v.Manifest)
			}
			return nil
		}
		for _, v := range ix.Latest() {
			if mid, _ := v.Manifest["id"].(string); mid == id {
				return plugins.ParseManifest(v.Manifest)
			}
		}
		return nil
	}
}

// DeviceGetPluginRegistry: the index as signed, for the frame to verify itself.
func (s *Server) DeviceGetPluginRegistry(ctx context.Context, _ gen.DeviceGetPluginRegistryRequestObject) (gen.DeviceGetPluginRegistryResponseObject, error) {
	if s.registry == nil {
		return noContent(), nil
	}
	raw, sig, ix := s.registry.Signed()
	if ix == nil {
		return noContent(), nil
	}
	return Response{Status: 200, Body: gen.PluginRegistry{Index: base64.StdEncoding.EncodeToString(raw), Signature: sig, Sequence: int(ix.Sequence)}}, nil
}

// deviceGetPluginBundle serves a bundle the current index lists (hand-written: binary).
func (s *Server) deviceGetPluginBundle(w http.ResponseWriter, r *http.Request) {
	file := r.PathValue("file")
	path := ""
	if s.registry != nil {
		path = s.registry.BundlePath(file)
	}
	if path == "" {
		problem.Write(w, r, problem.New(problem.NotFound, "plugin bundle not found"))
		return
	}
	b, err := os.ReadFile(path)
	if err != nil {
		problem.Write(w, r, problem.New(problem.NotFound, "plugin bundle not found"))
		return
	}
	sum := sha256.Sum256(b)
	etag := `"` + hex.EncodeToString(sum[:]) + `"`
	w.Header().Set("ETag", etag)
	w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
	if r.Header.Get("If-None-Match") == etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Length", strconv.Itoa(len(b)))
	_, _ = w.Write(b)
}
