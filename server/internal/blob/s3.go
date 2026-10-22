package blob

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

// S3Config configures an S3-compatible store.
type S3Config struct {
	// Endpoint is host[:port], or a URL whose scheme then decides UseSSL.
	Endpoint  string
	Region    string
	Bucket    string
	AccessKey string
	SecretKey string
	UseSSL    bool
	// PathStyle forces path-style requests (http://host/bucket/key), which
	// MinIO/Silo and most self-hosted servers need. Off means automatic
	// (virtual-host style where the provider supports it).
	PathStyle bool
	// Prefix is prepended to every key ("kinframe/"), so one bucket can be
	// shared. A trailing slash is added when missing.
	Prefix string
	// CreateBucket creates the bucket at start when it does not exist.
	CreateBucket bool
	// PublicEndpoint, when set, is the endpoint presigned URLs point at
	// (the bucket as clients reach it). Defaults to Endpoint.
	PublicEndpoint string
}

// S3 stores blobs in an S3-compatible bucket.
type S3 struct {
	client  *minio.Client
	presign *minio.Client
	bucket  string
	prefix  string
}

var _ Store = (*S3)(nil)

func parseEndpoint(ep string, useSSL bool) (host string, secure bool, err error) {
	if strings.Contains(ep, "://") {
		u, err := url.Parse(ep)
		if err != nil {
			return "", false, err
		}
		if u.Path != "" && u.Path != "/" {
			return "", false, fmt.Errorf("S3 endpoint %q must not have a path", ep)
		}
		return u.Host, u.Scheme == "https", nil
	}
	return ep, useSSL, nil
}

func newMinioClient(endpoint string, c S3Config) (*minio.Client, error) {
	host, secure, err := parseEndpoint(endpoint, c.UseSSL)
	if err != nil {
		return nil, err
	}
	lookup := minio.BucketLookupAuto
	if c.PathStyle {
		lookup = minio.BucketLookupPath
	}
	region := c.Region
	if region == "" {
		// Without a region minio-go asks the server (GetBucketLocation)
		// before the first request, and presigning would need the network.
		region = "us-east-1"
	}
	return minio.New(host, &minio.Options{
		Creds:        credentials.NewStaticV4(c.AccessKey, c.SecretKey, ""),
		Secure:       secure,
		Region:       region,
		BucketLookup: lookup,
		Transport:    newTransport(secure),
	})
}

func newTransport(secure bool) http.RoundTripper {
	t, err := minio.DefaultTransport(secure)
	if err != nil {
		return http.DefaultTransport
	}
	t.MaxIdleConnsPerHost = 32
	return t
}

// NewS3 connects to the bucket, creating it when allowed and missing.
func NewS3(ctx context.Context, c S3Config) (*S3, error) {
	if c.Endpoint == "" || c.Bucket == "" {
		return nil, errors.New("S3 endpoint and bucket are required")
	}
	client, err := newMinioClient(c.Endpoint, c)
	if err != nil {
		return nil, err
	}
	presign := client
	if c.PublicEndpoint != "" {
		if presign, err = newMinioClient(c.PublicEndpoint, c); err != nil {
			return nil, err
		}
	}
	prefix := strings.TrimLeft(c.Prefix, "/")
	if prefix != "" && !strings.HasSuffix(prefix, "/") {
		prefix += "/"
	}
	s := &S3{client: client, presign: presign, bucket: c.Bucket, prefix: prefix}
	ok, err := client.BucketExists(ctx, c.Bucket)
	if err != nil {
		return nil, fmt.Errorf("S3 bucket %q: %w", c.Bucket, err)
	}
	if !ok {
		if !c.CreateBucket {
			return nil, fmt.Errorf("S3 bucket %q does not exist (create it, or set KINFRAME_S3_CREATE_BUCKET=1)", c.Bucket)
		}
		if err := client.MakeBucket(ctx, c.Bucket, minio.MakeBucketOptions{Region: c.Region}); err != nil {
			if code := minio.ToErrorResponse(err).Code; code != "BucketAlreadyOwnedByYou" && code != "BucketAlreadyExists" {
				return nil, fmt.Errorf("create S3 bucket %q: %w", c.Bucket, err)
			}
		}
	}
	return s, nil
}

func (*S3) Backend() string { return "s3" }

func (s *S3) object(key string) (string, error) {
	if err := CheckKey(key); err != nil {
		return "", err
	}
	return s.prefix + key, nil
}

func isNotFound(err error) bool {
	switch minio.ToErrorResponse(err).Code {
	case "NoSuchKey", "NotFound", "NoSuchObject":
		return true
	}
	return minio.ToErrorResponse(err).StatusCode == http.StatusNotFound
}

func (s *S3) wrap(key string, err error) error {
	if err == nil {
		return nil
	}
	if isNotFound(err) {
		return fmt.Errorf("%s: %w", key, ErrNotFound)
	}
	return fmt.Errorf("s3 %s: %w", key, err)
}

func putOpts(key string, o PutOptions) minio.PutObjectOptions {
	ct := o.ContentType
	if ct == "" {
		ct = ContentType(key)
	}
	po := minio.PutObjectOptions{ContentType: ct}
	if o.SHA256 != "" {
		po.UserMetadata = map[string]string{"Sha256": o.SHA256}
	}
	return po
}

func (s *S3) Put(ctx context.Context, key string, r io.Reader, size int64, opts PutOptions) error {
	obj, err := s.object(key)
	if err != nil {
		return err
	}
	_, err = s.client.PutObject(ctx, s.bucket, obj, r, size, putOpts(key, opts))
	return s.wrap(key, err)
}

func (s *S3) PutFile(ctx context.Context, key, src string, opts PutOptions) error {
	obj, err := s.object(key)
	if err != nil {
		return err
	}
	f, err := os.Open(src)
	if err != nil {
		return err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return err
	}
	_, err = s.client.PutObject(ctx, s.bucket, obj, f, st.Size(), putOpts(key, opts))
	return s.wrap(key, err)
}

func (s *S3) info(key string, oi minio.ObjectInfo) Info {
	etag := strings.Trim(oi.ETag, `"`)
	sha := oi.UserMetadata["Sha256"]
	if sha == "" {
		sha = oi.Metadata.Get("X-Amz-Meta-Sha256")
	}
	return Info{Key: key, Size: oi.Size, ModTime: oi.LastModified, ETag: `"` + etag + `"`, ContentType: oi.ContentType, SHA256: sha}
}

func (s *S3) Stat(ctx context.Context, key string) (Info, error) {
	obj, err := s.object(key)
	if err != nil {
		return Info{}, err
	}
	oi, err := s.client.StatObject(ctx, s.bucket, obj, minio.StatObjectOptions{})
	if err != nil {
		return Info{}, s.wrap(key, err)
	}
	return s.info(key, oi), nil
}

// Open stats the object; the returned reader issues one ranged GET per
// contiguous read, so a Range request or a 304 costs no full download.
func (s *S3) Open(ctx context.Context, key string) (Object, Info, error) {
	info, err := s.Stat(ctx, key)
	if err != nil {
		return nil, Info{}, err
	}
	obj, _ := s.object(key)
	return &s3Reader{ctx: ctx, s: s, obj: obj, key: key, size: info.Size, etag: info.ETag}, info, nil
}

type s3Reader struct {
	ctx  context.Context
	s    *S3
	obj  string
	key  string
	size int64
	etag string
	off  int64
	body io.ReadCloser
}

func (r *s3Reader) Read(p []byte) (int, error) {
	if r.off >= r.size {
		return 0, io.EOF
	}
	if r.body == nil {
		opts := minio.GetObjectOptions{}
		if r.off > 0 {
			if err := opts.SetRange(r.off, 0); err != nil {
				return 0, err
			}
		}
		// The object must not change between the Stat and later reads.
		if err := opts.SetMatchETag(strings.Trim(r.etag, `"`)); err != nil {
			return 0, err
		}
		core := minio.Core{Client: r.s.client}
		body, _, _, err := core.GetObject(r.ctx, r.s.bucket, r.obj, opts)
		if err != nil {
			return 0, r.s.wrap(r.key, err)
		}
		r.body = body
	}
	n, err := r.body.Read(p)
	r.off += int64(n)
	if err == io.EOF && r.off < r.size {
		err = io.ErrUnexpectedEOF
	}
	return n, err
}

func (r *s3Reader) Seek(offset int64, whence int) (int64, error) {
	var abs int64
	switch whence {
	case io.SeekStart:
		abs = offset
	case io.SeekCurrent:
		abs = r.off + offset
	case io.SeekEnd:
		abs = r.size + offset
	default:
		return 0, errors.New("s3 seek: invalid whence")
	}
	if abs < 0 {
		return 0, errors.New("s3 seek: negative position")
	}
	if abs != r.off && r.body != nil {
		r.body.Close()
		r.body = nil
	}
	r.off = abs
	return abs, nil
}

func (r *s3Reader) Close() error {
	if r.body != nil {
		err := r.body.Close()
		r.body = nil
		return err
	}
	return nil
}

func (s *S3) Delete(ctx context.Context, key string) error {
	obj, err := s.object(key)
	if err != nil {
		return err
	}
	if err := s.client.RemoveObject(ctx, s.bucket, obj, minio.RemoveObjectOptions{}); err != nil && !isNotFound(err) {
		return s.wrap(key, err)
	}
	return nil
}

func (s *S3) List(ctx context.Context, prefix string, fn func(Info) error) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	for oi := range s.client.ListObjects(ctx, s.bucket, minio.ListObjectsOptions{Prefix: s.prefix + prefix, Recursive: true}) {
		if oi.Err != nil {
			return fmt.Errorf("s3 list %q: %w", prefix, oi.Err)
		}
		key := strings.TrimPrefix(oi.Key, s.prefix)
		if strings.HasSuffix(key, "/") {
			continue // directory marker
		}
		// Listings carry no user metadata; SHA256 stays empty.
		if err := fn(s.info(key, oi)); err != nil {
			return err
		}
	}
	return nil
}

func (s *S3) PresignGet(ctx context.Context, key string, ttl time.Duration, contentType string) (string, error) {
	obj, err := s.object(key)
	if err != nil {
		return "", err
	}
	params := url.Values{}
	if contentType != "" {
		params.Set("response-content-type", contentType)
	}
	u, err := s.presign.PresignedGetObject(ctx, s.bucket, obj, ttl, params)
	if err != nil {
		return "", s.wrap(key, err)
	}
	return u.String(), nil
}
