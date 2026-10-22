// Package problem implements RFC 9457 problem details with kinframe's stable
// machine-readable codes (openapi.yaml ProblemCode).
package problem

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/i18n"
)

type Code string

const (
	Unauthorized        Code = "unauthorized"
	TokenExpired        Code = "token_expired"
	DeviceRevoked       Code = "device_revoked"
	Forbidden           Code = "forbidden"
	MissingPermission   Code = "missing_permission"
	NotFound            Code = "not_found"
	CodeInvalid         Code = "code_invalid"
	CodeExpired         Code = "code_expired"
	InviteInvalid       Code = "invite_invalid"
	InviteExpired       Code = "invite_expired"
	LinkExpired         Code = "link_expired"
	AlreadyMember       Code = "already_member"
	ValidationFailed    Code = "validation_failed"
	TooManyItems        Code = "too_many_items"
	VideoTooLong        Code = "video_too_long"
	UploadTooLarge      Code = "upload_too_large"
	UploadNotFound      Code = "upload_not_found"
	UnsupportedMedia    Code = "unsupported_media"
	CorruptMedia        Code = "corrupt_media"
	VersionConflict     Code = "version_conflict"
	FrameOffline        Code = "frame_offline"
	CommandTimeout      Code = "command_timeout"
	OwnerCannotLeave    Code = "owner_cannot_leave"
	RateLimited         Code = "rate_limited"
	AppUpdateRequired   Code = "app_update_required"
	UpstreamUnavailable Code = "upstream_unavailable"
	FeatureDisabled     Code = "feature_disabled"
	AlreadyDecided      Code = "already_decided"
	ServerError         Code = "server_error"
)

// defaults per code: HTTP status and English title.
var defaults = map[Code]struct {
	status int
	title  string
}{
	Unauthorized:        {401, "Sign-in required"},
	TokenExpired:        {401, "The access token has expired"},
	DeviceRevoked:       {401, "This frame has been removed"},
	Forbidden:           {403, "You can't do that"},
	MissingPermission:   {403, "You don't have permission for this"},
	NotFound:            {404, "Not found"},
	CodeInvalid:         {404, "That code doesn't match a frame"},
	CodeExpired:         {410, "This code has expired"},
	InviteInvalid:       {404, "This invite link is not valid"},
	InviteExpired:       {410, "This invite link has expired"},
	LinkExpired:         {410, "This link has expired"},
	AlreadyMember:       {409, "You are already a member of this frame"},
	ValidationFailed:    {422, "Some fields are not valid"},
	TooManyItems:        {422, "Too many items in one send"},
	VideoTooLong:        {422, "The video is too long"},
	UploadTooLarge:      {413, "The upload is too large"},
	UploadNotFound:      {422, "The upload was not found or has expired"},
	UnsupportedMedia:    {422, "This file type is not supported"},
	CorruptMedia:        {422, "This file could not be read"},
	VersionConflict:     {412, "Someone else changed this in the meantime"},
	FrameOffline:        {409, "The frame is offline"},
	CommandTimeout:      {504, "The frame did not respond in time"},
	OwnerCannotLeave:    {409, "Transfer ownership before leaving"},
	RateLimited:         {429, "Too many attempts; try again later"},
	AppUpdateRequired:   {426, "The frame app must be updated"},
	UpstreamUnavailable: {502, "An external service is not reachable right now"},
	FeatureDisabled:     {503, "This feature is turned off on this server"},
	AlreadyDecided:      {409, "This was already answered"},
	ServerError:         {500, "Something went wrong on the server"},
}

// FieldError is one entry of Problem.Errors.
type FieldError struct {
	Field string `json:"field"`
	Code  string `json:"code"`
}

// Problem is both an error and the JSON body.
type Problem struct {
	Type       string        `json:"type"`
	Title      string        `json:"title"`
	Status     int           `json:"status"`
	Code       Code          `json:"code"`
	Detail     string        `json:"detail,omitempty"`
	Errors     []FieldError  `json:"errors,omitempty"`
	RetryAfter time.Duration `json:"-"`
	cause      error
	format     string // English detail format: the lookup key for translations
	args       []any
}

func (p *Problem) Error() string {
	s := string(p.Code)
	if p.Detail != "" {
		s += ": " + p.Detail
	}
	if p.cause != nil {
		s += ": " + p.cause.Error()
	}
	return s
}

func (p *Problem) Unwrap() error { return p.cause }

// New builds a problem with the code's default status and title.
func New(code Code, detail string, args ...any) *Problem {
	d, ok := defaults[code]
	if !ok {
		d = defaults[ServerError]
	}
	format := detail
	if len(args) > 0 {
		detail = fmt.Sprintf(detail, args...)
	}
	return &Problem{
		Type:   "https://kinframe.app/errors/" + strings.ReplaceAll(string(code), "_", "-"),
		Title:  d.title,
		Status: d.status,
		Code:   code,
		Detail: detail,
		format: format,
		args:   args,
	}
}

// Localize translates the title and detail into lang (a supported language,
// see package i18n). English, or an unknown language, keeps the texts as built.
func (p *Problem) Localize(lang string) *Problem {
	if lang == "" || lang == i18n.Default {
		return p
	}
	if key := "problem." + string(p.Code); i18n.Has(key) {
		p.Title = i18n.T(lang, key, nil)
	}
	if p.format != "" {
		f := i18n.Detail(lang, p.format)
		if len(p.args) > 0 {
			p.Detail = fmt.Sprintf(f, p.args...)
		} else {
			p.Detail = f
		}
	}
	return p
}

// WithStatus overrides the HTTP status (e.g. 400 for malformed JSON).
func (p *Problem) WithStatus(s int) *Problem { p.Status = s; return p }

// WithField appends a field error.
func (p *Problem) WithField(field, code string) *Problem {
	p.Errors = append(p.Errors, FieldError{Field: field, Code: code})
	return p
}

// Validation is shorthand for a 422 with one field error.
func Validation(field, code, detail string) *Problem {
	return New(ValidationFailed, detail).WithField(field, code)
}

// Internal wraps an unexpected error as a 500 without leaking details.
func Internal(err error) *Problem {
	p := New(ServerError, "")
	p.cause = err
	return p
}

// From converts any error to a Problem.
func From(err error) *Problem {
	var p *Problem
	if errors.As(err, &p) {
		return p
	}
	return Internal(err)
}

// Write serialises p as application/problem+json, localized for the request's
// Accept-Language (r may be nil).
func Write(w http.ResponseWriter, r *http.Request, p *Problem) {
	if r != nil {
		p.Localize(i18n.Normalize(r.Header.Get("Accept-Language")))
	}
	w.Header().Set("Content-Type", "application/problem+json")
	if p.RetryAfter > 0 {
		w.Header().Set("Retry-After", strconv.Itoa(int(p.RetryAfter.Seconds()+0.999)))
	}
	w.WriteHeader(p.Status)
	_ = json.NewEncoder(w).Encode(p)
}
