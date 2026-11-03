package calendar

import "github.com/jeroentrappers/kinframe/server/internal/secrets"

// Sealer encrypts calendar URLs and credentials at rest with AES-256-GCM.
type Sealer = secrets.Sealer

// ErrUnseal means stored secrets can't be decrypted (the key changed).
var ErrUnseal = secrets.ErrUnseal

// NewSealer uses secretsKey (KINFRAME_SECRETS_KEY) when set, else a key
// derived from the signing key with HKDF-SHA256.
func NewSealer(secretsKey, signingKey []byte) (*Sealer, error) {
	return secrets.New(secretsKey, signingKey, "kinframe secrets v1", "kinframe calendar secrets from signing key v1")
}
