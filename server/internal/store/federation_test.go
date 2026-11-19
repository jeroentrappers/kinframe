package store_test

import (
	"context"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/store"
	"github.com/jeroentrappers/kinframe/server/internal/testdb"
)

// The server id is made once; a rotated signing key stays valid for 7 days, then goes.
func TestFederationIdentityAndKeyRotation(t *testing.T) {
	ctx := context.Background()
	pool, _ := testdb.New(t)
	st := store.New(pool)
	id, err := st.FederationServerID(ctx)
	if err != nil || id == "" {
		t.Fatalf("server id: %q %v", id, err)
	}
	if again, _ := st.FederationServerID(ctx); again != id {
		t.Fatalf("server id changed: %q -> %q", id, again)
	}
	week := 7 * 24 * time.Hour
	if err := st.AddFederationKey(ctx, store.FederationKey{KeyID: "k1", PublicKey: make([]byte, 32), PrivateKeyEnc: make([]byte, 32)}, week); err != nil {
		t.Fatal(err)
	}
	if err := st.AddFederationKey(ctx, store.FederationKey{KeyID: "k2", PublicKey: make([]byte, 32), PrivateKeyEnc: make([]byte, 32)}, week); err != nil {
		t.Fatal(err)
	}
	keys, err := st.FederationKeys(ctx)
	if err != nil || len(keys) != 2 || keys[0].KeyID != "k2" || !keys[0].IsCurrent || keys[1].KeyID != "k1" || keys[1].IsCurrent ||
		keys[1].ExpiresAt == nil || time.Until(*keys[1].ExpiresAt) < week-time.Minute || time.Until(*keys[1].ExpiresAt) > week+time.Minute {
		t.Fatalf("keys after rotation: %+v %v", keys, err)
	}
	// Past its 7 days the rotated key is no longer valid, and housekeeping deletes it.
	if _, err := pool.Exec(ctx, `UPDATE federation_keys SET expires_at = now() - interval '1 second' WHERE key_id = 'k1'`); err != nil {
		t.Fatal(err)
	}
	if keys, _ = st.FederationKeys(ctx); len(keys) != 1 || keys[0].KeyID != "k2" {
		t.Fatalf("expired key still valid: %+v", keys)
	}
	if err := st.DeleteExpiredFederationKeys(ctx); err != nil {
		t.Fatal(err)
	}
	var n int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM federation_keys`).Scan(&n); err != nil || n != 1 {
		t.Fatalf("keys left: %d %v", n, err)
	}
}
