package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// FramePlugins is what a frame reported about its plugins (docs/plugins.md §10).
type FramePlugins struct {
	Catalog   json.RawMessage `db:"plugin_catalog"`
	CatalogAt *time.Time      `db:"plugin_catalog_at"`
	Status    json.RawMessage `db:"plugin_status"`
	StatusAt  *time.Time      `db:"plugin_status_at"`
}

// GetFramePlugins returns the frame's plugin catalog and status (nil fields when it never reported them).
func (s *Store) GetFramePlugins(ctx context.Context, q db.DBTX, frameID uuid.UUID) (*FramePlugins, error) {
	return one[FramePlugins](q.Query(ctx, `
		SELECT plugin_catalog, plugin_catalog_at, plugin_status, plugin_status_at FROM frames WHERE id = $1 AND deleted_at IS NULL`, frameID))
}

// SetPluginCatalog replaces the frame's catalog.
func (s *Store) SetPluginCatalog(ctx context.Context, frameID uuid.UUID, catalog json.RawMessage) error {
	_, err := s.Pool.Exec(ctx, `UPDATE frames SET plugin_catalog = $2, plugin_catalog_at = now() WHERE id = $1`, frameID, catalog)
	return err
}

// SetPluginStatus replaces the frame's plugin status report.
func (s *Store) SetPluginStatus(ctx context.Context, frameID uuid.UUID, status json.RawMessage) error {
	_, err := s.Pool.Exec(ctx, `UPDATE frames SET plugin_status = $2, plugin_status_at = now() WHERE id = $1`, frameID, status)
	return err
}
