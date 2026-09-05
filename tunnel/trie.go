package tunnel

import (
	"encoding/binary"
	"fmt"
	"os"
	"strings"

	"golang.org/x/sys/unix"
)

const (
	trieMagic   = 0x54524945 // "TRIE" in hex
	trieVersion = 2
	headerSize  = 16
)

// MmapTrie represents a read-only memory-mapped DomainTrie.
type MmapTrie struct {
	file   *os.File
	buffer []byte
	limit  int
}

// LoadMmapTrie opens a file and memory-maps its contents.
func LoadMmapTrie(path string) (*MmapTrie, error) {
	if path == "" {
		return nil, fmt.Errorf("empty path")
	}

	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("failed to open trie file: %w", err)
	}

	stat, err := f.Stat()
	if err != nil {
		f.Close()
		return nil, fmt.Errorf("failed to stat trie file: %w", err)
	}

	size := stat.Size()
	if size < headerSize {
		f.Close()
		return nil, fmt.Errorf("file too small to be a valid trie")
	}

	// Memory map the file (Read Only)
	data, err := unix.Mmap(int(f.Fd()), 0, int(size), unix.PROT_READ, unix.MAP_SHARED)
	if err != nil {
		f.Close()
		return nil, fmt.Errorf("mmap failed: %w", err)
	}

	magic := binary.BigEndian.Uint32(data[0:4])
	if magic != trieMagic {
		unix.Munmap(data)
		f.Close()
		return nil, fmt.Errorf("invalid trie magic: expected %X, got %X", trieMagic, magic)
	}

	version := binary.BigEndian.Uint32(data[4:8])
	if version != trieVersion {
		unix.Munmap(data)
		f.Close()
		return nil, fmt.Errorf("invalid trie version: expected %d, got %d", trieVersion, version)
	}

	return &MmapTrie{
		file:   f,
		buffer: data,
		limit:  int(size),
	}, nil
}

// Close unmaps the memory and closes the file.
func (m *MmapTrie) Close() {
	if m.buffer != nil {
		unix.Munmap(m.buffer)
		m.buffer = nil
	}
	if m.file != nil {
		m.file.Close()
		m.file = nil
	}
}

// ContainsOrParent checks if a domain or any of its parent domains exists in the trie.
// It also checks for wildcard (*) matches from top to bottom.
func (m *MmapTrie) ContainsOrParent(domain string) bool {
	if m.buffer == nil {
		return false
	}
	// Split and sanitize labels
	labels := strings.Split(domain, ".")
	return m.matchWithWildcard(headerSize, labels, len(labels)-1)
}

// matchWithWildcard recursively checks exact and wildcard matches.
//
// Wildcards live in the trie as labels beginning with '*':
//   - "*"        matches any single label (whole-label wildcard)
//   - "*-ad"     matches any label ending with "-ad" (label-tail glob)
//
// Both are resolved in the same child scan as exact labels so the hot
// path stays a single pass per node.
func (m *MmapTrie) matchWithWildcard(nodeOffset int, labels []string, index int) bool {
	if index < 0 {
		return false
	}
	if nodeOffset < 0 || nodeOffset >= m.limit {
		return false
	}

	targetLabel := labels[index]

	exactOffset, globOffset := m.findChildAndGlobOffset(nodeOffset, targetLabel)

	// 1. Try exact label match
	if exactOffset != -1 {
		if m.isTerminal(exactOffset) {
			return true
		}
		if m.matchWithWildcard(exactOffset, labels, index-1) {
			return true
		}
	}

	// 2. Try glob label match ("*" or "*suffix")
	if globOffset != -1 {
		if m.isTerminal(globOffset) {
			return true
		}
		if m.matchWithWildcard(globOffset, labels, index-1) {
			return true
		}
	}

	return false
}

func (m *MmapTrie) isTerminal(nodeOffset int) bool {
	if nodeOffset < 0 || nodeOffset >= m.limit {
		return false
	}
	return m.buffer[nodeOffset] != 0
}

// findChildAndGlobOffset makes a single scan over a node's children and
// returns both the exact-label child and the first glob child whose pattern
// matches the target label. Glob labels start with '*':
//
//	"*"      matches any label (whole-label wildcard)
//	"*-ad"   matches labels ending with "-ad" (label-tail glob)
//
// Returns -1 for each component that has no match.
func (m *MmapTrie) findChildAndGlobOffset(nodeOffset int, targetLabel string) (exact int, glob int) {
	exact, glob = -1, -1
	if nodeOffset < 0 || nodeOffset+5 > m.limit {
		return
	}

	targetBytes := []byte(targetLabel)
	targetLen := len(targetBytes)

	pos := nodeOffset + 1 // skip isTerminal byte

	// BigEndian: read uint32 (4 bytes) for child count
	childCount := int(binary.BigEndian.Uint32(m.buffer[pos : pos+4]))
	pos += 4

	for c := 0; c < childCount; c++ {
		if pos+2 > m.limit {
			return // buffer too short
		}
		labelLen := int(binary.BigEndian.Uint16(m.buffer[pos : pos+2]))
		pos += 2

		if pos+labelLen+4 > m.limit {
			return // corrupted data
		}

		if labelLen > 0 && m.buffer[pos] == '*' {
			// Glob child: "*" matches any label, "*suffix" by suffix
			if glob == -1 {
				suffix := string(m.buffer[pos+1 : pos+labelLen])
				if suffix == "" || strings.HasSuffix(targetLabel, suffix) {
					childOffset := int(binary.BigEndian.Uint32(m.buffer[pos+labelLen : pos+labelLen+4]))
					if childOffset >= headerSize && childOffset < m.limit {
						glob = childOffset
					}
				}
			}
		} else if labelLen == targetLen {
			match := true
			for b := 0; b < labelLen; b++ {
				if m.buffer[pos+b] != targetBytes[b] {
					match = false
					break
				}
			}
			if match {
				childOffset := int(binary.BigEndian.Uint32(m.buffer[pos+labelLen : pos+labelLen+4]))
				if childOffset >= headerSize && childOffset < m.limit {
					exact = childOffset
				}
			}
		}

		pos += labelLen + 4 // skip label bytes + child offset
	}

	return
}
