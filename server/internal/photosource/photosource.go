// Package photosource talks to outside photo services for photo sources and
// Google Photos imports (docs/spec/photo-sources.md): Immich shared links and
// API keys, iCloud shared albums (best effort) and the Google Photos Picker
// API. It lists albums and downloads files; what becomes of them (items,
// sends, posts) is decided by the API server.
//
// Every request goes through a netguard client: user-entered hosts are
// checked against the SSRF rules, fixed providers are pinned to their domains.
package photosource

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/netguard"
)

// Providers.
const (
	ImmichLink = "immich_link"
	ImmichAPI  = "immich_api"
	ICloudLink = "icloud_link"
)

// Error codes (PhotoSourceErrorCode, §13).
const (
	CodeUnreachable           = "unreachable"
	CodeTLS                   = "tls_error"
	CodePrivateBlocked        = "private_address_blocked"
	CodeAuthFailed            = "auth_failed"
	CodePasswordRequired      = "password_required"
	CodePermissionMissing     = "permission_missing"
	CodeLinkNotFound          = "link_not_found"
	CodeLinkExpired           = "link_expired"
	CodeAlbumNotFound         = "album_not_found"
	CodeUnsupportedLink       = "unsupported_link"
	CodeTooLarge              = "too_large"
	CodeFormatChanged         = "format_changed"
	CodeRateLimited           = "rate_limited_upstream"
	CodeUpstreamError         = "upstream_error"
	CodeOwnerLostAccess       = "owner_lost_access"
	CodeCredentialsUnreadable = "credentials_unreadable"
	CodeMassRemoval           = "mass_removal"
)

// Warnings (PhotoSourceWarning).
const (
	WarnDownloadsDisabled = "downloads_disabled"
	WarnMetadataHidden    = "metadata_hidden"
	WarnItemLimit         = "item_limit_reached"
)

// Error is a failed request to a provider, with its PhotoSourceErrorCode and a
// short English detail for support (never a link, key or token).
type Error struct {
	Code   string
	Detail string
	Status int // upstream HTTP status, when there was one
}

func (e *Error) Error() string { return e.Code + ": " + e.Detail }

func errf(code, format string, a ...any) *Error {
	return &Error{Code: code, Detail: fmt.Sprintf(format, a...)}
}

// AsError returns err as an *Error, classifying network failures.
func AsError(err error) *Error {
	if err == nil {
		return nil
	}
	var pe *Error
	if errors.As(err, &pe) {
		return pe
	}
	if b, ok := netguard.Blocked(err); ok {
		switch b.Reason {
		case netguard.ReasonPinned:
			return errf(CodeFormatChanged, "the service pointed to an unexpected host (%s)", b.Host)
		case netguard.ReasonRedirect:
			return errf(CodeUpstreamError, "too many redirects")
		}
		return &Error{Code: CodePrivateBlocked, Detail: b.Error()}
	}
	var ue x509.UnknownAuthorityError
	var he x509.HostnameError
	var ce x509.CertificateInvalidError
	var tv *tls.CertificateVerificationError
	if errors.As(err, &ue) || errors.As(err, &he) || errors.As(err, &ce) || errors.As(err, &tv) || strings.Contains(err.Error(), "tls: ") {
		return errf(CodeTLS, "the certificate is not trusted")
	}
	var dnsErr *net.DNSError
	if errors.As(err, &dnsErr) {
		return errf(CodeUnreachable, "server not found (%s)", dnsErr.Name)
	}
	if errors.Is(err, context.DeadlineExceeded) || strings.Contains(err.Error(), "Client.Timeout") {
		return errf(CodeUnreachable, "the server did not answer in time")
	}
	return errf(CodeUnreachable, "could not connect")
}

// Transient codes put a source in `error` (retried with backoff); the others
// need a person (`needs_attention`).
func Transient(code string) bool {
	switch code {
	case CodeUnreachable, CodeRateLimited, CodeUpstreamError:
		return true
	}
	return false
}

// Probed codes may fix themselves upstream: a source in needs_attention with
// one of them is retried once a day (§5.1). iCloud also probes format_changed.
func Probed(code, provider string) bool {
	switch code {
	case CodeLinkNotFound, CodeAlbumNotFound, CodeAuthFailed, CodePasswordRequired:
		return true
	case CodeFormatChanged:
		return provider == ICloudLink
	}
	return false
}

// statusError maps an unexpected upstream HTTP status.
func statusError(code int) *Error {
	switch {
	case code == http.StatusTooManyRequests || code == http.StatusServiceUnavailable:
		return &Error{Code: CodeRateLimited, Detail: fmt.Sprintf("the service answered %d", code), Status: code}
	case code >= 500:
		return &Error{Code: CodeUpstreamError, Detail: fmt.Sprintf("the server had a problem (error %d)", code), Status: code}
	}
	return &Error{Code: CodeFormatChanged, Detail: fmt.Sprintf("unexpected answer %d", code), Status: code}
}

// ---------------------------------------------------------------- model

// Conn is how to reach an outside album (decrypted credentials).
type Conn struct {
	Provider string
	URL      string // the shared link (links), or the Immich server URL (immich_api)
	Password string // Immich link password
	APIKey   string // immich_api
	AlbumID  string // immich_api: the album; links: learned from the link
	// ResolvedHost is the iCloud partition host learned from a redirect.
	ResolvedHost string
}

// Asset is one photo or video in the outside album.
type Asset struct {
	ID       string
	Kind     string     // photo, video, live
	TakenAt  *time.Time // capture instant (§8.5)
	AddedAt  *time.Time // when it was added upstream (iCloud batchDateCreated)
	Caption  string
	Checksum string // content fingerprint as the provider tells it
	Updated  string // provider's change stamp (Immich updatedAt)
	Size     int64  // bytes, when known
	// DurationMS of a video (0 unknown).
	DurationMS int
	// MotionID is the motion clip of a live photo (Immich livePhotoVideoId).
	MotionID string
	Mime     string
	// Offline assets are listed but can't be read right now (Immich isOffline):
	// skipped this run, not gone.
	Offline bool
	// Width/Height when known.
	Width, Height int

	// provider-specific download info
	derivative string // iCloud: chosen derivative checksum
}

// Listing is a complete read of the outside album's metadata.
type Listing struct {
	Name           string
	AlbumID        string // the upstream album id (Immich)
	Hint           string // cheap change hint (updatedAt+assetCount, streamCtag)
	Assets         []Asset
	Warnings       []string
	ServerVersion  string
	ResolvedHost   string // iCloud: the partition host to remember
	DownloadsAllow bool   // Immich links: originals may be downloaded

	sess session
}

// Counts of photos, videos and the size the provider tells.
func (l *Listing) Counts() (photos, videos int, bytes int64) {
	for _, a := range l.Assets {
		if a.Kind == "video" {
			videos++
		} else {
			photos++
		}
		bytes += a.Size
	}
	return
}

// Download is a file fetched into the scratch volume.
type Download struct {
	Path    string
	Bytes   int64
	SHA256  string
	Variant string // original, fullsize, preview, playback, derivative, motion
}

// Provider lists one kind of source.
type Provider interface {
	// List reads the album's metadata completely, or fails: a partial or
	// unparsable answer is an error (format_changed), never "empty". The
	// listing's Download fetches its assets.
	List(ctx context.Context, c Conn) (*Listing, error)
}

// session downloads the assets of one listing (logged-in link, cached URLs).
type session interface {
	prefetch(ctx context.Context, assets []Asset) error
	download(ctx context.Context, a Asset, variant, dst string, max int64) (*Download, error)
}

// Prefetch prepares downloads of these assets in one go where the provider
// wants that (iCloud asks for download URLs 25 at a time).
func (l *Listing) Prefetch(ctx context.Context, assets []Asset) error {
	if l.sess == nil {
		return nil
	}
	return l.sess.prefetch(ctx, assets)
}

// Download fetches an asset to dst. variant "" picks the best file (§6.6);
// a named variant (original, fullsize, preview, playback, motion) only that
// one. Fails with too_large beyond max bytes.
func (l *Listing) Download(ctx context.Context, a Asset, variant, dst string, max int64) (*Download, error) {
	if l.sess == nil {
		return nil, errf(CodeFormatChanged, "nothing to download from")
	}
	return l.sess.download(ctx, a, variant, dst, max)
}

// UserAgent is sent to every provider.
var UserAgent = "kinframe"

// Limits on what is read from providers (§12.3).
const (
	MaxMetadataBytes = 64 << 20
	MetadataTimeout  = 60 * time.Second
	DownloadTimeout  = 10 * time.Minute
)

// errTooBig marks a response body over its cap.
var errTooBig = errors.New("response too large")

func readCapped(r io.Reader, max int64) ([]byte, error) {
	b, err := io.ReadAll(io.LimitReader(r, max+1))
	if err != nil {
		return nil, err
	}
	if int64(len(b)) > max {
		return nil, errTooBig
	}
	return b, nil
}

// saveCapped streams body to dst (sha256 on the way), failing beyond max.
func saveCapped(body io.Reader, dst string, max int64) (*Download, error) {
	f, err := os.Create(dst)
	if err != nil {
		return nil, err
	}
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(f, h), io.LimitReader(body, max+1))
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		_ = os.Remove(dst)
		return nil, AsError(err)
	}
	if n > max {
		_ = os.Remove(dst)
		return nil, errf(CodeTooLarge, "the file is larger than %d MB", max>>20)
	}
	return &Download{Path: dst, Bytes: n, SHA256: hex.EncodeToString(h.Sum(nil))}, nil
}

// ---------------------------------------------------------------- links

// ParsedLink is a recognised source link.
type ParsedLink struct {
	Provider string
	Host     string
	APIBase  string // Immich: https://host[/prefix]/api
	Key      string // Immich share key, or iCloud token
	Slug     string // Immich custom slug (/s/<slug>)
}

// ParseLink recognises an Immich shared link or an iCloud shared album link
// for provider. unsupported_link: a recognised but unsupported kind of link
// (an iCloud Link to a few photos).
func ParseLink(provider, raw string) (*ParsedLink, error) {
	raw = strings.TrimSpace(raw)
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") {
		return nil, errf(CodeUnsupportedLink, "not a web link")
	}
	if u.User != nil {
		return nil, errf(CodeUnsupportedLink, "links must not contain a user name or password")
	}
	switch provider {
	case ImmichLink:
		p := strings.TrimRight(u.Path, "/")
		var prefix, key, slug string
		if i := strings.LastIndex(p, "/share/"); i >= 0 {
			prefix, key = p[:i], p[i+len("/share/"):]
		} else if i := strings.LastIndex(p, "/s/"); i >= 0 {
			prefix, slug = p[:i], p[i+len("/s/"):]
		}
		if (key == "" && slug == "") || strings.Contains(key+slug, "/") {
			return nil, errf(CodeUnsupportedLink, "not an Immich shared link (…/share/<key>)")
		}
		base := u.Scheme + "://" + u.Host + prefix + "/api"
		return &ParsedLink{Provider: provider, Host: strings.ToLower(u.Hostname()), APIBase: base, Key: key, Slug: slug}, nil
	case ICloudLink:
		h := strings.ToLower(u.Hostname())
		if h != "www.icloud.com" && h != "icloud.com" {
			return nil, errf(CodeUnsupportedLink, "not an iCloud link")
		}
		p := strings.Trim(u.Path, "/")
		if p == "photos" || strings.HasPrefix(p, "photos/") {
			return nil, errf(CodeUnsupportedLink, "this is an iCloud Link for a few photos, not a shared album")
		}
		if p != "sharedalbum" && !strings.HasPrefix(p, "sharedalbum/") {
			return nil, errf(CodeUnsupportedLink, "not an iCloud shared album link (…/sharedalbum/#<token>)")
		}
		tok := strings.TrimSpace(u.Fragment)
		if tok == "" || !validToken(tok) {
			return nil, errf(CodeUnsupportedLink, "the link has no album token after #")
		}
		return &ParsedLink{Provider: provider, Host: "icloud.com", Key: tok}, nil
	}
	return nil, errf(CodeUnsupportedLink, "unknown provider")
}

func validToken(t string) bool {
	if len(t) < 2 || len(t) > 64 {
		return false
	}
	for _, r := range t {
		if !(r >= '0' && r <= '9' || r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z') {
			return false
		}
	}
	return true
}

// ImmichServer normalises an Immich server URL ("an /api suffix is
// tolerated") to its API base and display URL.
func ImmichServer(raw string) (apiBase, display, host string, err error) {
	u, perr := url.Parse(strings.TrimSpace(raw))
	if perr != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") {
		return "", "", "", errf(CodeUnreachable, "not a server address")
	}
	if u.User != nil {
		return "", "", "", errf(CodeUnreachable, "the address must not contain a user name or password")
	}
	p := strings.TrimRight(u.Path, "/")
	p = strings.TrimSuffix(p, "/api")
	display = u.Scheme + "://" + u.Host + p
	return display + "/api", display, strings.ToLower(u.Hostname()), nil
}

// URLHint redacts a link to scheme, host and "…" (§12.1).
func URLHint(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return ""
	}
	return u.Scheme + "://" + u.Host + "/…"
}

// UpstreamKey identifies an outside album for the duplicate check:
// sha256(provider|host|album id or link key).
func UpstreamKey(provider, host, id string) string {
	sum := sha256.Sum256([]byte(provider + "|" + strings.ToLower(host) + "|" + id))
	return hex.EncodeToString(sum[:])
}

// ---------------------------------------------------------------- readable formats

// Readable reports whether the media pipeline decodes this MIME type itself.
func Readable(mime string) bool {
	switch strings.ToLower(strings.TrimSpace(strings.Split(mime, ";")[0])) {
	case "image/jpeg", "image/jpg", "image/png", "image/webp", "image/heic", "image/heif", "image/avif", "image/gif",
		"video/mp4", "video/quicktime", "video/webm", "video/x-matroska", "video/3gpp", "video/x-m4v":
		return true
	}
	return false
}
