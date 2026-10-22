// Package githubevent reads the event JSON payload from GITHUB_EVENT_PATH.
package githubevent

import (
	"encoding/json"
	"fmt"
	"os"
)

// Actor holds minimal author info. GitHub gives us Type for free in the
// webhook (no extra request), and it reliably distinguishes "User" from
// "Bot" — the first and cheapest signal for the Laplace factor (see
// internal/laplace).
type Actor struct {
	Login string `json:"login"`
	ID    int64  `json:"id"`
	Type  string `json:"type"` // "User" | "Bot" | "Organization"
}

// IssuePayload is the "issues" event's relevant payload fields.
type IssuePayload struct {
	Number int    `json:"number"`
	Title  string `json:"title"`
	Body   string `json:"body"`
	User   Actor  `json:"user"`
}

// PullRequestPayload is the "pull_request"/"pull_request_target" event's
// relevant payload fields.
type PullRequestPayload struct {
	Number int    `json:"number"`
	Title  string `json:"title"`
	Body   string `json:"body"`
	User   Actor  `json:"user"`
}

// Repository is the repo the event belongs to.
type Repository struct {
	Name  string `json:"name"`
	Owner Actor  `json:"owner"`
}

// Event is the shared payload shape covering both event types the bot listens to.
type Event struct {
	Action      string              `json:"action"`
	Issue       *IssuePayload       `json:"issue"`
	PullRequest *PullRequestPayload `json:"pull_request"`
	Repository  Repository          `json:"repository"`
}

// Load reads and parses the event payload at path (usually GITHUB_EVENT_PATH).
func Load(path string) (*Event, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("reading event payload: %w", err)
	}
	var e Event
	if err := json.Unmarshal(data, &e); err != nil {
		return nil, fmt.Errorf("parsing event payload: %w", err)
	}
	return &e, nil
}
