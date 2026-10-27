package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

// WeatherStation is a station a frame's weather-station plugin registered (docs/plugins.md §9.4).
type WeatherStation struct {
	FrameID    uuid.UUID `db:"frame_id"`
	InstanceID string    `db:"instance_id"`
	UploadID   string    `db:"upload_id"`
	KeyMAC     []byte    `db:"key_mac"`
	Forward    bool      `db:"forward"`
}

// SetFrameStations replaces the stations a frame registered.
func (tx *Tx) SetFrameStations(ctx context.Context, frameID uuid.UUID, stations []WeatherStation) error {
	if _, err := tx.Exec(ctx, `DELETE FROM weather_stations WHERE frame_id = $1`, frameID); err != nil {
		return err
	}
	for _, s := range stations {
		if _, err := tx.Exec(ctx, `INSERT INTO weather_stations (frame_id, instance_id, upload_id, key_mac, forward) VALUES ($1, $2, $3, $4, $5)`,
			frameID, s.InstanceID, s.UploadID, s.KeyMAC, s.Forward); err != nil {
			return err
		}
	}
	return nil
}

// StationsByMAC returns the registrations of a station (one per frame instance that names it).
func (s *Store) StationsByMAC(ctx context.Context, q db.DBTX, mac []byte) ([]WeatherStation, error) {
	return many[WeatherStation](q.Query(ctx, `SELECT frame_id, instance_id, upload_id, key_mac, forward FROM weather_stations WHERE key_mac = $1`, mac))
}

// StationReading is one upload, normalised.
type StationReading struct {
	At      time.Time       `db:"at"`
	Reading json.RawMessage `db:"reading"`
}

// AddStationReading stores a reading and drops those older than 48 hours.
func (s *Store) AddStationReading(ctx context.Context, mac []byte, at time.Time, reading json.RawMessage) error {
	if _, err := s.Pool.Exec(ctx, `INSERT INTO station_readings (key_mac, at, reading) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING`, mac, at, reading); err != nil {
		return err
	}
	_, err := s.Pool.Exec(ctx, `DELETE FROM station_readings WHERE key_mac = $1 AND at < $2`, mac, at.Add(-48*time.Hour))
	return err
}

// StationReadings returns a station's readings since a time, oldest first.
func (s *Store) StationReadings(ctx context.Context, mac []byte, since time.Time) ([]StationReading, error) {
	return many[StationReading](s.Pool.Query(ctx, `SELECT at, reading FROM station_readings WHERE key_mac = $1 AND at >= $2 ORDER BY at`, mac, since))
}
