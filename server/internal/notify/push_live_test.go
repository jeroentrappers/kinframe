package notify

import (
	"context"
	"os"
	"testing"

	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Manual check against Firebase: FCM_LIVE_KEY=<service account json> go test -run TestFCMLive ./internal/notify/
func TestFCMLive(t *testing.T) {
	path := os.Getenv("FCM_LIVE_KEY")
	if path == "" {
		t.Skip("FCM_LIVE_KEY not set")
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	s, err := NewFCMSender(b)
	if err != nil {
		t.Fatal(err)
	}
	gone, err := s.Send(context.Background(), store.PushEndpoint{Kind: "fcm", Endpoint: "not-a-real-device-token"}, []byte(`{"type":"test","key":"test","title":"t","body":"b","ids":{}}`))
	t.Logf("fake token: gone=%v err=%v", gone, err)
	if !gone {
		t.Errorf("expected FCM to reject the fake token as invalid (auth works), got gone=%v err=%v", gone, err)
	}
}

// Manual check against Apple: APNS_LIVE_KEY=<.p8> APNS_LIVE_KEY_ID=… APNS_LIVE_TEAM_ID=… go test -run TestAPNsLive ./internal/notify/
func TestAPNsLive(t *testing.T) {
	path := os.Getenv("APNS_LIVE_KEY")
	if path == "" {
		t.Skip("APNS_LIVE_KEY not set")
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	s, err := NewAPNsSender(b, os.Getenv("APNS_LIVE_KEY_ID"), os.Getenv("APNS_LIVE_TEAM_ID"), "app.kinframe.family")
	if err != nil {
		t.Fatal(err)
	}
	fake := "00000000000000000000000000000000000000000000000000000000000000aa"
	gone, err := s.Send(context.Background(), store.PushEndpoint{Kind: "apns", Endpoint: fake}, []byte(`{"type":"test","key":"test","title":"t","body":"b","ids":{}}`))
	t.Logf("fake token: gone=%v err=%v", gone, err)
	if !gone || err != nil {
		t.Errorf("expected Apple to reject the fake token as BadDeviceToken (key accepted), got gone=%v err=%v", gone, err)
	}
}
