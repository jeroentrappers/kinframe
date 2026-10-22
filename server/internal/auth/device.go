package auth

import (
	"errors"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

const (
	DeviceAudience = "device"
	DeviceTokenTTL = time.Hour
)

var (
	ErrExpired = errors.New("token expired")
	ErrInvalid = errors.New("token invalid")
)

// DeviceTokens issues and validates frame access tokens (HS256).
type DeviceTokens struct {
	key    []byte
	issuer string
	now    func() time.Time
}

func NewDeviceTokens(master []byte, issuer string) *DeviceTokens {
	return &DeviceTokens{key: DeriveKey(master, "device-token"), issuer: issuer, now: time.Now}
}

// Issue returns a signed token for the device and its lifetime in seconds.
func (d *DeviceTokens) Issue(deviceID uuid.UUID) (string, int, error) {
	now := d.now()
	claims := jwt.RegisteredClaims{
		Issuer:    d.issuer,
		Subject:   deviceID.String(),
		Audience:  jwt.ClaimStrings{DeviceAudience},
		IssuedAt:  jwt.NewNumericDate(now),
		NotBefore: jwt.NewNumericDate(now),
		ExpiresAt: jwt.NewNumericDate(now.Add(DeviceTokenTTL)),
		ID:        uuid.NewString(),
	}
	s, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(d.key)
	return s, int(DeviceTokenTTL.Seconds()), err
}

// Verify returns the device ID of a valid token.
func (d *DeviceTokens) Verify(token string) (uuid.UUID, error) {
	var claims jwt.RegisteredClaims
	_, err := jwt.ParseWithClaims(token, &claims, func(*jwt.Token) (any, error) { return d.key, nil },
		jwt.WithValidMethods([]string{"HS256"}),
		jwt.WithIssuer(d.issuer),
		jwt.WithAudience(DeviceAudience),
		jwt.WithExpirationRequired(),
		jwt.WithLeeway(60*time.Second),
		jwt.WithTimeFunc(d.now),
	)
	if err != nil {
		if errors.Is(err, jwt.ErrTokenExpired) {
			return uuid.Nil, ErrExpired
		}
		return uuid.Nil, ErrInvalid
	}
	id, err := uuid.Parse(claims.Subject)
	if err != nil {
		return uuid.Nil, ErrInvalid
	}
	return id, nil
}
