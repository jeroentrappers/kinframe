package shop

import (
	"os"
	"strings"
	"testing"

	"gopkg.in/yaml.v3"
)

// The example in docs/deploy.md is what operators copy: it must be a valid
// shop once the Ansible role adds enabled: true.
func TestDeployDocExampleParses(t *testing.T) {
	b, err := os.ReadFile("../../../docs/deploy.md")
	if os.IsNotExist(err) {
		t.Skip("docs/ is not in this build context (server/Dockerfile)")
	}
	if err != nil {
		t.Fatal(err)
	}
	doc := string(b)
	start := strings.Index(doc, "```yaml\nkinframe_shop:")
	if start < 0 {
		t.Fatal("no kinframe_shop example in docs/deploy.md")
	}
	block := doc[start+len("```yaml\n"):]
	block = block[:strings.Index(block, "```")]
	var v struct {
		Shop map[string]any `yaml:"kinframe_shop"`
	}
	if err := yaml.Unmarshal([]byte(block), &v); err != nil {
		t.Fatal(err)
	}
	v.Shop["enabled"] = true
	out, err := yaml.Marshal(v.Shop)
	if err != nil {
		t.Fatal(err)
	}
	c, err := Parse(out, "kinframe")
	if err != nil {
		t.Fatalf("docs example: %v", err)
	}
	if q, _ := c.Price([]Want{{"frame-10", 1}}); q.TotalCents != 7900+695 {
		t.Fatalf("docs example total %d", q.TotalCents)
	}
}
