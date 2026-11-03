package logtoboot

import (
	_ "embed"
	"strings"
)

// Brand colours from the web app's design tokens (web/src/app.css --primary).
const (
	brandPrimary     = "#a7471e"
	brandPrimaryDark = "#f09a6d"
)

// signinCSS is Logto's custom CSS for the sign-in pages, kept in the repo so it
// can be reviewed. {{ORIGIN}} becomes kinframe's origin (for the web font).
//
//go:embed signin.css
var signinCSS string

func signInCSS(origin string) string {
	return strings.ReplaceAll(signinCSS, "{{ORIGIN}}", origin)
}
