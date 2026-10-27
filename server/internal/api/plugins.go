package api

import (
	"context"
	"encoding/json"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/plugins"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
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
	var catalog plugins.Catalog
	if p, err := s.st.GetFramePlugins(ctx, tx, f.ID); err == nil && len(p.Catalog) > 0 {
		catalog, _ = plugins.ParseCatalog(p.Catalog)
	}
	problems := plugins.Check(instances, catalog)
	if len(problems) == 0 {
		return nil
	}
	pr := problem.New(problem.ValidationFailed, "invalid settings")
	for _, p := range problems {
		pr.WithField(p.Path, p.Reason)
	}
	return pr
}
