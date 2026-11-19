package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/mailin"
)

// Exit codes Postfix and Exim understand (sysexits.h).
const (
	exOK       = 0
	exUsage    = 64
	exNoUser   = 67
	exTempFail = 75
	exConfig   = 78
)

// deliverEmail reads one message on stdin and posts it, signed, to the inbound
// webhook (docs/spec/email-inbox.md §6.3), for a Postfix pipe transport:
//
//	kinframe unix - n n - - pipe flags=Rq user=kinframe argv=/usr/local/bin/kinframe-server deliver-email
//	  --sender ${sender} --recipient ${recipient} --client-ip ${client_address} --helo ${client_helo}
//
// The secret comes from KINFRAME_EMAIL_WEBHOOK_SECRET (or _FILE; the first of
// "new,old" signs), the URL from --url or KINFRAME_PUBLIC_URL.
func deliverEmail(args []string) int {
	fs := flag.NewFlagSet("deliver-email", flag.ContinueOnError)
	url := fs.String("url", "", "webhook URL (default $KINFRAME_PUBLIC_URL/v1/inbound/email)")
	sender := fs.String("sender", "", "envelope sender (MAIL FROM); empty for the null sender")
	recipient := fs.String("recipient", "", "envelope recipient(s), comma-separated")
	clientIP := fs.String("client-ip", "", "the sending server's IP address")
	helo := fs.String("helo", "", "the sending server's HELO name")
	if err := fs.Parse(args); err != nil {
		return exUsage
	}
	if *recipient == "" {
		fmt.Fprintln(os.Stderr, "deliver-email: --recipient is required")
		return exUsage
	}
	secret := strings.TrimSpace(os.Getenv("KINFRAME_EMAIL_WEBHOOK_SECRET"))
	if secret == "" {
		if f := os.Getenv("KINFRAME_EMAIL_WEBHOOK_SECRET_FILE"); f != "" {
			b, err := os.ReadFile(f)
			if err != nil {
				fmt.Fprintln(os.Stderr, "deliver-email:", err)
				return exConfig
			}
			secret = strings.TrimSpace(string(b))
		}
	}
	secrets := mailin.ParseSecrets(secret)
	if len(secrets) == 0 {
		fmt.Fprintln(os.Stderr, "deliver-email: KINFRAME_EMAIL_WEBHOOK_SECRET is not set")
		return exConfig
	}
	if *url == "" {
		base := strings.TrimRight(os.Getenv("KINFRAME_PUBLIC_URL"), "/")
		if base == "" {
			fmt.Fprintln(os.Stderr, "deliver-email: set --url or KINFRAME_PUBLIC_URL")
			return exConfig
		}
		*url = base + "/v1/inbound/email"
	}
	body, err := io.ReadAll(os.Stdin)
	if err != nil {
		fmt.Fprintln(os.Stderr, "deliver-email: read message:", err)
		return exTempFail
	}
	from := *sender
	if from == "<>" {
		from = ""
	}
	sr := mailin.SignedRequest{Timestamp: strconv.FormatInt(time.Now().Unix(), 10), EnvelopeTo: *recipient, EnvelopeFrom: from,
		ClientIP: *clientIP, Helo: *helo, BodySHA256: mailin.BodyHash(body)}
	req, err := http.NewRequest(http.MethodPost, *url, bytes.NewReader(body))
	if err != nil {
		fmt.Fprintln(os.Stderr, "deliver-email:", err)
		return exConfig
	}
	req.Header.Set("Content-Type", "message/rfc822")
	req.Header.Set("X-Kinframe-Timestamp", sr.Timestamp)
	req.Header.Set("X-Kinframe-Signature", mailin.Sign(secrets[0], sr))
	req.Header.Set("X-Kinframe-Envelope-To", sr.EnvelopeTo)
	req.Header.Set("X-Kinframe-Envelope-From", sr.EnvelopeFrom)
	if sr.ClientIP != "" {
		req.Header.Set("X-Kinframe-Client-Ip", sr.ClientIP)
	}
	if sr.Helo != "" {
		req.Header.Set("X-Kinframe-Helo", sr.Helo)
	}
	res, err := (&http.Client{Timeout: 2 * time.Minute}).Do(req)
	if err != nil {
		fmt.Fprintln(os.Stderr, "deliver-email:", err)
		return exTempFail
	}
	defer res.Body.Close()
	out, _ := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	switch {
	case res.StatusCode == http.StatusTooManyRequests || res.StatusCode >= 500:
		fmt.Fprintf(os.Stderr, "deliver-email: server answered %s, try again later\n", res.Status)
		return exTempFail
	case res.StatusCode != http.StatusAccepted:
		fmt.Fprintf(os.Stderr, "deliver-email: refused: %s %s\n", res.Status, strings.TrimSpace(string(out)))
		return exConfig
	}
	var r struct {
		Recipients []struct {
			Address string `json:"address"`
			Outcome string `json:"outcome"`
		} `json:"recipients"`
	}
	_ = json.Unmarshal(out, &r)
	for _, rc := range r.Recipients {
		if rc.Outcome == "accepted" {
			return exOK
		}
	}
	for _, rc := range r.Recipients {
		fmt.Fprintf(os.Stderr, "deliver-email: %s: %s\n", rc.Address, rc.Outcome)
	}
	return exNoUser
}
