// Package mailin receives mail for email to frame (docs/spec/email-inbox.md):
// the SMTP receiver, webhook signatures, sender authentication
// (SPF/DKIM/DMARC), the MIME walk, captions and reply texts. It knows nothing
// about the database; the API package supplies recipients and the queue.
package mailin

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"net/mail"
	"strings"
	"time"

	"blitiri.com.ar/go/spf"
	"github.com/emersion/go-msgauth/authres"
	"github.com/emersion/go-msgauth/dkim"
	"github.com/emersion/go-msgauth/dmarc"
	"golang.org/x/net/idna"
	"golang.org/x/net/publicsuffix"
)

// Resolver is the DNS the checks use (compatible with *net.Resolver).
type Resolver interface {
	LookupTXT(ctx context.Context, name string) ([]string, error)
	LookupMX(ctx context.Context, name string) ([]*net.MX, error)
	LookupIPAddr(ctx context.Context, host string) ([]net.IPAddr, error)
	LookupAddr(ctx context.Context, addr string) ([]string, error)
}

// NewResolver returns the system resolver, or one that asks server
// ("host:port") when set (KINFRAME_EMAIL_DNS_SERVER).
func NewResolver(server string) Resolver {
	if server == "" {
		return net.DefaultResolver
	}
	return &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, network, server)
	}}
}

// AuthResult is §7's verdict, stored with queued and held mail (EmailAuthResult).
type AuthResult struct {
	Authenticated bool    `json:"authenticated"`
	From          string  `json:"-"` // the single From address, lower case (also when unauthenticated)
	FromName      string  `json:"-"`
	FromDomain    *string `json:"from_domain"`
	FromInvalid   bool    `json:"from_invalid,omitempty"`
	SPF           string  `json:"spf"`
	DKIM          string  `json:"dkim"`
	DKIMDomain    *string `json:"dkim_domain"`
	DMARC         string  `json:"dmarc"`
	DMARCPolicy   *string `json:"dmarc_policy"`
	Aligned       *string `json:"aligned"`
	// TempError: a DNS temporary error decided the outcome (SMTP: 451; webhook: retried).
	TempError bool `json:"temp_error,omitempty"`
	// RejectDMARC: the mail fails DMARC and the From domain's policy is reject.
	RejectDMARC bool `json:"reject_dmarc,omitempty"`
}

// AuthInput is what the checks need besides the message.
type AuthInput struct {
	ClientIP net.IP // nil: SPF not checked
	Helo     string
	MailFrom string // envelope sender; "" for the null sender
}

// Limits of §7.2.
const (
	maxDKIMSignatures = 5
	DNSBudget         = 20 * time.Second
)

// PrivateIP reports addresses that can't be a sending server's (Docker
// bridges, private networks, loopback): SPF is skipped for them.
func PrivateIP(ip net.IP) bool {
	return ip == nil || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsUnspecified()
}

// ParseFrom returns the single From address of a header block, or ok=false
// when there is not exactly one From header with exactly one address.
func ParseFrom(h mail.Header) (addr, name string, ok bool) {
	vals := h["From"]
	if len(vals) != 1 {
		return "", "", false
	}
	dec := mail.AddressParser{WordDecoder: wordDecoder}
	list, err := dec.ParseList(vals[0])
	if err != nil || len(list) != 1 {
		return "", "", false
	}
	a := list[0].Address
	at := strings.LastIndexByte(a, '@')
	if at <= 0 || at == len(a)-1 {
		return "", "", false
	}
	domain, err := idna.Lookup.ToASCII(strings.TrimSuffix(a[at+1:], "."))
	if err != nil || domain == "" {
		return "", "", false
	}
	return strings.ToLower(a[:at] + "@" + domain), list[0].Name, true
}

// Domain returns the domain part of an address, lower case.
func Domain(addr string) string {
	if at := strings.LastIndexByte(addr, '@'); at >= 0 {
		return strings.ToLower(strings.TrimSuffix(addr[at+1:], "."))
	}
	return strings.ToLower(strings.TrimSuffix(addr, "."))
}

// OrgDomain is the organizational domain (Public Suffix List).
func OrgDomain(d string) string {
	d = strings.ToLower(strings.TrimSuffix(d, "."))
	if o, err := publicsuffix.EffectiveTLDPlusOne(d); err == nil {
		return o
	}
	return d
}

func aligned(a, b string, strict bool) bool {
	a, b = strings.ToLower(a), strings.ToLower(b)
	if strict {
		return a == b
	}
	return a != "" && OrgDomain(a) == OrgDomain(b)
}

// Authenticate runs §7.2 on a raw message: SPF for the client IP and MAIL
// FROM (or HELO), every DKIM signature (at most 5), and DMARC alignment
// against the From domain. Existing Authentication-Results headers are
// ignored. raw is read twice (headers, then DKIM), so it must be seekable.
func Authenticate(ctx context.Context, r Resolver, in AuthInput, raw io.ReadSeeker) AuthResult {
	ctx, cancel := context.WithTimeout(ctx, DNSBudget)
	defer cancel()
	res := AuthResult{SPF: "not_checked", DKIM: "none", DMARC: "none"}
	hdr, err := readHeader(raw)
	if err != nil {
		res.FromInvalid = true
		res.DMARC = "fail"
		return res
	}
	from, name, ok := ParseFrom(hdr)
	if !ok {
		res.FromInvalid = true
		res.DMARC = "fail"
		return res
	}
	res.From, res.FromName = from, name
	fromDomain := Domain(from)
	res.FromDomain = &fromDomain

	// SPF (RFC 7208).
	spfDomain := Domain(in.MailFrom)
	if in.MailFrom == "" {
		spfDomain = strings.ToLower(in.Helo)
	}
	spfTemp := false
	if in.ClientIP != nil && !PrivateIP(in.ClientIP) && spfDomain != "" {
		sender := in.MailFrom
		if sender == "" {
			sender = "postmaster@" + in.Helo
		}
		result, _ := spf.CheckHostWithSender(in.ClientIP, in.Helo, sender, spf.WithContext(ctx), spf.WithResolver(r))
		res.SPF = string(result)
		spfTemp = result == spf.TempError
	}

	// DKIM (RFC 6376). go-msgauth refuses l= signatures, stricter than "count only when the whole body is covered".
	dkimTemp := false
	var passing []string
	if _, err := raw.Seek(0, io.SeekStart); err == nil {
		verifs, err := dkim.VerifyWithOptions(raw, &dkim.VerifyOptions{
			MaxVerifications: maxDKIMSignatures,
			LookupTXT:        func(d string) ([]string, error) { return r.LookupTXT(ctx, d) },
		})
		if err == nil {
			now := time.Now()
			for _, v := range verifs {
				switch {
				case v.Err == nil && (v.Expiration.IsZero() || v.Expiration.After(now)):
					passing = append(passing, v.Domain)
				case v.Err != nil && dkim.IsTempFail(v.Err):
					dkimTemp = true
				}
			}
			switch {
			case len(passing) > 0:
				res.DKIM = "pass"
				d := passing[0]
				res.DKIMDomain = &d
			case dkimTemp:
				res.DKIM = "temperror"
			case len(verifs) > 0:
				res.DKIM = "fail"
			}
		} else if errors.Is(err, dkim.ErrTooManySignatures) {
			res.DKIM = "permerror"
		}
	}

	// DMARC (RFC 7489).
	lookup := &dmarc.LookupOptions{LookupTXT: func(d string) ([]string, error) { return r.LookupTXT(ctx, d) }}
	rec, derr := dmarc.LookupWithOptions(fromDomain, lookup)
	sub := false
	if errors.Is(derr, dmarc.ErrNoPolicy) {
		if org := OrgDomain(fromDomain); org != fromDomain {
			rec, derr = dmarc.LookupWithOptions(org, lookup)
			sub = true
		}
	}
	dmarcTemp := derr != nil && dmarc.IsTempFail(derr)
	strictSPF, strictDKIM := false, false
	if derr == nil && rec != nil {
		strictSPF = rec.SPFAlignment == dmarc.AlignmentStrict
		strictDKIM = rec.DKIMAlignment == dmarc.AlignmentStrict
		p := string(rec.Policy)
		if sub && rec.SubdomainPolicy != "" {
			p = string(rec.SubdomainPolicy)
		}
		res.DMARCPolicy = &p
	}
	if res.SPF == "pass" && aligned(spfDomain, fromDomain, strictSPF) && in.MailFrom != "" {
		a := "spf"
		res.Aligned = &a
	}
	if res.Aligned == nil {
		for _, d := range passing {
			if aligned(d, fromDomain, strictDKIM) {
				a := "dkim"
				res.Aligned, res.DKIMDomain = &a, &d
				break
			}
		}
	}
	res.Authenticated = res.Aligned != nil
	switch {
	case res.Authenticated:
		res.DMARC = "pass"
	case dmarcTemp || ((spfTemp || dkimTemp) && derr == nil):
		// A temporary error could have changed the outcome.
		res.DMARC = "temperror"
		res.TempError = true
	case derr == nil:
		res.DMARC = "fail"
		res.RejectDMARC = res.DMARCPolicy != nil && *res.DMARCPolicy == string(dmarc.PolicyReject)
	default:
		res.DMARC = "none"
		res.TempError = spfTemp || dkimTemp
		if res.TempError {
			res.DMARC = "temperror"
		}
	}
	return res
}

// readHeader reads the header block of a raw message.
func readHeader(r io.ReadSeeker) (mail.Header, error) {
	if _, err := r.Seek(0, io.SeekStart); err != nil {
		return nil, err
	}
	var buf bytes.Buffer
	if _, err := io.Copy(&buf, io.LimitReader(r, MaxHeaderBytes+1)); err != nil {
		return nil, err
	}
	b := buf.Bytes()
	end := headerEnd(b)
	if end < 0 {
		if len(b) > MaxHeaderBytes {
			return nil, ErrHeaderTooLarge
		}
		end = len(b)
	}
	m, err := mail.ReadMessage(bytes.NewReader(append(append([]byte{}, b[:end]...), '\r', '\n', '\r', '\n')))
	if err != nil {
		return nil, err
	}
	return m.Header, nil
}

// headerEnd returns the offset of the blank line ending the header, or -1.
func headerEnd(b []byte) int {
	if i := bytes.Index(b, []byte("\r\n\r\n")); i >= 0 {
		if j := bytes.Index(b, []byte("\n\n")); j >= 0 && j < i {
			return j
		}
		return i
	}
	return bytes.Index(b, []byte("\n\n"))
}

// MaxHeaderBytes is the cap on a message's top-level header block (§6.2).
const MaxHeaderBytes = 256 << 10

// ErrHeaderTooLarge: the header block is over MaxHeaderBytes.
var ErrHeaderTooLarge = errors.New("message header too large")

// TrustedResults reads the provider's own verdict (webhook trust mode,
// §6.3): the topmost Authentication-Results header whose authserv-id is
// exactly authservID, with dmarc=pass for the From domain. Every other such
// header is ignored (anyone can write one).
func TrustedResults(raw io.ReadSeeker, authservID string) AuthResult {
	res := AuthResult{SPF: "not_checked", DKIM: "none", DMARC: "none"}
	hdr, err := readHeader(raw)
	if err != nil {
		res.FromInvalid = true
		return res
	}
	from, name, ok := ParseFrom(hdr)
	if !ok {
		res.FromInvalid = true
		res.DMARC = "fail"
		return res
	}
	res.From, res.FromName = from, name
	fromDomain := Domain(from)
	res.FromDomain = &fromDomain
	for _, v := range hdr["Authentication-Results"] { // topmost first
		id, results, err := authres.Parse(v)
		if err != nil || !strings.EqualFold(strings.TrimSpace(id), authservID) {
			continue
		}
		for _, r := range results {
			switch r := r.(type) {
			case *authres.DMARCResult:
				res.DMARC = string(r.Value)
				if r.Value == authres.ResultPass && strings.EqualFold(Domain(r.From), fromDomain) {
					res.Authenticated = true
				}
			case *authres.SPFResult:
				res.SPF = string(r.Value)
			case *authres.DKIMResult:
				if r.Value == authres.ResultPass && res.DKIM != "pass" {
					res.DKIM = "pass"
					d := r.Domain
					res.DKIMDomain = &d
				} else if res.DKIM == "none" {
					res.DKIM = string(r.Value)
				}
			}
		}
		break
	}
	switch res.DMARC {
	case "pass", "fail", "none", "temperror":
	default:
		res.DMARC = "fail"
	}
	switch res.SPF {
	case "pass", "fail", "softfail", "neutral", "none", "temperror", "permerror", "not_checked":
	default:
		res.SPF = "none"
	}
	switch res.DKIM {
	case "pass", "fail", "none", "temperror", "permerror":
	default:
		res.DKIM = "fail"
	}
	if !res.Authenticated && res.DMARC == "pass" {
		res.DMARC = "fail"
	}
	if res.Authenticated {
		a := "dkim"
		if res.DKIM != "pass" {
			a = "spf"
		}
		res.Aligned = &a
	}
	return res
}
