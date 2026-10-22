module aitriage

go 1.26

// google.golang.org/genai is fetched during the workflow via
// `go get google.golang.org/genai@latest` + `go mod tidy` (see action.yml),
// so the version is intentionally not pinned here — it resolves to
// whatever is current when the Action runs, not when the code was written.
