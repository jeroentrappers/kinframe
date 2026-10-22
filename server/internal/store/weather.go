package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// FeedWeather is the feed kind of a frame's `weather` change (entity = frame id).
const FeedWeather = "weather"

// WeatherFrame is a frame that wants weather, or that was sent weather before.
type WeatherFrame struct {
	FrameID   uuid.UUID       `db:"id"`
	Settings  json.RawMessage `db:"settings"`
	CacheKey  *string         `db:"cache_key"`
	Digest    *string         `db:"digest"`
	EmittedAt *time.Time      `db:"emitted_at"`
}

// WeatherFrames lists live frames whose settings ask for weather (location set
// and widgets.weather on) plus frames that still hold weather. only limits the
// list to one frame.
func (s *Store) WeatherFrames(ctx context.Context, only *uuid.UUID) ([]WeatherFrame, error) {
	return many[WeatherFrame](s.Pool.Query(ctx, `
		SELECT f.id, f.settings, fw.cache_key, fw.digest, fw.emitted_at
		FROM frames f LEFT JOIN frame_weather fw ON fw.frame_id = f.id
		WHERE f.deleted_at IS NULL AND ($1::uuid IS NULL OR f.id = $1)
		  AND (fw.frame_id IS NOT NULL OR (
		       jsonb_typeof(f.settings->'location') = 'object' AND f.settings->'widgets'->'weather' = 'true'::jsonb))
		ORDER BY f.id`, only))
}

// WeatherCache is one cached Open-Meteo result.
type WeatherCache struct {
	Key         string          `db:"key"`
	Lat         float64         `db:"lat"`
	Lon         float64         `db:"lon"`
	Payload     json.RawMessage `db:"payload"`
	FetchedAt   *time.Time      `db:"fetched_at"`
	NextFetchAt time.Time       `db:"next_fetch_at"`
	Failures    int             `db:"failures"`
	LastError   *string         `db:"last_error"`
}

const weatherCacheCols = `key, lat, lon, payload, fetched_at, next_fetch_at, failures, last_error`

// GetWeatherCache returns the cache row for key, or ErrNotFound.
func (s *Store) GetWeatherCache(ctx context.Context, key string) (*WeatherCache, error) {
	return one[WeatherCache](s.Pool.Query(ctx, `SELECT `+weatherCacheCols+` FROM weather_cache WHERE key = $1`, key))
}

// PutWeather stores a successful fetch.
func (s *Store) PutWeather(ctx context.Context, key string, lat, lon float64, payload json.RawMessage, fetched, next time.Time) error {
	_, err := s.Pool.Exec(ctx, `
		INSERT INTO weather_cache (key, lat, lon, payload, fetched_at, next_fetch_at, failures, last_error)
		VALUES ($1, $2, $3, $4, $5, $6, 0, NULL)
		ON CONFLICT (key) DO UPDATE SET lat = EXCLUDED.lat, lon = EXCLUDED.lon, payload = EXCLUDED.payload,
			fetched_at = EXCLUDED.fetched_at, next_fetch_at = EXCLUDED.next_fetch_at, failures = 0, last_error = NULL`,
		key, lat, lon, payload, fetched, next)
	return err
}

// WeatherFailed records a failed fetch; the previous payload is kept.
func (s *Store) WeatherFailed(ctx context.Context, key string, lat, lon float64, failures int, next time.Time, cause string) error {
	_, err := s.Pool.Exec(ctx, `
		INSERT INTO weather_cache (key, lat, lon, next_fetch_at, failures, last_error) VALUES ($1, $2, $3, $4, $5, $6)
		ON CONFLICT (key) DO UPDATE SET next_fetch_at = EXCLUDED.next_fetch_at, failures = EXCLUDED.failures,
			last_error = EXCLUDED.last_error`, key, lat, lon, next, failures, cause)
	return err
}

// PruneWeatherCache deletes entries no frame uses that have not been due for a day.
func (s *Store) PruneWeatherCache(ctx context.Context) error {
	_, err := s.Pool.Exec(ctx, `
		DELETE FROM weather_cache wc WHERE wc.next_fetch_at < now() - interval '1 day'
		AND NOT EXISTS (SELECT 1 FROM frame_weather fw WHERE fw.cache_key = wc.key)`)
	return err
}

// SetFrameWeather records what a frame was sent and appends a `weather` change.
func (tx *Tx) SetFrameWeather(ctx context.Context, frameID uuid.UUID, key, digest string) error {
	if _, err := tx.Exec(ctx, `
		INSERT INTO frame_weather (frame_id, cache_key, digest, emitted_at) VALUES ($1, $2, $3, now())
		ON CONFLICT (frame_id) DO UPDATE SET cache_key = EXCLUDED.cache_key, digest = EXCLUDED.digest, emitted_at = now()`,
		frameID, key, digest); err != nil {
		return err
	}
	return tx.FeedPut(ctx, frameID, FeedWeather, frameID, false)
}

// ClearFrameWeather forgets a frame's weather and appends a `weather: null` change.
func (tx *Tx) ClearFrameWeather(ctx context.Context, frameID uuid.UUID) error {
	if _, err := tx.Exec(ctx, `DELETE FROM frame_weather WHERE frame_id = $1`, frameID); err != nil {
		return err
	}
	return tx.FeedPut(ctx, frameID, FeedWeather, frameID, false)
}

// FrameWeatherPayload returns the cached payload a frame was last sent, or nil.
func (s *Store) FrameWeatherPayload(ctx context.Context, q db.DBTX, frameID uuid.UUID) (json.RawMessage, error) {
	var raw json.RawMessage
	err := q.QueryRow(ctx, `
		SELECT wc.payload FROM frame_weather fw JOIN weather_cache wc ON wc.key = fw.cache_key
		WHERE fw.frame_id = $1 AND wc.payload IS NOT NULL`, frameID).Scan(&raw)
	if err == pgx.ErrNoRows {
		return nil, nil
	}
	return raw, err
}
