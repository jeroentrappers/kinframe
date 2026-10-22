package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestShopAndSMTP(t *testing.T) {
	dir := t.TempDir()
	base := func() map[string]string {
		return map[string]string{"KINFRAME_DATABASE_URL": "postgres://x", "KINFRAME_DEV_AUTH": "1", "KINFRAME_DATA_DIR": dir,
			"KINFRAME_SERVER_NAME": "Familie"}
	}
	c, err := load(func(k string) string { return base()[k] })
	if err != nil || c.Shop != nil || c.SMTP != nil {
		t.Fatalf("no shop and no SMTP by default: %v %v %v", c.Shop, c.SMTP, err)
	}

	shopFile := filepath.Join(dir, "shop.yaml")
	if err := os.WriteFile(shopFile, []byte(`enabled: true
seller: { name: S, email: s@example.org }
notify_email: n@example.org
products: [{ id: frame-10, name: F, price_cents: 7900, max_qty: 5 }]
shipping: { countries: [BE, NL], flat_cents: 695, free_from_cents: 10000 }
`), 0o600); err != nil {
		t.Fatal(err)
	}
	env := base()
	env["KINFRAME_SHOP_FILE"] = shopFile
	env["KINFRAME_SMTP_HOST"] = "smtp.example.org"
	env["KINFRAME_SMTP_FROM"] = "kinframe <noreply@example.org>"
	c, err = load(func(k string) string { return env[k] })
	if err != nil {
		t.Fatal(err)
	}
	if c.Shop == nil || c.Shop.Brand != "Familie" || c.SMTP == nil || c.SMTP.Port != 587 {
		t.Fatalf("shop %+v smtp %+v", c.Shop, c.SMTP)
	}

	if err := os.WriteFile(shopFile, []byte("enabled: true\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	env["KINFRAME_SMTP_FROM"] = ""
	_, err = load(func(k string) string { return env[k] })
	if err == nil || !strings.Contains(err.Error(), "KINFRAME_SHOP_FILE") || !strings.Contains(err.Error(), "seller.name") ||
		!strings.Contains(err.Error(), "KINFRAME_SMTP_FROM") {
		t.Fatalf("invalid shop and SMTP: %v", err)
	}
	env["KINFRAME_SHOP_FILE"] = filepath.Join(dir, "missing.yaml")
	if _, err := load(func(k string) string { return env[k] }); err == nil {
		t.Fatal("a missing shop file must fail startup")
	}

	// A file that says enabled: false is no shop.
	if err := os.WriteFile(shopFile, []byte("enabled: false\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	env["KINFRAME_SHOP_FILE"] = shopFile
	env["KINFRAME_SMTP_FROM"] = "noreply@example.org"
	if c, err := load(func(k string) string { return env[k] }); err != nil || c.Shop != nil {
		t.Fatalf("disabled shop: %v %v", c, err)
	}
}

// A storefront domain is an extra host; the app's own host can't be one.
func TestShopDomainsNotThePublicHost(t *testing.T) {
	dir := t.TempDir()
	shopFile := filepath.Join(dir, "shop.yaml")
	write := func(domains string) {
		t.Helper()
		if err := os.WriteFile(shopFile, []byte(`enabled: true
seller: { name: S, email: s@example.org }
notify_email: n@example.org
products: [{ id: frame-10, name: F, price_cents: 7900, max_qty: 5 }]
shipping: { countries: [BE], flat_cents: 0 }
domains: `+domains+"\n"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	env := map[string]string{"KINFRAME_DATABASE_URL": "postgres://x", "KINFRAME_DEV_AUTH": "1", "KINFRAME_DATA_DIR": dir,
		"KINFRAME_PUBLIC_URL": "https://app.example.org", "KINFRAME_SHOP_FILE": shopFile}
	write("[shop.example.org]")
	c, err := load(func(k string) string { return env[k] })
	if err != nil || !c.Shop.IsStorefront("shop.example.org") {
		t.Fatalf("storefront domain: %v", err)
	}
	write("[shop.example.org, App.Example.org]")
	if _, err := load(func(k string) string { return env[k] }); err == nil || !strings.Contains(err.Error(), "app.example.org") {
		t.Fatalf("the app's host as a storefront: %v", err)
	}
}
