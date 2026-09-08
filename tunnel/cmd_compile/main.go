package main

import (
	"fmt"
	"os"

	tunnel "github.com/nqmgaming/blockads-tunnel"
)

func main() {
	if len(os.Args) < 4 {
		fmt.Fprintln(os.Stderr, "usage: compiletool <input.txt> <out.trie> <out.bloom>")
		os.Exit(1)
	}
	n, err := tunnel.CompileFilterList(os.Args[1], os.Args[2], os.Args[3])
	if err != nil {
		fmt.Fprintln(os.Stderr, "compile failed:", err)
		os.Exit(1)
	}
	fmt.Printf("compiled %d domains -> %s + %s\n", n, os.Args[2], os.Args[3])
}
