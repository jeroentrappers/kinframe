package mailin

import (
	"context"
	"crypto/tls"
	"net"
	"net/smtp"
	"testing"
)

type noBackend struct{}

func (noBackend) CheckRecipient(context.Context, string) RcptStatus { return RcptUnknown }
func (noBackend) Enqueue(context.Context, Envelope, string, int64, *AuthResult) (string, error) {
	return "", nil
}
func (noBackend) MaxMessageBytes(context.Context) int64 { return 1 << 20 }

// Without a configured certificate the receiver still offers STARTTLS, with a self-signed one.
func TestStartTLSWithoutCertificate(t *testing.T) {
	srv, err := NewSMTPServer(SMTPConfig{Hostname: "mx.example.org", Domain: "in.example.org", SpoolDir: t.TempDir()}, noBackend{})
	if err != nil {
		t.Fatal(err)
	}
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go func() { _ = srv.Serve(l) }()
	t.Cleanup(func() { _ = srv.Close() })

	c, err := smtp.Dial(l.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if err := c.Hello("sender.example.net"); err != nil {
		t.Fatal(err)
	}
	if ok, _ := c.Extension("STARTTLS"); !ok {
		t.Fatal("STARTTLS not offered")
	}
	if err := c.StartTLS(&tls.Config{InsecureSkipVerify: true, ServerName: "mx.example.org"}); err != nil {
		t.Fatalf("STARTTLS: %v", err)
	}
	st, ok := c.TLSConnectionState()
	if !ok || len(st.PeerCertificates) == 0 || st.PeerCertificates[0].Subject.CommonName != "mx.example.org" {
		t.Fatalf("unexpected certificate: %+v", st.PeerCertificates)
	}
}
