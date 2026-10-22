package api

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/tus/tusd/v2/pkg/filelocker"
	"github.com/tus/tusd/v2/pkg/filestore"
	"github.com/tus/tusd/v2/pkg/handler"
	expslog "golang.org/x/exp/slog"

	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// setupTus configures tusd (tus 1.0 with creation, creation-with-upload,
// termination and expiration) storing uploads under <data>/uploads.
func (s *Server) setupTus() error {
	s.uploadDir = filepath.Join(s.cfg.DataDir, "uploads")
	if err := os.MkdirAll(s.uploadDir, 0o750); err != nil {
		return err
	}
	composer := handler.NewStoreComposer()
	filestore.New(s.uploadDir).UseIn(composer)
	filelocker.New(s.uploadDir).UseIn(composer)
	h, err := handler.NewUnroutedHandler(handler.Config{
		BasePath:             s.cfg.PublicURL + "/v1/uploads/",
		StoreComposer:        composer,
		DisableDownload:      true,
		DisableConcatenation: true,
		Cors:                 &handler.CorsConfig{Disable: true},
		// tusd logs every request at info level; keep only warnings.
		Logger:                     expslog.New(expslog.NewTextHandler(os.Stderr, &expslog.HandlerOptions{Level: expslog.LevelWarn})),
		PreUploadCreateCallback:    s.tusPreCreate,
		PreFinishResponseCallback:  s.tusPreFinish,
		PreUploadTerminateCallback: s.tusPreTerminate,
	})
	if err != nil {
		return err
	}
	s.tus = h
	return nil
}

func (s *Server) uploadPath(id string) string { return filepath.Join(s.uploadDir, filepath.Base(id)) }

// RemoveUpload deletes an upload's bytes and tusd metadata.
func (s *Server) RemoveUpload(path string) {
	_ = os.Remove(path)
	_ = os.Remove(path + ".info")
	_ = os.Remove(path + ".lock")
}

func tusProblem(code problem.Code, status int, detail string) handler.Error {
	p := problem.New(code, "%s", detail).WithStatus(status)
	body, _ := json.Marshal(p)
	return handler.Error{
		ErrorCode: string(code), Message: detail,
		HTTPResponse: handler.HTTPResponse{StatusCode: status, Body: string(body), Header: handler.HTTPHeader{"Content-Type": "application/problem+json"}},
	}
}

func expiresHeader(created time.Time) string {
	return created.Add(24 * time.Hour).UTC().Format(http.TimeFormat)
}

func (s *Server) tusPreCreate(hook handler.HookEvent) (handler.HTTPResponse, handler.FileInfoChanges, error) {
	ctx := hook.Context
	p := userFrom(ctx)
	if p == nil {
		return handler.HTTPResponse{}, handler.FileInfoChanges{}, tusProblem(problem.Unauthorized, 401, "sign-in required")
	}
	info := hook.Upload
	if info.SizeIsDeferred {
		return handler.HTTPResponse{}, handler.FileInfoChanges{}, tusProblem(problem.ValidationFailed, 400, "Upload-Length is required")
	}
	if limit := s.Limits(ctx).MaxUploadBytes; info.Size > limit {
		return handler.HTTPResponse{}, handler.FileInfoChanges{}, tusProblem(problem.UploadTooLarge, 413, "the file exceeds the server's upload limit")
	}
	md := handler.MetaData{}
	for _, k := range []string{"filename", "filetype", "sha256"} {
		if v, ok := info.MetaData[k]; ok && len(v) <= 512 {
			md[k] = v
		}
	}
	sha := strings.ToLower(md["sha256"])
	if _, err := hex.DecodeString(sha); err != nil || len(sha) != 64 {
		sha = ""
		delete(md, "sha256")
	}
	id := store.NewID().String()
	up := store.Upload{ID: id, UserID: p.user.ID, Size: info.Size}
	if v := md["filename"]; v != "" {
		up.Filename = &v
	}
	if v := md["filetype"]; v != "" {
		up.Filetype = &v
	}
	if sha != "" {
		up.SHA256 = &sha
	}
	if err := s.st.CreateUpload(ctx, up); err != nil {
		return handler.HTTPResponse{}, handler.FileInfoChanges{}, err
	}
	resp := handler.HTTPResponse{Header: handler.HTTPHeader{"Upload-Expires": expiresHeader(time.Now())}}
	if sha != "" {
		if itemID, err := s.st.FindDuplicateItem(ctx, p.user.ID, sha); err == nil && itemID != nil {
			resp.Header["Kinframe-Existing-Item"] = itemID.String()
		}
	}
	return resp, handler.FileInfoChanges{ID: id, MetaData: md}, nil
}

func (s *Server) tusPreFinish(hook handler.HookEvent) (handler.HTTPResponse, error) {
	if err := s.st.CompleteUpload(hook.Context, hook.Upload.ID); err != nil {
		return handler.HTTPResponse{}, err
	}
	return handler.HTTPResponse{}, nil
}

func (s *Server) tusPreTerminate(hook handler.HookEvent) (handler.HTTPResponse, error) {
	up, err := s.st.GetUpload(hook.Context, s.st.Q(), hook.Upload.ID)
	if err == nil && up.ConsumedAt != nil {
		return handler.HTTPResponse{}, tusProblem(problem.ValidationFailed, http.StatusConflict, "the upload is used by a send")
	}
	if err := s.st.DeleteUpload(hook.Context, hook.Upload.ID); err != nil {
		return handler.HTTPResponse{}, err
	}
	return handler.HTTPResponse{}, nil
}

// headerHook runs fn just before the response header is written.
type headerHook struct {
	http.ResponseWriter
	fn   func(http.Header)
	done bool
}

func (h *headerHook) WriteHeader(code int) {
	if !h.done {
		h.done = true
		h.fn(h.Header())
	}
	h.ResponseWriter.WriteHeader(code)
}

func (h *headerHook) Write(b []byte) (int, error) {
	if !h.done {
		h.WriteHeader(http.StatusOK)
	}
	return h.ResponseWriter.Write(b)
}

func (h *headerHook) Unwrap() http.ResponseWriter { return h.ResponseWriter }

// tusRoutes dispatches tus requests, enforcing that only the uploader can
// touch an upload.
func (s *Server) tusRoutes() http.Handler {
	inner := s.tus.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := strings.TrimPrefix(strings.TrimPrefix(r.URL.Path, "/v1/uploads"), "/")
		// Note: tusd writes through the ResponseWriter captured by its Middleware,
		// so headers are set on w before delegating.
		if id == "" {
			if r.Method == http.MethodPost {
				s.tus.PostFile(w, r)
				return
			}
			problem.Write(w, r, problem.New(problem.NotFound, "").WithStatus(http.StatusMethodNotAllowed))
			return
		}
		up, err := s.st.GetUpload(r.Context(), s.st.Q(), id)
		if err != nil || up.UserID != me(r.Context()).ID {
			if err != nil && !errors.Is(err, store.ErrNotFound) {
				s.writeError(w, r, err)
				return
			}
			w.Header().Set("Tus-Resumable", "1.0.0")
			problem.Write(w, r, problem.New(problem.UploadNotFound, "").WithStatus(http.StatusNotFound))
			return
		}
		if up.ConsumedAt == nil {
			w.Header().Set("Upload-Expires", expiresHeader(up.CreatedAt))
		}
		// The unrouted tusd handler expects the path relative to its base path.
		r2 := r.Clone(r.Context())
		r2.URL.Path = "/" + id
		r = r2
		switch r.Method {
		case http.MethodHead:
			s.tus.HeadFile(w, r)
		case http.MethodPatch:
			s.tus.PatchFile(w, r)
		case http.MethodDelete:
			s.tus.DelFile(w, r)
		default:
			problem.Write(w, r, problem.New(problem.NotFound, "").WithStatus(http.StatusMethodNotAllowed))
		}
	}))
	// Advertise the expiration extension (enforced by the upload janitor).
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		inner.ServeHTTP(&headerHook{ResponseWriter: w, fn: func(h http.Header) {
			if ext := h.Get("Tus-Extension"); ext != "" && !strings.Contains(ext, "expiration") {
				h.Set("Tus-Extension", ext+",expiration")
			}
		}}, r)
	})
}
