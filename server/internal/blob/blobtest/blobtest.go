// Package blobtest starts a throwaway S3 server (Silo, the maintained MinIO
// fork) in Docker for tests.
//
// KINFRAME_TEST_S3_ENDPOINT (+ _ACCESS_KEY, _SECRET_KEY) points tests at an
// existing server instead. Without either, tests that need S3 are skipped,
// or fail when KINFRAME_REQUIRE_S3=1.
package blobtest

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/blob"
)

// Image is the default test image; KINFRAME_TEST_S3_IMAGE overrides it.
const Image = "pgsty/silo:RELEASE.2026-10-18T00-00-00Z"

const (
	rootUser     = "kinframe-test"
	rootPassword = "kinframe-test-secret" // throwaway, container-local
)

var (
	once      sync.Once
	endpoint  string
	container string
	startErr  error
	docker    []string // command prefix that reaches the Docker daemon
)

func dockerCmd(args ...string) *exec.Cmd {
	argv := append(append([]string{}, docker...), args...)
	if len(docker) > 0 && docker[0] == "sg" {
		// sg docker -c "docker …": quote the arguments for the shell.
		q := make([]string, len(args))
		for i, a := range args {
			q[i] = "'" + strings.ReplaceAll(a, "'", `'\''`) + "'"
		}
		argv = []string{"sg", "docker", "-c", "docker " + strings.Join(q, " ")}
	}
	return exec.Command(argv[0], argv[1:]...)
}

// findDocker picks plain `docker`, or `sg docker -c` for shells started
// before the user joined the docker group.
func findDocker() error {
	if _, err := exec.LookPath("docker"); err != nil {
		return fmt.Errorf("docker not installed")
	}
	docker = []string{"docker"}
	if dockerCmd("version", "--format", "{{.Server.Version}}").Run() == nil {
		return nil
	}
	if _, err := exec.LookPath("sg"); err == nil {
		docker = []string{"sg"}
		out, err := dockerCmd("version", "--format", "{{.Server.Version}}").CombinedOutput()
		if err == nil {
			return nil
		}
		return fmt.Errorf("docker daemon not reachable (also via sg docker: %v: %s)", err, bytes.TrimSpace(out))
	}
	return fmt.Errorf("docker daemon not reachable")
}

func start() {
	if ep := os.Getenv("KINFRAME_TEST_S3_ENDPOINT"); ep != "" {
		endpoint = ep
		return
	}
	if startErr = findDocker(); startErr != nil {
		return
	}
	image := os.Getenv("KINFRAME_TEST_S3_IMAGE")
	if image == "" {
		image = Image
	}
	var out bytes.Buffer
	cmd := dockerCmd("run", "-d", "--rm", "--label", "kinframe-test=s3", "-p", "127.0.0.1::9000",
		"-e", "MINIO_ROOT_USER="+rootUser, "-e", "MINIO_ROOT_PASSWORD="+rootPassword,
		image, "server", "/data")
	cmd.Stdout, cmd.Stderr = &out, &out
	if err := cmd.Run(); err != nil {
		startErr = fmt.Errorf("docker run %s: %v: %s", image, err, out.String())
		return
	}
	container = strings.TrimSpace(lastLine(out.String()))
	out.Reset()
	cmd = dockerCmd("port", container, "9000/tcp")
	cmd.Stdout, cmd.Stderr = &out, &out
	if err := cmd.Run(); err != nil {
		startErr = fmt.Errorf("docker port: %v: %s", err, out.String())
		return
	}
	endpoint = strings.TrimSpace(strings.Split(strings.TrimSpace(out.String()), "\n")[0])
	deadline := time.Now().Add(60 * time.Second)
	for {
		resp, err := http.Get("http://" + endpoint + "/minio/health/ready")
		if err == nil {
			resp.Body.Close()
			if resp.StatusCode == http.StatusOK {
				return
			}
		}
		if time.Now().After(deadline) {
			startErr = fmt.Errorf("S3 test server on %s not ready: %v", endpoint, err)
			return
		}
		time.Sleep(200 * time.Millisecond)
	}
}

func lastLine(s string) string {
	lines := strings.Split(strings.TrimSpace(s), "\n")
	return lines[len(lines)-1]
}

// Config returns the configuration of a fresh, empty bucket on the test
// server (a new bucket per call, created by NewS3), or skips the test.
func Config(t testing.TB) blob.S3Config {
	t.Helper()
	once.Do(start)
	if startErr != nil {
		if os.Getenv("KINFRAME_REQUIRE_S3") != "" {
			t.Fatalf("S3 test server: %v", startErr)
		}
		t.Skipf("no S3 test server: %v", startErr)
	}
	b := make([]byte, 6)
	_, _ = rand.Read(b)
	access, secret := rootUser, rootPassword
	if os.Getenv("KINFRAME_TEST_S3_ENDPOINT") != "" {
		access, secret = os.Getenv("KINFRAME_TEST_S3_ACCESS_KEY"), os.Getenv("KINFRAME_TEST_S3_SECRET_KEY")
	}
	return blob.S3Config{
		Endpoint: endpoint, Bucket: "kftest-" + hex.EncodeToString(b), Region: "us-east-1",
		AccessKey: access, SecretKey: secret, PathStyle: true, CreateBucket: true,
	}
}

// New returns an S3 store on a fresh bucket, or skips the test.
func New(t testing.TB) *blob.S3 {
	t.Helper()
	cfg := Config(t)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	s, err := blob.NewS3(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

// Stop removes the container started by this process (call from TestMain).
func Stop() {
	if container != "" {
		_ = dockerCmd("rm", "-f", container).Run()
		container = ""
	}
}
