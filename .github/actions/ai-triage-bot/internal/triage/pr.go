package triage

import (
	"context"
	"fmt"
	"log"

	"aitriage/internal/ai"
	"aitriage/internal/config"
	"aitriage/internal/ghclient"
	"aitriage/internal/githubevent"
	"aitriage/internal/laplace"
	"aitriage/internal/promptbuilder"
)

// HandlePullRequest is the entry point for "pull_request"/"pull_request_target"
// events. modGH is the (optional, may be nil) elevated-permission client
// used only for user blocking when the AI flags a moderation violation —
// PRs themselves can never be truly deleted via any GitHub API, so modGH
// doesn't change what happens to the PR's content, only whether a repeat
// offender also gets blocked. See internal/triage/moderation.go.
func HandlePullRequest(ctx context.Context, cfg *config.Config, gh, modGH *ghclient.Client, aiClient *ai.Client, event *githubevent.Event) error {
	if event.PullRequest == nil {
		return fmt.Errorf("pull_request event payload is missing the pull_request field")
	}
	if event.Action != "opened" {
		log.Printf("PR #%d: action=%q, the bot only reacts to 'opened' — exiting", event.PullRequest.Number, event.Action)
		return nil
	}

	owner, repo := cfg.RepoOwner, cfg.RepoName
	number := event.PullRequest.Number
	author := event.PullRequest.User.Login

	// Idempotency: one comment, and the bot never touches this PR again.
	done, err := alreadyProcessed(ctx, gh, owner, repo, number)
	if err != nil {
		return fmt.Errorf("checking prior comments: %w", err)
	}
	if done {
		log.Printf("PR #%d was already processed by the bot — exiting", number)
		return nil
	}

	p, err := loadPrompts(cfg)
	if err != nil {
		return err
	}
	prLabels := filterLabels(p.labels, "pull_request")

	pr, err := gh.GetPullRequest(ctx, owner, repo, number)
	if err != nil {
		return fmt.Errorf("fetching PR #%d: %w", number, err)
	}

	// The diff is the main source of context for a PR, read in one request.
	diff, err := gh.GetPullDiff(ctx, owner, repo, number)
	if err != nil {
		return fmt.Errorf("fetching diff for PR #%d: %w", number, err)
	}
	if len(diff) > cfg.MaxDiffBytes {
		diff = diff[:cfg.MaxDiffBytes] + "\n... (diff truncated to save tokens) ...\n"
	}

	// Plus the full versions of a few changed files — the diff alone doesn't
	// always show the surrounding context (the rest of the file, neighboring
	// functions, etc.). Shares its budget with the issue flow (cfg.MaxFiles);
	// noisy files (lockfiles, binaries, vendored code) are skipped since
	// they don't add useful context.
	files, err := gh.ListPullFiles(ctx, owner, repo, number)
	if err != nil {
		return fmt.Errorf("fetching file list for PR #%d: %w", number, err)
	}
	var extraFiles []promptbuilder.FileSnippet
	budget := cfg.MaxFiles
	for _, f := range files {
		if budget <= 0 {
			break
		}
		if isLikelyNoise(f.Filename) {
			continue
		}
		content, err := gh.GetFileContent(ctx, owner, repo, f.Filename, pr.Head.SHA)
		if err != nil {
			log.Printf("PR #%d: skipping file %q: %v", number, f.Filename, err)
			continue
		}
		truncated := false
		if len(content) > cfg.MaxFileBytes {
			content = content[:cfg.MaxFileBytes]
			truncated = true
		}
		extraFiles = append(extraFiles, promptbuilder.FileSnippet{Path: f.Filename, Content: content, Truncated: truncated})
		budget--
	}
	log.Printf("PR #%d: diff %d bytes, %d files changed, %d full versions read", number, len(diff), len(files), len(extraFiles))

	// Cross-referenced issues/PRs (e.g. "fixes #42") — resolved so the model
	// can check the claim against what #42 actually says.
	refNumbers := extractIssueRefs(pr.Body, number, cfg.MaxReferencedIssues)
	references := fetchReferences(ctx, gh, owner, repo, refNumbers)
	if len(references) > 0 {
		log.Printf("PR #%d: resolved %d referenced issue(s)/PR(s)", number, len(references))
	}

	// Author attachments (screenshots/logs attached to the PR description).
	// Anything that fails automatic screening is NOT read; rejectedAtts
	// carries why, so the model judges those cases by text alone.
	imageAtts, textAtts, rejectedAtts := fetchBodyAttachments(ctx, cfg, pr.Body)

	// Laplace factor: for PRs, also checks commit git metadata (author/
	// committer email/name — noreply/actions/codex/copilot/claude patterns
	// suggest an automated pipeline rather than a human).
	factor := laplace.Compute(ctx, gh, laplace.Input{
		Owner: owner, Repo: repo, Author: author,
		AuthorType: event.PullRequest.User.Type, Body: pr.Body,
		IsPR: true, PRNumber: number,
	})
	log.Printf("PR #%d: laplace factor = %d/100 (%s), %d signals", number, factor.Score, factor.Level(), len(factor.Signals))

	userPrompt := promptbuilder.BuildPRPrompt(promptbuilder.PRInput{
		Number:         number,
		Title:          pr.Title,
		Body:           pr.Body,
		Author:         author,
		Diff:           diff,
		Files:          extraFiles,
		References:     references,
		AllowedLabels:  prLabels,
		LaplaceFactor:       factor.PromptBlock(),
		AttachedImages:      len(imageAtts),
		AttachedTexts:       textAtts,
		RejectedAttachments: rejectedAtts,
	})
	raw, err := aiClient.GenerateWithImages(ctx, p.systemPrompt(), userPrompt, imageAtts)
	if err != nil {
		return fmt.Errorf("gemini request: %w", err)
	}
	verdict, err := parseVerdict(raw)
	if err != nil {
		return fmt.Errorf("parsing gemini response: %w", err)
	}

	// Moderation takes priority over the normal label/comment flow: see
	// internal/triage/moderation.go. Note PRs can never truly be deleted
	// (no such GitHub API exists) — this redacts/closes/locks instead.
	if verdict.Moderation != nil && verdict.Moderation.Violation {
		return handleModerationViolation(ctx, cfg, gh, modGH, owner, repo, number, author, pr.Title, "", true, verdict.Moderation)
	}

	finalLabel := resolveLabel(verdict, prLabels)
	labelDef := findLabelDef(p.labels, finalLabel)
	if err := gh.EnsureLabel(ctx, owner, repo, labelDef.Name, labelDef.Color, labelDef.Description); err != nil {
		return fmt.Errorf("creating label %q: %w", labelDef.Name, err)
	}
	if err := gh.AddLabels(ctx, owner, repo, number, []string{finalLabel}); err != nil {
		return fmt.Errorf("applying label %q to PR #%d: %w", finalLabel, number, err)
	}
	comment := formatComment(verdict.Comment, verdict.Verdict, attachmentScreeningNote(rejectedAtts))
	if err := gh.CreateComment(ctx, owner, repo, number, comment); err != nil {
		return fmt.Errorf("posting comment on PR #%d: %w", number, err)
	}

	log.Printf("PR #%d processed: verdict=%s label=%s", number, verdict.Verdict, finalLabel)
	return nil
}
