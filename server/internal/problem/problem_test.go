package problem

import (
	"encoding/json"
	"net/http/httptest"
	"testing"
)

func write(t *testing.T, acceptLanguage string, p *Problem) Problem {
	t.Helper()
	r := httptest.NewRequest("GET", "/", nil)
	if acceptLanguage != "" {
		r.Header.Set("Accept-Language", acceptLanguage)
	}
	w := httptest.NewRecorder()
	Write(w, r, p)
	var out Problem
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	return out
}

func TestWriteLocalizes(t *testing.T) {
	got := write(t, "nl-BE,nl;q=0.9,en;q=0.5", New(VersionConflict, "settings changed since version %d (now %d)", 3, 5))
	if got.Title != "Iemand anders heeft dit intussen gewijzigd" {
		t.Errorf("title %q", got.Title)
	}
	if got.Detail != "de instellingen zijn gewijzigd sinds versie 3 (nu 5)" {
		t.Errorf("detail %q", got.Detail)
	}
	if got.Code != VersionConflict || got.Status != 412 {
		t.Errorf("code/status changed: %+v", got)
	}
}

func TestWriteEnglishByDefault(t *testing.T) {
	got := write(t, "", New(NotFound, "frame not found"))
	if got.Title != "Not found" || got.Detail != "frame not found" {
		t.Errorf("%+v", got)
	}
	// Unknown details pass through untranslated; dynamic "%s" details too.
	got = write(t, "nl", New(ValidationFailed, "%s", "body: name is required"))
	if got.Detail != "body: name is required" || got.Title != "Sommige velden zijn niet geldig" {
		t.Errorf("%+v", got)
	}
}
