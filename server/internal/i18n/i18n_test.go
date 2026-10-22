package i18n

import (
	"go/ast"
	"go/parser"
	"go/token"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"testing"
	"unicode/utf8"
)

func TestEveryLanguageHasACatalog(t *testing.T) {
	cat(Default)
	for _, l := range Languages {
		if catalogs[l] == nil {
			t.Errorf("no locales/%s.json", l)
		}
	}
	for l := range catalogs {
		if !slices.Contains(Languages, l) {
			t.Errorf("locales/%s.json is not in Languages", l)
		}
	}
}

var (
	placeholderRe = regexp.MustCompile(`\{\w+\}`)
	verbRe        = regexp.MustCompile(`%[-+# 0]*\d*(\.\d+)?[a-zA-Z%]`)
)

func sorted(xs []string) []string { slices.Sort(xs); return xs }

func TestCatalogsMatchEnglish(t *testing.T) {
	en := cat(Default)
	for _, l := range Languages {
		c := cat(l)
		if c == nil {
			continue
		}
		for k, v := range en.Messages {
			tr, ok := c.Messages[k]
			if !ok || strings.TrimSpace(tr) == "" {
				t.Errorf("%s: missing message %q", l, k)
				continue
			}
			if a, b := sorted(placeholderRe.FindAllString(tr, -1)), sorted(placeholderRe.FindAllString(v, -1)); !slices.Equal(a, b) {
				t.Errorf("%s: %q placeholders %v, want %v", l, k, a, b)
			}
		}
		for k := range c.Messages {
			if _, ok := en.Messages[k]; !ok {
				t.Errorf("%s: unknown message %q", l, k)
			}
		}
		for k := range en.Details {
			tr, ok := c.Details[k]
			if !ok || strings.TrimSpace(tr) == "" {
				t.Errorf("%s: missing detail %q", l, k)
				continue
			}
			// printf verbs must match in kind and order: arguments are positional.
			if a, b := verbRe.FindAllString(tr, -1), verbRe.FindAllString(k, -1); !slices.Equal(a, b) {
				t.Errorf("%s: detail %q verbs %v, want %v", l, k, a, b)
			}
		}
		for k := range c.Details {
			if _, ok := en.Details[k]; !ok {
				t.Errorf("%s: unknown detail %q", l, k)
			}
		}
	}
}

// Every literal problem detail in the server's code must be in the English
// catalog, so it can be translated (problem.Problem.Localize looks it up).
func TestEveryProblemDetailIsInTheCatalog(t *testing.T) {
	root := filepath.Join("..")
	en := cat(Default)
	fset := token.NewFileSet()
	found := 0
	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() || !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") || strings.Contains(path, string(filepath.Separator)+"gen"+string(filepath.Separator)) {
			return nil
		}
		src, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		f, err := parser.ParseFile(fset, path, src, 0)
		if err != nil {
			return err
		}
		ast.Inspect(f, func(n ast.Node) bool {
			call, ok := n.(*ast.CallExpr)
			if !ok {
				return true
			}
			sel, ok := call.Fun.(*ast.SelectorExpr)
			if !ok {
				return true
			}
			pkg, ok := sel.X.(*ast.Ident)
			if !ok || pkg.Name != "problem" {
				return true
			}
			idx := map[string]int{"New": 1, "Validation": 2}[sel.Sel.Name]
			if idx == 0 || len(call.Args) <= idx {
				return true
			}
			lit, ok := call.Args[idx].(*ast.BasicLit)
			if !ok || lit.Kind != token.STRING {
				return true
			}
			s, err := strconv.Unquote(lit.Value)
			if err != nil || s == "" || s == "%s" {
				return true
			}
			found++
			if _, ok := en.Details[s]; !ok {
				t.Errorf("%s: problem detail %q is not in locales/en.json details", fset.Position(lit.Pos()), s)
			}
			return true
		})
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if found < 30 {
		t.Fatalf("only found %d problem details; is the source scan broken?", found)
	}
}

func TestNormalize(t *testing.T) {
	for in, want := range map[string]string{
		"nl":                      "nl",
		"nl-BE":                   "nl",
		"de-CH":                   "de",
		"iw":                      "he",
		"he-IL":                   "he",
		"in":                      "id",
		"no":                      "nb",
		"nb-NO":                   "nb",
		"zh":                      "zh-CN",
		"zh-Hans":                 "zh-CN",
		"zh-CN":                   "zh-CN",
		"zh-TW":                   "zh-TW",
		"zh-Hant-HK":              "zh-TW",
		"zh-HK":                   "zh-TW",
		"pt-PT":                   "pt",
		"sr-Cyrl-RS":              "sr",
		"en-US":                   "en",
		"fr-BE,fr;q=0.9,en;q=0.8": "fr",
		"xx":                      "",
		"":                        "",
		"tlh, de;q=0.5":           "de",
		"ja_JP":                   "ja",
	} {
		if got := Normalize(in); got != want {
			t.Errorf("Normalize(%q) = %q, want %q", in, got, want)
		}
	}
	if got := Pick("", "xx", "nl-BE"); got != "nl" {
		t.Errorf("Pick = %q", got)
	}
	if got := Pick(); got != Default {
		t.Errorf("Pick() = %q", got)
	}
}

func TestDefaultFrameName(t *testing.T) {
	if got := DefaultFrameName("en", "Jeroen Trappers"); got != "Jeroen's frame" {
		t.Errorf("en: %q", got)
	}
	if got := DefaultFrameName("nl", "Jeroen Trappers"); got != "Fotolijst van Jeroen" {
		t.Errorf("nl: %q", got)
	}
	if got := DefaultFrameName("nl", "  "); got != "Fotolijst" {
		t.Errorf("anonymous: %q", got)
	}
	for _, l := range Languages {
		long := DefaultFrameName(l, strings.Repeat("Maximiliaan", 6))
		if n := utf8.RuneCountInString(long); n > 40 {
			t.Errorf("%s: %d characters: %q", l, n, long)
		}
		if got := DefaultFrameName(l, "Ann"); !strings.Contains(got, "Ann") {
			t.Errorf("%s: name missing in %q", l, got)
		}
	}
}

func TestT(t *testing.T) {
	if got := T("nl", "push.reaction.body", map[string]string{"frame": "Oma"}); got != "Oma reageerde op je foto" {
		t.Errorf("got %q", got)
	}
	if got := T("xx", "push.reaction.title", nil); got != "New reaction" {
		t.Errorf("fallback: %q", got)
	}
	if got := T("en", "no.such.key", nil); got != "no.such.key" {
		t.Errorf("missing key: %q", got)
	}
}
