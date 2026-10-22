package api

import (
	"encoding/json"
	"net/http"
)

// aasaHandler serves /.well-known/apple-app-site-association: it lets the iOS
// family app open frame join links (/f/{code}) and invite links (/i/{token})
// as Universal Links instead of the web app. Apple fetches it through its CDN,
// without redirects, and requires application/json.
func aasaHandler(appIDs []string) http.Handler {
	type component struct {
		Path    string `json:"/"`
		Comment string `json:"comment,omitempty"`
	}
	type detail struct {
		AppIDs     []string    `json:"appIDs"`
		Components []component `json:"components"`
	}
	var doc struct {
		Applinks struct {
			Details []detail `json:"details"`
		} `json:"applinks"`
	}
	doc.Applinks.Details = []detail{{
		AppIDs: appIDs,
		Components: []component{
			{Path: "/f/*", Comment: "Frame code"},
			{Path: "/i/*", Comment: "Invite link"},
		},
	}}
	body, _ := json.Marshal(doc)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "public, max-age=3600")
		_, _ = w.Write(body)
	})
}
