// Package mailintest has a fake DNS and DKIM-signed message builders for
// email-to-frame tests.
package mailintest

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"mime/multipart"
	"net"
	"net/textproto"
	"strings"
	"sync"
	"time"

	"github.com/emersion/go-msgauth/dkim"
)

// DNS is an in-memory resolver: TXT records by name; everything else is
// NXDOMAIN. Temp lists names that fail temporarily.
type DNS struct {
	mu   sync.Mutex
	TXT  map[string][]string
	A    map[string][]net.IP
	Temp map[string]bool
}

func NewDNS() *DNS {
	return &DNS{TXT: map[string][]string{}, A: map[string][]net.IP{}, Temp: map[string]bool{}}
}

func (d *DNS) Set(name string, txt ...string) {
	d.mu.Lock()
	d.TXT[strings.ToLower(strings.TrimSuffix(name, "."))] = txt
	d.mu.Unlock()
}

func (d *DNS) SetTemp(name string, temp bool) {
	d.mu.Lock()
	d.Temp[strings.ToLower(strings.TrimSuffix(name, "."))] = temp
	d.mu.Unlock()
}

func (d *DNS) lookup(name string) ([]string, bool, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	n := strings.ToLower(strings.TrimSuffix(name, "."))
	if d.Temp[n] {
		return nil, false, &net.DNSError{Err: "timeout", Name: name, IsTimeout: true, IsTemporary: true}
	}
	v, ok := d.TXT[n]
	return v, ok, nil
}

func (d *DNS) LookupTXT(_ context.Context, name string) ([]string, error) {
	v, ok, err := d.lookup(name)
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, &net.DNSError{Err: "no such host", Name: name, IsNotFound: true}
	}
	return v, nil
}

func (d *DNS) LookupMX(_ context.Context, name string) ([]*net.MX, error) {
	return nil, &net.DNSError{Err: "no such host", Name: name, IsNotFound: true}
}

func (d *DNS) LookupIPAddr(_ context.Context, host string) ([]net.IPAddr, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	ips := d.A[strings.ToLower(strings.TrimSuffix(host, "."))]
	if len(ips) == 0 {
		return nil, &net.DNSError{Err: "no such host", Name: host, IsNotFound: true}
	}
	out := make([]net.IPAddr, len(ips))
	for i, ip := range ips {
		out[i] = net.IPAddr{IP: ip}
	}
	return out, nil
}

func (d *DNS) LookupAddr(_ context.Context, addr string) ([]string, error) {
	return nil, &net.DNSError{Err: "no such host", Name: addr, IsNotFound: true}
}

// Domain is a sending domain with a DKIM key published in a DNS.
type Domain struct {
	Name     string
	Selector string
	key      ed25519.PrivateKey
}

// NewDomain publishes a DKIM key (and optionally a DMARC policy) for name.
func NewDomain(d *DNS, name, dmarcPolicy string) *Domain {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		panic(err)
	}
	dom := &Domain{Name: name, Selector: "s1", key: priv}
	d.Set("s1._domainkey."+name, "v=DKIM1; k=ed25519; p="+base64.StdEncoding.EncodeToString(pub))
	if dmarcPolicy != "" {
		d.Set("_dmarc."+name, "v=DMARC1; p="+dmarcPolicy)
	}
	return dom
}

// Sign DKIM-signs a raw message as the domain.
func (dom *Domain) Sign(raw []byte) []byte {
	var out bytes.Buffer
	if err := dkim.Sign(&out, bytes.NewReader(raw), &dkim.SignOptions{Domain: dom.Name, Selector: dom.Selector, Signer: dom.key}); err != nil {
		panic(err)
	}
	return out.Bytes()
}

// Part is one attachment.
type Part struct {
	ContentType string
	Filename    string
	Inline      bool
	Data        []byte
}

// Mail builds a multipart/mixed message with a text body and attachments.
type Mail struct {
	From, To, Subject, Text, MessageID string
	Date                               time.Time
	Headers                            map[string]string
	Parts                              []Part
}

// Build renders the message (CRLF line ends, base64 attachments).
func (m Mail) Build() []byte {
	var body bytes.Buffer
	w := multipart.NewWriter(&body)
	th := textproto.MIMEHeader{}
	th.Set("Content-Type", "text/plain; charset=utf-8")
	pw, _ := w.CreatePart(th)
	_, _ = pw.Write([]byte(strings.ReplaceAll(m.Text, "\n", "\r\n")))
	for _, p := range m.Parts {
		h := textproto.MIMEHeader{}
		h.Set("Content-Type", p.ContentType)
		h.Set("Content-Transfer-Encoding", "base64")
		disp := "attachment"
		if p.Inline {
			disp = "inline"
		}
		h.Set("Content-Disposition", fmt.Sprintf("%s; filename=%q", disp, p.Filename))
		pw, _ := w.CreatePart(h)
		enc := base64.StdEncoding.EncodeToString(p.Data)
		for len(enc) > 76 {
			_, _ = pw.Write([]byte(enc[:76] + "\r\n"))
			enc = enc[76:]
		}
		_, _ = pw.Write([]byte(enc + "\r\n"))
	}
	_ = w.Close()
	var out bytes.Buffer
	date := m.Date
	if date.IsZero() {
		date = time.Now()
	}
	hdr := func(k, v string) { out.WriteString(k + ": " + v + "\r\n") }
	hdr("From", m.From)
	hdr("To", m.To)
	hdr("Subject", m.Subject)
	hdr("Date", date.Format(time.RFC1123Z))
	if m.MessageID != "" {
		hdr("Message-ID", m.MessageID)
	}
	for k, v := range m.Headers {
		hdr(k, v)
	}
	hdr("MIME-Version", "1.0")
	hdr("Content-Type", `multipart/mixed; boundary="`+w.Boundary()+`"`)
	out.WriteString("\r\n")
	out.Write(body.Bytes())
	return out.Bytes()
}
