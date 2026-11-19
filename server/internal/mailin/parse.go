package mailin

import (
	"bufio"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"mime"
	"net/mail"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/emersion/go-message"
	"github.com/emersion/go-message/charset"
	"golang.org/x/net/html"
)

var wordDecoder = &mime.WordDecoder{CharsetReader: charset.Reader}

// Limits bound the MIME walk (§8.3, §12.3).
type Limits struct {
	MaxDepth      int   // nesting (multipart and forwarded messages); default 8
	MaxParts      int   // default 200
	MaxPartHeader int64 // bytes per part header; default 64 KiB
	MaxDecoded    int64 // all decoded bytes together; the message size limit
	MaxFileBytes  int64 // one file (max_upload_bytes); bigger ones are too_large
	MaxTextBytes  int64 // caption source text kept; default 64 KiB
	ScratchDir    string
	ScratchPrefix string
}

func (l Limits) withDefaults() Limits {
	if l.MaxDepth <= 0 {
		l.MaxDepth = 8
	}
	if l.MaxParts <= 0 {
		l.MaxParts = 200
	}
	if l.MaxPartHeader <= 0 {
		l.MaxPartHeader = 64 << 10
	}
	if l.MaxDecoded <= 0 {
		l.MaxDecoded = 50 << 20
	}
	if l.MaxFileBytes <= 0 {
		l.MaxFileBytes = l.MaxDecoded
	}
	if l.MaxTextBytes <= 0 {
		l.MaxTextBytes = 64 << 10
	}
	if l.ScratchDir == "" {
		l.ScratchDir = os.TempDir()
	}
	return l
}

// Candidate is a part that may be a photo or video, decoded to a scratch file.
type Candidate struct {
	Path     string // empty when TooLarge
	Filename string
	Size     int64
	SHA256   string
	TooLarge bool
}

// Parsed is what the walk found in a message. Only the top-level headers are
// read; forwarded messages contribute their files, not their headers.
type Parsed struct {
	From, FromName string
	FromOK         bool
	Subject        string
	MessageID      string
	Date           *time.Time
	Text           string // first text/plain outside attachments
	HTMLText       string // text of the first text/html, tags stripped (used only without Text)
	Candidates     []Candidate
	Encrypted      bool
	Unsupported    int // attachments that are not media (archives, documents, ...), and undecodable parts
	Truncated      bool
	// Robot markers (§8.1 step 3).
	AutoSubmitted   string
	Precedence      string
	ListHeaders     bool
	Report          bool
	Calendar        bool // top-level text/calendar or Content-Class calendarmessage
	FromLocal       string
	TopLevelHeaders int
}

// Cleanup removes the candidates' scratch files.
func (p *Parsed) Cleanup() {
	for _, c := range p.Candidates {
		if c.Path != "" {
			_ = os.Remove(c.Path)
		}
	}
}

// Automatic reports whether the message is an automatic one (out of office,
// bounce, list mail without media, calendar invite without images).
func (p *Parsed) Automatic(envelopeFrom string, nullSender bool) bool {
	if a := strings.ToLower(strings.TrimSpace(p.AutoSubmitted)); a != "" && a != "no" {
		return true
	}
	switch strings.ToLower(strings.TrimSpace(p.Precedence)) {
	case "bulk", "junk", "list":
		return true
	}
	usable := false
	for _, c := range p.Candidates {
		if !c.TooLarge {
			usable = true
		}
	}
	if p.ListHeaders && !usable {
		return true
	}
	if nullSender {
		return true
	}
	switch strings.ToLower(p.FromLocal) {
	case "mailer-daemon", "postmaster":
		return true
	}
	if p.Report {
		return true
	}
	if p.Calendar && len(p.Candidates) == 0 {
		return true
	}
	return false
}

type walker struct {
	lim     Limits
	out     *Parsed
	parts   int
	decoded int64
}

// ErrTimeBudget is returned by callers that stop a parse after its time budget.
var ErrTimeBudget = errors.New("parse time budget exceeded")

// Parse walks a raw message. Files go to scratch; the caller removes them
// (Cleanup) once they are no longer needed.
func Parse(r io.Reader, lim Limits) (*Parsed, error) {
	lim = lim.withDefaults()
	e, err := message.ReadWithOptions(bufio.NewReader(r), &message.ReadOptions{MaxHeaderBytes: MaxHeaderBytes})
	if err != nil && e == nil {
		return nil, err
	}
	p := &Parsed{}
	h := mail.Header(e.Header.Map())
	p.From, p.FromName, p.FromOK = ParseFrom(h)
	if p.FromOK {
		p.FromLocal = p.From[:strings.LastIndexByte(p.From, '@')]
	} else if list, err := mail.ParseAddressList(e.Header.Get("From")); err == nil && len(list) > 0 {
		p.FromLocal, _, _ = strings.Cut(list[0].Address, "@")
	}
	p.Subject, _ = e.Header.Text("Subject")
	p.MessageID = strings.TrimSpace(e.Header.Get("Message-Id"))
	if d, err := mail.ParseDate(e.Header.Get("Date")); err == nil {
		p.Date = &d
	}
	p.AutoSubmitted = e.Header.Get("Auto-Submitted")
	p.Precedence = e.Header.Get("Precedence")
	p.ListHeaders = e.Header.Get("List-Id") != "" || e.Header.Get("List-Unsubscribe") != ""
	mt, _, _ := e.Header.ContentType()
	if mt == "text/calendar" || strings.Contains(strings.ToLower(e.Header.Get("Content-Class")), "calendarmessage") {
		p.Calendar = true
	}
	w := &walker{lim: lim, out: p}
	w.walk(e, err, 0, false)
	return p, nil
}

func (w *walker) walk(e *message.Entity, readErr error, depth int, inAttachment bool) {
	w.parts++
	if w.parts > w.lim.MaxParts || depth > w.lim.MaxDepth {
		w.out.Truncated = true
		return
	}
	if headerSize(e) > w.lim.MaxPartHeader {
		w.out.Truncated = true
		return
	}
	mt, params, _ := e.Header.ContentType()
	mt = strings.ToLower(mt)
	disp, dparams, _ := e.Header.ContentDisposition()
	filename := dparams["filename"]
	if filename == "" {
		filename = params["name"]
	}
	attachment := inAttachment || strings.EqualFold(disp, "attachment")
	switch {
	case mt == "multipart/encrypted":
		w.out.Encrypted = true
		return
	case mt == "application/pkcs7-mime" || mt == "application/x-pkcs7-mime":
		if st := strings.ToLower(params["smime-type"]); st == "" || st == "enveloped-data" || st == "authenveloped-data" {
			w.out.Encrypted = true
		} else {
			w.out.Unsupported++ // opaque signed-data: the content is inside the CMS structure
		}
		return
	case mt == "multipart/report":
		w.out.Report = true
		w.children(e, depth, attachment, false)
	case mt == "multipart/signed":
		w.children(e, depth, attachment, true)
	case mt == "multipart/alternative":
		w.alternative(e, depth, attachment)
	case strings.HasPrefix(mt, "multipart/"):
		w.children(e, depth, attachment, false)
	case mt == "message/rfc822" || mt == "message/global":
		nested, err := message.ReadWithOptions(bufio.NewReader(e.Body), &message.ReadOptions{MaxHeaderBytes: w.lim.MaxPartHeader})
		if nested == nil {
			if err != nil {
				w.out.Unsupported++
			}
			return
		}
		w.walk(nested, err, depth+1, false)
	case readErr != nil && message.IsUnknownEncoding(readErr):
		w.out.Unsupported++
	case mt == "text/calendar" || mt == "application/ics":
		// Ignored.
	case mt == "text/plain" && !attachment && filename == "":
		if w.out.Text == "" {
			w.out.Text = w.readText(e.Body)
		}
	case mt == "text/html" && !attachment && filename == "":
		if w.out.HTMLText == "" {
			w.out.HTMLText = htmlText(w.readText(e.Body))
		}
	case isMediaType(mt) || mt == "application/octet-stream" || looksLikeMedia(filename) && !isArchive(mt):
		w.candidate(e.Body, filename)
	default:
		if attachment || filename != "" {
			w.out.Unsupported++
		}
	}
}

func headerSize(e *message.Entity) int64 {
	var n int64
	f := e.Header.Fields()
	for f.Next() {
		n += int64(len(f.Key()) + len(f.Value()) + 4)
	}
	return n
}

func (w *walker) children(e *message.Entity, depth int, attachment, firstOnly bool) {
	mr := e.MultipartReader()
	if mr == nil {
		return
	}
	for {
		part, err := mr.NextPart()
		if part == nil {
			if err != nil && !errors.Is(err, io.EOF) {
				w.out.Truncated = true
			}
			return
		}
		w.walk(part, err, depth+1, attachment)
		if firstOnly || w.parts > w.lim.MaxParts {
			// multipart/signed: the second part is the signature. Drain the rest of the walk.
			if firstOnly {
				_, _ = io.Copy(io.Discard, io.LimitReader(e.Body, w.lim.MaxDecoded))
			}
			return
		}
	}
}

// alternative walks each branch on its own and keeps the one with the most
// files; with no files anywhere, the branches' texts are kept.
func (w *walker) alternative(e *message.Entity, depth int, attachment bool) {
	mr := e.MultipartReader()
	if mr == nil {
		return
	}
	parent := w.out
	var best *Parsed
	var all []*Parsed
	for {
		part, err := mr.NextPart()
		if part == nil {
			break
		}
		sub := &Parsed{}
		w.out = sub
		w.walk(part, err, depth+1, attachment)
		all = append(all, sub)
		if best == nil || len(sub.Candidates) >= len(best.Candidates) && len(sub.Candidates) > 0 {
			best = sub
		}
		if w.parts > w.lim.MaxParts {
			break
		}
	}
	w.out = parent
	for _, sub := range all {
		parent.Encrypted = parent.Encrypted || sub.Encrypted
		parent.Truncated = parent.Truncated || sub.Truncated
		parent.Report = parent.Report || sub.Report
		if best != nil && len(best.Candidates) > 0 && sub != best {
			sub.Cleanup()
			continue
		}
		parent.Candidates = append(parent.Candidates, sub.Candidates...)
		parent.Unsupported += sub.Unsupported
		if parent.Text == "" {
			parent.Text = sub.Text
		}
		if parent.HTMLText == "" {
			parent.HTMLText = sub.HTMLText
		}
	}
}

func (w *walker) readText(r io.Reader) string {
	b, _ := io.ReadAll(io.LimitReader(r, w.lim.MaxTextBytes))
	_, _ = io.Copy(io.Discard, io.LimitReader(r, w.lim.MaxDecoded))
	return strings.ToValidUTF8(string(b), "")
}

func (w *walker) candidate(body io.Reader, filename string) {
	c := Candidate{Filename: strings.TrimSpace(filename)}
	remaining := w.lim.MaxDecoded - w.decoded
	if remaining <= 0 {
		w.out.Truncated = true
		return
	}
	f, err := os.CreateTemp(w.lim.ScratchDir, w.lim.ScratchPrefix+"mail-*")
	if err != nil {
		w.out.Unsupported++
		return
	}
	h := sha256.New()
	limit := min(w.lim.MaxFileBytes, remaining) + 1
	n, err := io.Copy(io.MultiWriter(f, h), io.LimitReader(body, limit))
	closeErr := f.Close()
	w.decoded += n
	if err != nil || closeErr != nil {
		_ = os.Remove(f.Name())
		w.out.Unsupported++
		return
	}
	if n > w.lim.MaxFileBytes {
		_ = os.Remove(f.Name())
		_, _ = io.Copy(io.Discard, io.LimitReader(body, w.lim.MaxDecoded))
		c.TooLarge = true
		w.out.Candidates = append(w.out.Candidates, c)
		return
	}
	if n >= limit {
		// Over the message's decoded budget.
		_ = os.Remove(f.Name())
		w.out.Truncated = true
		return
	}
	if n == 0 {
		_ = os.Remove(f.Name())
		return
	}
	c.Path, c.Size, c.SHA256 = f.Name(), n, hex.EncodeToString(h.Sum(nil))
	w.out.Candidates = append(w.out.Candidates, c)
}

func isMediaType(mt string) bool {
	return strings.HasPrefix(mt, "image/") || strings.HasPrefix(mt, "video/")
}

var mediaExt = map[string]bool{
	".jpg": true, ".jpeg": true, ".png": true, ".heic": true, ".heif": true, ".webp": true, ".gif": true, ".avif": true,
	".mp4": true, ".m4v": true, ".mov": true, ".webm": true, ".3gp": true, ".3g2": true, ".mkv": true,
}

func looksLikeMedia(filename string) bool {
	return mediaExt[strings.ToLower(filepath.Ext(filename))]
}

func isArchive(mt string) bool {
	switch mt {
	case "application/zip", "application/x-zip-compressed", "application/x-rar-compressed", "application/vnd.rar",
		"application/x-7z-compressed", "application/x-tar", "application/gzip", "application/x-gzip", "application/ms-tnef",
		"application/vnd.ms-tnef", "application/pdf":
		return true
	}
	return strings.HasPrefix(mt, "application/vnd.openxmlformats") || strings.HasPrefix(mt, "application/vnd.ms-") ||
		strings.HasPrefix(mt, "application/msword") || strings.HasPrefix(mt, "application/vnd.oasis")
}

// htmlText extracts the visible text of an HTML body with a tokenizer:
// nothing is rendered or fetched.
func htmlText(s string) string {
	z := html.NewTokenizer(strings.NewReader(s))
	var b strings.Builder
	skip := 0
	for {
		tt := z.Next()
		switch tt {
		case html.ErrorToken:
			return strings.TrimSpace(b.String())
		case html.StartTagToken, html.SelfClosingTagToken:
			name, _ := z.TagName()
			switch string(name) {
			case "script", "style", "head", "title":
				if tt == html.StartTagToken {
					skip++
				}
			case "br", "p", "div", "li", "tr", "h1", "h2", "h3", "h4", "blockquote":
				b.WriteByte('\n')
			}
			if string(name) == "blockquote" {
				b.WriteString("> ")
			}
		case html.EndTagToken:
			name, _ := z.TagName()
			switch string(name) {
			case "script", "style", "head", "title":
				if skip > 0 {
					skip--
				}
			case "p", "div", "li", "tr":
				b.WriteByte('\n')
			}
		case html.TextToken:
			if skip == 0 {
				b.Write(z.Text())
			}
		}
	}
}
