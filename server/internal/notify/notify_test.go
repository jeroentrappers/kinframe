package notify

import "testing"

func TestText(t *testing.T) {
	tests := []struct {
		locale, key string
		args        map[string]string
		title, body string
	}{
		{"en", "reaction", map[string]string{"frame": "Oma"}, "New reaction", "Oma reacted to your photo"},
		{"nl-BE", "reaction", map[string]string{"frame": "Oma"}, "Nieuwe reactie", "Oma reageerde op je foto"},
		{"fr", "reply", map[string]string{"frame": "Oma", "preset": "call_me"}, "New reply", "Oma: Call me"},
		{"nl", "reply", map[string]string{"frame": "Oma", "preset": "miss_you"}, "Nieuw antwoord", "Oma: Ik mis je"},
	}
	for _, tt := range tests {
		ti, b := Text(tt.locale, tt.key, tt.args)
		if ti != tt.title || b != tt.body {
			t.Errorf("%s/%s: got %q %q", tt.locale, tt.key, ti, b)
		}
	}
}
