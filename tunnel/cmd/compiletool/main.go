// Command compiletool compiles a filter-list text file into the
// trie+bloom artifacts used by the bundled CN rules. It exists so the
// rules pipeline can run on an Android device (the tunnel package pulls
// in golang.org/x/sys/unix and cannot build on Windows hosts).
package main

import (
	"fmt"
	"os"

	tunnel "github.com/nqmgaming/blockads-tunnel"
)

func main() {
	if len(os.Args) != 4 {
		fmt.Fprintln(os.Stderr, "usage: compiletool <input.txt> <out.trie> <out.bloom>")
		os.Exit(2)
	}
	n, err := tunnel.CompileFilterList(os.Args[1], os.Args[2], os.Args[3])
	if err != nil {
		fmt.Fprintln(os.Stderr, "compile failed:", err)
		os.Exit(1)
	}
	fmt.Println(n)
}
