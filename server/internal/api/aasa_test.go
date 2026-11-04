package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestAASAHandler(t *testing.T) {
	rec := httptest.NewRecorder()
	aasaHandler([]string{"TEAM.app.kinframe.family"}).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/.well-known/apple-app-site-association", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Fatalf("content type = %q", ct)
	}
	var doc struct {
		Applinks struct {
			Details []struct {
				AppIDs     []string            `json:"appIDs"`
				Components []map[string]string `json:"components"`
			} `json:"details"`
		} `json:"applinks"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &doc); err != nil {
		t.Fatal(err)
	}
	if len(doc.Applinks.Details) != 1 {
		t.Fatalf("details = %+v", doc.Applinks.Details)
	}
	d := doc.Applinks.Details[0]
	if len(d.AppIDs) != 1 || d.AppIDs[0] != "TEAM.app.kinframe.family" {
		t.Fatalf("appIDs = %v", d.AppIDs)
	}
	paths := map[string]bool{}
	for _, c := range d.Components {
		paths[c["/"]] = true
	}
	if !paths["/f/*"] || !paths["/i/*"] || len(paths) != 2 {
		t.Fatalf("components = %v", d.Components)
	}
}
