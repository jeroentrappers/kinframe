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
	Public     bool      `db:"public"`
	PublicName string    `db:"public_name"`
}

// SetFrameStations replaces the stations a frame registered.
func (tx *Tx) SetFrameStations(ctx context.Context, frameID uuid.UUID, stations []WeatherStation) error {
	if _, err := tx.Exec(ctx, `DELETE FROM weather_stations WHERE frame_id = $1`, frameID); err != nil {
		return err
	}
	for _, s := range stations {
		if _, err := tx.Exec(ctx, `INSERT INTO weather_stations (frame_id, instance_id, upload_id, key_mac, forward, public, public_name) VALUES ($1, $2, $3, $4, $5, $6, $7)`,
			frameID, s.InstanceID, s.UploadID, s.KeyMAC, s.Forward, s.Public, s.PublicName); err != nil {
			return err
		}
	}
	return nil
}

// StationsByMAC returns the registrations of a station (one per frame instance that names it).
func (s *Store) StationsByMAC(ctx context.Context, q db.DBTX, mac []byte) ([]WeatherStation, error) {
	return many[WeatherStation](q.Query(ctx, `SELECT frame_id, instance_id, upload_id, key_mac, forward, public, public_name FROM weather_stations WHERE key_mac = $1`, mac))
}

// PublicStations returns the registrations of a station ID whose owners turned its public page on.
func (s *Store) PublicStations(ctx context.Context, q db.DBTX, uploadID string) ([]WeatherStation, error) {
	return many[WeatherStation](q.Query(ctx, `SELECT frame_id, instance_id, upload_id, key_mac, forward, public, public_name FROM weather_stations
		WHERE upper(upload_id) = upper($1) AND public ORDER BY frame_id, instance_id`, uploadID))
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

// PublicStationLatest is a registration with a public page and its station's newest reading.
type PublicStationLatest struct {
	UploadID   string          `db:"upload_id"`
	PublicName string          `db:"public_name"`
	KeyMAC     []byte          `db:"key_mac"`
	At         time.Time       `db:"at"`
	Reading    json.RawMessage `db:"reading"`
}

// PublicStationsLatest returns every registration with a public page whose station sent something since `since`,
// with its newest reading (a station named by several frames appears once per registration).
func (s *Store) PublicStationsLatest(ctx context.Context, since time.Time) ([]PublicStationLatest, error) {
	return many[PublicStationLatest](s.Pool.Query(ctx, `SELECT ws.upload_id, ws.public_name, ws.key_mac, r.at, r.reading
		FROM weather_stations ws
		JOIN LATERAL (SELECT at, reading FROM station_readings sr WHERE sr.key_mac = ws.key_mac ORDER BY at DESC LIMIT 1) r ON true
		WHERE ws.public AND r.at >= $1`, since))
}

// HourlyTemperature is a station's mean temperature per hour since a time.
type HourlyTemperature struct {
	Hour  time.Time `db:"hour"`
	TempC *float64  `db:"temp_c"`
}

// StationHourlyTemperature returns the mean temperature of each hour since `since` that had readings, oldest first.
func (s *Store) StationHourlyTemperature(ctx context.Context, mac []byte, since time.Time) ([]HourlyTemperature, error) {
	return many[HourlyTemperature](s.Pool.Query(ctx, `SELECT date_trunc('hour', at) AS hour, avg((reading->>'temp_c')::float8) AS temp_c
		FROM station_readings WHERE key_mac = $1 AND at >= $2 AND reading ? 'temp_c'
		GROUP BY 1 ORDER BY 1`, mac, since))
}
