package config

import (
	"strings"
	"testing"
)

func TestLoadBlob(t *testing.T) {
	s3 := map[string]string{
		"KINFRAME_BLOB_BACKEND": "s3", "KINFRAME_S3_ENDPOINT": "minio:9000", "KINFRAME_S3_BUCKET": "kinframe",
		"KINFRAME_S3_ACCESS_KEY": "a", "KINFRAME_S3_SECRET_KEY": "b", "KINFRAME_S3_USE_SSL": "false", "KINFRAME_S3_PATH_STYLE": "true",
	}
	with := func(base map[string]string, kv ...string) map[string]string {
		m := map[string]string{}
		for k, v := range base {
			m[k] = v
		}
		for i := 0; i < len(kv); i += 2 {
			m[kv[i]] = kv[i+1]
		}
		return m
	}
	tests := []struct {
		name    string
		env     map[string]string
		wantErr string
	}{
		{"default fs", map[string]string{}, ""},
		{"s3", s3, ""},
		{"s3 presigned", with(s3, "KINFRAME_MEDIA_REDIRECT", "presigned"), ""},
		{"s3 missing secret", with(s3, "KINFRAME_S3_SECRET_KEY", ""), "KINFRAME_S3_SECRET_KEY"},
		{"s3 missing bucket and endpoint", with(s3, "KINFRAME_S3_BUCKET", "", "KINFRAME_S3_ENDPOINT", ""), "KINFRAME_S3_BUCKET, KINFRAME_S3_ENDPOINT"},
		{"unknown backend", map[string]string{"KINFRAME_BLOB_BACKEND": "gcs"}, "fs or s3"},
		{"presigned needs s3", map[string]string{"KINFRAME_MEDIA_REDIRECT": "presigned"}, "needs KINFRAME_BLOB_BACKEND=s3"},
		{"bad redirect", with(s3, "KINFRAME_MEDIA_REDIRECT", "proxy"), "off or presigned"},
		{"bad bool", with(s3, "KINFRAME_S3_USE_SSL", "maybe"), "KINFRAME_S3_USE_SSL"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			c, err := LoadBlob(func(k string) string { return tt.env[k] }, "/data")
			if tt.wantErr != "" {
				if err == nil || !strings.Contains(err.Error(), tt.wantErr) {
					t.Fatalf("error = %v, want %q", err, tt.wantErr)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if c.FSRoot != "/data/media" {
				t.Errorf("fs root %q", c.FSRoot)
			}
			if c.Backend == BlobS3 && (c.S3.UseSSL || !c.S3.PathStyle || !c.S3.CreateBucket) {
				t.Errorf("s3 flags: %+v", c.S3)
			}
		})
	}
}
