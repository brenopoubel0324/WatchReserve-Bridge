package ghclient

import (
	"context"
	"fmt"
	"net/http"
)

// CommitIdentity is the author/committer name+email from a git commit
// object — not the same as GitHubUser below: git metadata is written by
// whatever local git client/CI produced the commit and can say anything,
// unlike GitHubLogin, which GitHub resolves itself from the email/token
// when it can.
type CommitIdentity struct {
	Name  string `json:"name"`
	Email string `json:"email"`
}

// GitHubUser is the part GitHub resolves itself (can be null if the
// commit's email isn't linked to any account).
type GitHubUser struct {
	Login string `json:"login"`
	Type  string `json:"type"`
}

// PullCommit is one commit from /pulls/{n}/commits — we only care about the
// author/committer git metadata for the anti-fraud heuristic (Laplace factor).
type PullCommit struct {
	SHA    string `json:"sha"`
	Commit struct {
		Author    CommitIdentity `json:"author"`
		Committer CommitIdentity `json:"committer"`
	} `json:"commit"`
	Author    *GitHubUser `json:"author"`    // resolved GitHub account of the commit author (may be nil)
	Committer *GitHubUser `json:"committer"` // resolved GitHub account of the committer (may be nil)
}

// ListPullCommits returns the git metadata of a PR's commits (up to 250 —
// enough for the heuristic; a PR with more commits than that will already
// get "needs-work" for other reasons before any anti-fraud check matters).
func (c *Client) ListPullCommits(ctx context.Context, owner, repo string, number int) ([]PullCommit, error) {
	var commits []PullCommit
	err := c.do(ctx, http.MethodGet, fmt.Sprintf("/repos/%s/%s/pulls/%d/commits?per_page=250", owner, repo, number), nil, &commits)
	if err != nil {
		return nil, err
	}
	return commits, nil
}
