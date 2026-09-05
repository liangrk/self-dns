package tunnel

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestGlobTrieRoundTrip verifies the label-tail glob feature end to end:
// compile a rule list containing globs, then match real-world domains
// through bloom + trie exactly like the engine does.
func TestGlobTrieRoundTrip(t *testing.T) {
	dir := t.TempDir()
	listPath := filepath.Join(dir, "list.txt")
	triePath := filepath.Join(dir, "t.trie")
	bloomPath := filepath.Join(dir, "t.bloom")

	// Mixed list: glob rules, whole-label wildcard, plain rules, and
	// content domains that must stay allowed.
	list := strings.Join([]string{
		"# comment line",
		"*-ad.qznovelvod.com",
		"*-ads.qznovelvod.com",
		"*.wild.example.com",
		"ads.example.com",
		"starrydyn.com",
		"v5-ex-reading-video-a.qznovelvod.com", // exact, pilot entry
	}, "\n") + "\n"

	if err := writeFile(listPath, list); err != nil {
		t.Fatalf("write list: %v", err)
	}

	count, err := CompileFilterList(listPath, triePath, bloomPath)
	if err != nil {
		t.Fatalf("CompileFilterList: %v", err)
	}
	if count != 6 {
		t.Fatalf("expected 6 compiled domains (comment excluded), got %d", count)
	}

	trie, err := LoadMmapTrie(triePath)
	if err != nil {
		t.Fatalf("LoadMmapTrie: %v", err)
	}
	defer trie.Close()
	bloom, err := LoadBloomFilter(bloomPath)
	if err != nil {
		t.Fatalf("LoadBloomFilter: %v", err)
	}
	defer bloom.Close()

	cases := []struct {
		domain string
		want   bool
		note   string
	}{
		{"v5-ex-reading-ad.qznovelvod.com", true, "glob *-ad matches v5 prefix"},
		{"v26-reading-ad.qznovelvod.com", true, "glob *-ad matches v26 prefix"},
		{"v66-pro-new-reading-ad.qznovelvod.com", true, "glob matches stacked prefixes"},
		{"x-reading-ads.qznovelvod.com", true, "glob *-ads matches"},
		{"v5-ex-reading-video-a.qznovelvod.com", true, "exact pilot entry"},
		{"reading-video-a.qznovelvod.com", false, "content video host must stay allowed"},
		{"reading.qznovelvod.com", false, "content base host allowed"},
		{"qznovelvod.com", false, "parent domain itself allowed"},
		{"anything.wild.example.com", true, "whole-label wildcard"},
		{"a.b.wild.example.com", true, "whole-label wildcard deep"},
		{"wild.example.com", false, "wildcard must not match its own base"},
		{"ads.example.com", true, "plain rule"},
		{"example.com", false, "plain rule parent allowed"},
		{"foo.ads.example.com", true, "plain rule subdomain via parent match"},
		{"starrydyn.com", true, "plain parent rule"},
		{"douyin.starrydyn.com", true, "subdomain of plain parent rule"},
		{"ad.wrongexample.com", false, "unrelated domain with -ad inside label prefix"},
		{"*-ad.qznovelvod.com", true, "literal pattern matches itself; harmless since '*' never appears in real DNS queries (RFC 1035)"},
	}

	for _, tc := range cases {
		// Engine pipeline: bloom pre-filter then trie decision.
		if !bloom.MightContainDomainOrParent(tc.domain) {
			if tc.want {
				t.Errorf("BLOOM MISS (would skip trie): %s (%s)", tc.domain, tc.note)
			}
			continue
		}
		got := trie.ContainsOrParent(tc.domain)
		if got != tc.want {
			t.Errorf("match(%s) = %v, want %v (%s)", tc.domain, got, tc.want, tc.note)
		}
	}
}

// TestGlobValidation covers parseDomainLine's glob accept/reject rules.
func TestGlobValidation(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"*-ad.qznovelvod.com", "*-ad.qznovelvod.com"},   // valid tail glob
		{"*.qznovelvod.com", "*.qznovelvod.com"},         // valid whole-label
		{"*-AD.Example.COM", "*-ad.example.com"},          // lowercased
		{"ad*-bad.example.com", ""},                       // '*' must lead the label
		{"*-a*d.example.com", ""},                         // second '*' rejected
		{"*-ad.", ""},                                     // no base domain
		{"*-ad.example", ""},                              // base needs label+tld
		{"*-ad.exa_mple.com", "*-ad.exa_mple.com"},        // underscore tolerated
		{"*-ad..com", ""},                                 // empty base label
		{"v5-ex-reading-ad.qznovelvod.com", "v5-ex-reading-ad.qznovelvod.com"}, // plain
		{"0.0.0.0 blocked.example.org", "blocked.example.org"}, // hosts format
		{"||ad.doubleclick.net^", "ad.doubleclick.net"},        // adblock format
	}
	for _, tc := range cases {
		got := parseDomainLine(tc.in)
		if got != tc.want {
			t.Errorf("parseDomainLine(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

// TestGlobBloomKeyIsBaseDomain verifies the critical invariant: a glob rule
// must be bloom-indexed by its base domain, otherwise the engine's
// bloom-first pipeline never consults the trie for matching domains.
func TestGlobBloomKeyIsBaseDomain(t *testing.T) {
	cases := []struct {
		rule string
		base string
		ok   bool
	}{
		{"*-ad.qznovelvod.com", "qznovelvod.com", true},
		{"*.wild.example.com", "wild.example.com", true},
		{"ads.example.com", "", false},
	}
	for _, tc := range cases {
		base, ok := globBaseDomain(tc.rule)
		if ok != tc.ok || (ok && base != tc.base) {
			t.Errorf("globBaseDomain(%q) = %q,%v want %q,%v", tc.rule, base, ok, tc.base, tc.ok)
		}
	}
}

func writeFile(path, content string) error {
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = f.WriteString(content)
	return err
}
