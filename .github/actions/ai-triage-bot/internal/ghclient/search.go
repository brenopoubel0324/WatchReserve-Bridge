package ghclient

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"time"
)

// SearchIssuesResult is the part of the /search/issues response we need.
type SearchIssuesResult struct {
	TotalCount int `json:"total_count"`
	Items      []struct {
		Number int    `json:"number"`
		State  string `json:"state"`
		// GitHub adds this field when the item is actually a pull request —
		// used as extra protection even though we already filter type:issue.
		PullRequest *struct{} `json:"pull_request,omitempty"`
	} `json:"items"`
}

// SearchAuthorIssuesSince returns the given author's issues in this repo
// created at or after `since`. Only used for the antispam check, so it
// explicitly filters type:issue (pull requests don't count).
func (c *Client) SearchAuthorIssuesSince(ctx context.Context, owner, repo, author string, since time.Time) (*SearchIssuesResult, error) {
	q := fmt.Sprintf("repo:%s/%s type:issue author:%s created:>=%s",
		owner, repo, author, since.UTC().Format("2006-01-02T15:04:05"))

	var res SearchIssuesResult
	err := c.do(ctx, http.MethodGet, "/search/issues?q="+url.QueryEscape(q)+"&per_page=100", nil, &res)
	if err != nil {
		return nil, err
	}
	return &res, nil
}
