// Package promptbuilder assembles the final user prompt from an issue's
// title/body or a PR's diff, the repository files that were read, and the
// list of allowed labels.
package promptbuilder

import (
	"fmt"
	"strings"

	"aitriage/internal/model"
)

// FileSnippet is one file that was read (or a truncated piece of it) for context.
type FileSnippet struct {
	Path      string
	Content   string
	Truncated bool
}

// ReferenceSnippet is another issue/PR in the same repo that the author
// pointed to (e.g. "duplicate of #42", "fixes #17"), fetched so the model
// can actually check the claim instead of trusting the bare number.
type ReferenceSnippet struct {
	Number    int
	Title     string
	Body      string
	State     string
	IsPR      bool
	Truncated bool
}

// RejectedAttachment is a link the author included that was found but
// FAILED automatic screening (wrong host, too big, looked like an
// executable, unsupported type, ...) and was therefore never downloaded or
// read. This is passed to the model explicitly — a lower attachment count
// must never be indistinguishable from "the author attached nothing" (see
// writeAttachmentsSection and internal/triage/common.go's
// attachmentScreeningNote, which makes the same disclosure in the posted
// comment, deterministically, regardless of what the model says).
type RejectedAttachment struct {
	URL    string
	Reason string
}

// IssueInput is everything needed for an issue prompt.
type IssueInput struct {
	Number              int
	Title               string
	Body                string
	Author              string
	Files               []FileSnippet
	FailedFiles         []string // paths the author cited that could NOT be read (typo, wrong branch, missing)
	References          []ReferenceSnippet
	AllowedLabels       []model.LabelDef
	LaplaceFactor       string // from internal/laplace, Factor.PromptBlock() — heuristic context, not fact
	AttachedImages      int    // how many screenshots were passed separately as multimodal input
	AttachedTexts       []FileSnippet
	RejectedAttachments []RejectedAttachment
}

// PRInput is everything needed for a pull request prompt.
type PRInput struct {
	Number              int
	Title               string
	Body                string
	Author              string
	Diff                string
	Files               []FileSnippet
	References          []ReferenceSnippet
	AllowedLabels       []model.LabelDef
	LaplaceFactor       string
	AttachedImages      int
	AttachedTexts       []FileSnippet
	RejectedAttachments []RejectedAttachment
}

// evidenceReminder is repeated right before the JSON-format instructions
// (i.e. last thing the model reads before answering) as a second, more
// forceful pass at the same rule from instructions.md — a plausible-sounding
// claim is not evidence, and a missing/unread file is not silence to fill
// in with the author's version of events.
const evidenceReminder = "## Reminder before you answer\nOnly confirm a code-level claim (\"bug\", or treating a technical detail as true) if you can name a specific file from a \"Files read\" section above (or, for PRs, a specific line in the diff) and what in it supports the claim. A file the author cited but that's missing from \"Files read\" — or no files at all — means you cannot confirm it: say so explicitly and prefer \"needs-info\" (or \"invalid\" if the report is otherwise clearly mistaken) over taking the description at face value. A confident or detailed description is not, by itself, evidence.\n\n"

const responseFormatBlock = `## Response format
Return STRICTLY valid JSON and nothing else — no markdown wrapper, no explanation before or after. The "label" field must be one of the label names above, or "trash" if verdict="trash". The "moderation" object is REQUIRED on every response (see the "Moderation" policy above): set "violation": true ONLY for genuine doxxing/personal-data exposure or severe targeted abuse, with "kind" as "doxxing", "abuse", or "both", and "reason" as one factual English sentence that does NOT repeat the exposed data or slurs verbatim. Otherwise "violation": false with "kind" and "reason" left as empty strings. Example structures (the values below are illustration only, don't copy them):
{"verdict": "trash", "label": "trash", "comment": "Spam/ads, unrelated to the repository.", "moderation": {"violation": false, "kind": "", "reason": ""}}
or
{"verdict": "valid", "label": "bug", "comment": "Confirmed in internal/storage/save.go: there's no nil check, so it panics on an empty input.", "moderation": {"violation": false, "kind": "", "reason": ""}}
or, only in a genuine moderation case:
{"verdict": "trash", "label": "trash", "comment": "Off-topic and contains a policy violation, see below.", "moderation": {"violation": true, "kind": "doxxing", "reason": "The body publishes another contributor's home address and personal phone number."}}

Always write the "comment" and "moderation.reason" fields in English, regardless of what language the issue/PR itself is written in.`

// BuildIssuePrompt builds the prompt for triaging an issue.
func BuildIssuePrompt(in IssueInput) string {
	var b strings.Builder

	b.WriteString("# Task: Triage a GitHub Issue\n\n")
	fmt.Fprintf(&b, "Issue #%d, author @%s\n\n", in.Number, in.Author)
	fmt.Fprintf(&b, "## Title\n%s\n\n", orNA(in.Title))
	fmt.Fprintf(&b, "## Issue body\n%s\n\n", orNA(in.Body))

	writeFilesSection(&b, in.Files, "no files were successfully read — either the author didn't cite one, or the cited path doesn't exist / is wrong. This is NOT license to judge the claim from the text alone: if confirming it requires code you don't have, say so and prefer \"needs-info\" over trusting the description.")
	writeFailedFilesSection(&b, in.FailedFiles)
	writeReferencesSection(&b, in.References)
	writeAttachmentsSection(&b, in.AttachedImages, in.AttachedTexts, in.RejectedAttachments)
	writeLaplaceSection(&b, in.LaplaceFactor)
	writeLabelsSection(&b, in.AllowedLabels)

	b.WriteString("\n## What to do\n")
	b.WriteString("1. Decide whether this issue has real content or is junk (spam, ads, abuse, meaningless text, obviously bot-generated with no connection to the repo, a completely empty description).\n")
	b.WriteString("2. If it has real content, check the claimed problem against the files and repo context you were given: is this a genuine bug/reasonable request, or is the author mistaken/making things up?\n")
	b.WriteString("3. Pick ONE label from the list above (except trash, which is applied by a separate rule).\n")
	b.WriteString("4. Write a short comment with your diagnosis — why this verdict.\n\n")

	b.WriteString(evidenceReminder)
	b.WriteString(responseFormatBlock)
	return b.String()
}

// BuildPRPrompt builds the prompt for triaging a pull request.
func BuildPRPrompt(in PRInput) string {
	var b strings.Builder

	b.WriteString("# Task: Triage a GitHub Pull Request\n\n")
	fmt.Fprintf(&b, "PR #%d, author @%s\n\n", in.Number, in.Author)
	fmt.Fprintf(&b, "## Title\n%s\n\n", orNA(in.Title))
	fmt.Fprintf(&b, "## PR description\n%s\n\n", orNA(in.Body))

	b.WriteString("## Diff\n```diff\n")
	b.WriteString(orNA(in.Diff))
	b.WriteString("\n```\n\n")

	writeFilesSection(&b, in.Files, "no additional full files were read beyond the diff above — base your assessment on the diff itself, and if it doesn't show enough surrounding context to confirm or refute a specific claim, say so rather than guessing")
	writeReferencesSection(&b, in.References)
	writeAttachmentsSection(&b, in.AttachedImages, in.AttachedTexts, in.RejectedAttachments)
	writeLaplaceSection(&b, in.LaplaceFactor)
	writeLabelsSection(&b, in.AllowedLabels)

	b.WriteString("\n## What to do\n")
	b.WriteString("1. Decide whether this is a meaningful PR or junk (empty/meaningless diff, unrelated to the repo, obvious vandalism, generated noise).\n")
	b.WriteString("2. If it has real content, assess quality: does it solve the stated problem, does it follow the repo's conventions, are there obvious issues in the diff?\n")
	b.WriteString("3. Pick ONE label from the list above (except trash, which is applied by a separate rule).\n")
	b.WriteString("4. Write a short comment with your diagnosis.\n\n")

	b.WriteString(evidenceReminder)
	b.WriteString(responseFormatBlock)
	return b.String()
}

func writeFilesSection(b *strings.Builder, files []FileSnippet, emptyNote string) {
	if len(files) == 0 {
		fmt.Fprintf(b, "## Files read from the repository\n(%s)\n\n", emptyNote)
		return
	}
	b.WriteString("## Files read from the repository\n")
	for _, f := range files {
		suffix := ""
		if f.Truncated {
			suffix = " (truncated, only part is shown)"
		}
		fmt.Fprintf(b, "\n### %s%s\n```\n%s\n```\n", f.Path, suffix, f.Content)
	}
	b.WriteString("\n")
}

// writeFailedFilesSection lists paths the author cited in the text that the
// bot tried and failed to read (typo, wrong branch, deleted file). This is
// deliberately surfaced as its own section rather than silently folded into
// "no files were read" — the model needs to know a citation existed and
// specifically could not be verified, not just that the section is empty.
func writeFailedFilesSection(b *strings.Builder, failed []string) {
	if len(failed) == 0 {
		return
	}
	b.WriteString("## Files cited by the author that could NOT be read\n")
	b.WriteString("These paths were referenced in the text, but the bot could not fetch their content (typo, wrong branch, or the file doesn't exist in the repo). You have NOT seen what these files actually contain — do not treat the author's description of them as confirmed.\n")
	for _, p := range failed {
		fmt.Fprintf(b, "- `%s`\n", p)
	}
	b.WriteString("\n")
}

// writeReferencesSection includes other issues/PRs the author pointed to
// (e.g. "duplicate of #42"), fetched so the model can check the claim
// against their actual content instead of trusting the bare reference.
func writeReferencesSection(b *strings.Builder, refs []ReferenceSnippet) {
	if len(refs) == 0 {
		return
	}
	b.WriteString("## Other issues/PRs referenced in the text\n")
	b.WriteString("Fetched because the author pointed to them (e.g. \"#42\", \"duplicate of #17\", \"fixes #99\"). Treat this as context to verify, not as automatic confirmation — check that it actually says what the author claims before relying on it.\n")
	for _, r := range refs {
		kind := "Issue"
		if r.IsPR {
			kind = "PR"
		}
		suffix := ""
		if r.Truncated {
			suffix = " (truncated)"
		}
		fmt.Fprintf(b, "\n### %s #%d — %s (state: %s)%s\n%s\n", kind, r.Number, orNA(r.Title), orNA(r.State), suffix, orNA(r.Body))
	}
	b.WriteString("\n")
}

func writeLabelsSection(b *strings.Builder, labels []model.LabelDef) {
	b.WriteString("## Available labels\n")
	if len(labels) == 0 {
		b.WriteString("(list is empty — use the \"needs-triage\" label in this case)\n")
		return
	}
	for _, l := range labels {
		fmt.Fprintf(b, "- %s — %s\n", l.Name, l.Description)
	}
}

// writeAttachmentsSection describes the attachments the author included on
// the issue/PR (screenshots, log files). Images are NOT inlined into the
// prompt text — they're passed separately as multimodal input (see
// ai.GenerateWithImages); this section only states the count so the model
// knows to look at them. rejected lists attachments that were found but
// failed automatic screening and were therefore never downloaded/read —
// the model must judge those specific claims on text alone, not assume
// nothing was attached (see internal/attachments.SkipReason and
// internal/triage/common.go's attachmentScreeningNote, which makes the
// same disclosure in the posted comment regardless of what the model does
// with this).
func writeAttachmentsSection(b *strings.Builder, imageCount int, texts []FileSnippet, rejected []RejectedAttachment) {
	if imageCount == 0 && len(texts) == 0 && len(rejected) == 0 {
		return
	}
	b.WriteString("## Attachments from the author\n")
	if imageCount > 0 {
		fmt.Fprintf(b, "%d screenshot(s) passed automatic screening and are attached to this request — passed to you as separate message parts (multimodal input). Look at them: they may confirm or contradict the claimed problem.\n", imageCount)
	}
	for _, t := range texts {
		fmt.Fprintf(b, "\n### Attached file: %s\n```\n%s\n```\n", t.Path, t.Content)
	}
	if len(rejected) > 0 {
		fmt.Fprintf(b, "\n%d attachment(s) FAILED automatic screening and were NOT downloaded or shown to you in any form — do not guess their content, do not assume they support or contradict the claim, and do not penalize the author for something you can't see. Judge the affected part(s) of the issue/PR on the surrounding text alone. Reasons:\n", len(rejected))
		for _, r := range rejected {
			fmt.Fprintf(b, "- %s\n", r.Reason)
		}
	}
	b.WriteString("\n")
}

// writeLaplaceSection inserts the heuristic "Laplace factor" — context about
// the likely automated/AI origin of the submission. block is already fully
// formatted by internal/laplace (including the mandatory "this isn't fact"
// caveat), so it's inserted as-is here.
func writeLaplaceSection(b *strings.Builder, block string) {
	if strings.TrimSpace(block) == "" {
		return
	}
	b.WriteString("## Laplace factor (context about the author, NOT grounds for a verdict by itself)\n")
	b.WriteString(block)
	b.WriteString("\n")
}

func orNA(s string) string {
	if strings.TrimSpace(s) == "" {
		return "(empty)"
	}
	return s
}
