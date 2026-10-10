package main

import (
	"crypto/sha256"
	"debug/elf"
	"debug/gosym"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"runtime"
	"sort"
)

func main() {
	if len(os.Args) != 2 {
		panic("one executable input required")
	}
	data, err := os.ReadFile(os.Args[1])
	if err != nil {
		panic(err)
	}
	binary, err := elf.Open(os.Args[1])
	if err != nil {
		panic(err)
	}
	defer binary.Close()
	if binary.Class != elf.ELFCLASS64 || binary.Machine != elf.EM_X86_64 || binary.Section(".gopclntab") == nil || binary.Section(".text") == nil {
		panic("missing amd64 Go function table")
	}
	pcln, err := binary.Section(".gopclntab").Data()
	if err != nil {
		panic(err)
	}
	table, err := gosym.NewTable(nil, gosym.NewLineTable(pcln, binary.Section(".text").Addr))
	if err != nil || table == nil || len(table.Funcs) == 0 {
		panic("Go function-table decoding failed")
	}
	functions := make([]string, 0, len(table.Funcs))
	for _, function := range table.Funcs {
		if function.Sym == nil || function.Sym.Name == "" {
			panic("incomplete Go function table")
		}
		functions = append(functions, function.Sym.Name)
	}
	sort.Strings(functions)
	digest := sha256.Sum256(data)
	if err := json.NewEncoder(os.Stdout).Encode(map[string]any{
		"binary_sha256": hex.EncodeToString(digest[:]), "reader_go_version": runtime.Version(),
		"function_count": len(functions), "functions": functions,
	}); err != nil {
		panic(fmt.Errorf("write function evidence: %w", err))
	}
}
