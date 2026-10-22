// Package config reads the bot's configuration from environment variables,
// which action.yml passes in.
package config

import (
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config holds everything needed for one bot run (one event = one run).
type Config struct {
	GitHubToken   string
	GeminiAPIKeys []string
	GeminiModel   string

	Workspace        string // root of the checked-out repo (GITHUB_WORKSPACE)
	RepoContextPath  string // system prompt #1 (repo description)
	InstructionsPath string // system prompt #2 (instructions/tone)
	TemplatesPath    string // system prompt #3 (optional canned patterns for recurring issue/PR shapes)
	LabelsConfigPath string // config of allowed labels

	SpamWindow    time.Duration
	SpamThreshold int

	MaxFiles     int
	MaxFileBytes int
	MaxDiffBytes int

	// MaxExtractedPaths guards against issue bodies that list an excessive
	// number of file paths purely to make the bot hammer the GitHub API
	// (each path is a separate Contents API call). Above this count the
	// issue is treated as abuse and labeled without ever calling the AI.
	MaxExtractedPaths int

	// MaxReferencedIssues caps how many other issues/PRs (referenced in the
	// body as "#123") the bot will resolve and pass as context per run. Like
	// MaxExtractedPaths, this exists so a body can't force an unbounded
	// number of extra GitHub API calls just by listing "#1 #2 #3 ...".
	// 0 disables reference resolution entirely.
	MaxReferencedIssues int

	// MaxAPICalls is a hard ceiling on GitHub API calls for a single run —
	// a circuit breaker. If something drives the bot into making far more
	// requests than a normal run needs (a bug, a crafted payload, an
	// unbounded retry loop), the client starts refusing further calls and
	// the run aborts without posting anything.
	MaxAPICalls int

	// Attachments from the issue/PR body (screenshots and text files the
	// user drag-and-dropped in). See internal/attachments.
	MaxAttachments         int   // max attachments processed per run
	MaxAttachmentBytes     int64 // max size of a SINGLE attachment
	MaxAttachmentTotal     int64 // total byte budget for all attachments in one run
	AttachmentFetchTimeout time.Duration

	// Moderation: automatic removal of doxxing/severe-abuse content, with
	// escalation to an account block on a repeat offense by the same
	// author. GITHUB_TOKEN alone can only redact+close+lock content (see
	// internal/triage/moderation.go for exactly why) — ModerationToken
	// (optional) is a separate, more privileged PAT that unlocks real
	// issue deletion and real user blocking. Leaving it empty is a
	// perfectly normal, safe configuration, not a partially-broken one.
	ModerationToken     string
	ModerationStatePath string // where the per-author offense-count JSON file lives in the repo
	ModerationLogIssue  int    // issue number to post an audit-trail comment on for every moderation action (0 = disabled)

	EventName string
	EventPath string

	RepoOwner string
	RepoName  string
}

// Load reads and validates the configuration. Every parameter except the
// tokens has a sensible default.
func Load() (*Config, error) {
	c := &Config{}

	c.GitHubToken = os.Getenv("GITHUB_TOKEN")
	if c.GitHubToken == "" {
		return nil, errors.New("GITHUB_TOKEN is not set")
	}

	keysRaw := os.Getenv("GEMINI_API_KEYS")
	if strings.TrimSpace(keysRaw) == "" {
		return nil, errors.New("GEMINI_API_KEYS is not set (need at least 1 key, up to 100 separated by commas/newlines)")
	}
	c.GeminiAPIKeys = splitKeys(keysRaw)
	if len(c.GeminiAPIKeys) == 0 {
		return nil, errors.New("GEMINI_API_KEYS did not contain any valid keys")
	}
	if len(c.GeminiAPIKeys) > 100 {
		c.GeminiAPIKeys = c.GeminiAPIKeys[:100]
	}

	c.GeminiModel = envDefault("GEMINI_MODEL", "gemini-3.5-flash-lite")
	c.Workspace = envDefault("GITHUB_WORKSPACE", ".")
	c.RepoContextPath = envDefault("REPO_CONTEXT_PATH", ".github/ai-triage/repo_context.md")
	c.InstructionsPath = envDefault("INSTRUCTIONS_PATH", ".github/ai-triage/instructions.md")
	c.TemplatesPath = envDefault("TEMPLATES_PATH", ".github/ai-triage/templates.md")
	c.LabelsConfigPath = envDefault("LABELS_CONFIG_PATH", ".github/ai-triage/labels.json")

	windowMin, err := strconv.Atoi(envDefault("SPAM_WINDOW_MINUTES", "60"))
	if err != nil {
		return nil, fmt.Errorf("SPAM_WINDOW_MINUTES: %w", err)
	}
	c.SpamWindow = time.Duration(windowMin) * time.Minute

	c.SpamThreshold, err = strconv.Atoi(envDefault("SPAM_THRESHOLD", "5"))
	if err != nil {
		return nil, fmt.Errorf("SPAM_THRESHOLD: %w", err)
	}

	c.MaxFiles, err = strconv.Atoi(envDefault("MAX_FILES", "4"))
	if err != nil {
		return nil, fmt.Errorf("MAX_FILES: %w", err)
	}
	if c.MaxFiles < 1 {
		c.MaxFiles = 1
	}

	c.MaxFileBytes, err = strconv.Atoi(envDefault("MAX_FILE_BYTES", "20000"))
	if err != nil {
		return nil, fmt.Errorf("MAX_FILE_BYTES: %w", err)
	}

	c.MaxDiffBytes, err = strconv.Atoi(envDefault("MAX_DIFF_BYTES", "30000"))
	if err != nil {
		return nil, fmt.Errorf("MAX_DIFF_BYTES: %w", err)
	}

	c.MaxExtractedPaths, err = strconv.Atoi(envDefault("MAX_EXTRACTED_PATHS", "30"))
	if err != nil {
		return nil, fmt.Errorf("MAX_EXTRACTED_PATHS: %w", err)
	}
	if c.MaxExtractedPaths < 1 {
		c.MaxExtractedPaths = 1
	}

	c.MaxReferencedIssues, err = strconv.Atoi(envDefault("MAX_REFERENCED_ISSUES", "5"))
	if err != nil {
		return nil, fmt.Errorf("MAX_REFERENCED_ISSUES: %w", err)
	}
	if c.MaxReferencedIssues < 0 {
		c.MaxReferencedIssues = 0
	}

	c.MaxAPICalls, err = strconv.Atoi(envDefault("MAX_API_CALLS", "60"))
	if err != nil {
		return nil, fmt.Errorf("MAX_API_CALLS: %w", err)
	}
	if c.MaxAPICalls < 1 {
		c.MaxAPICalls = 1
	}

	c.MaxAttachments, err = strconv.Atoi(envDefault("MAX_ATTACHMENTS", "4"))
	if err != nil {
		return nil, fmt.Errorf("MAX_ATTACHMENTS: %w", err)
	}
	if c.MaxAttachments < 0 {
		c.MaxAttachments = 0
	}

	maxAttBytes, err := strconv.ParseInt(envDefault("MAX_ATTACHMENT_BYTES", "8000000"), 10, 64)
	if err != nil {
		return nil, fmt.Errorf("MAX_ATTACHMENT_BYTES: %w", err)
	}
	c.MaxAttachmentBytes = maxAttBytes

	maxAttTotal, err := strconv.ParseInt(envDefault("MAX_ATTACHMENT_TOTAL_BYTES", "20000000"), 10, 64)
	if err != nil {
		return nil, fmt.Errorf("MAX_ATTACHMENT_TOTAL_BYTES: %w", err)
	}
	c.MaxAttachmentTotal = maxAttTotal

	attTimeoutSec, err := strconv.Atoi(envDefault("ATTACHMENT_FETCH_TIMEOUT_SECONDS", "15"))
	if err != nil {
		return nil, fmt.Errorf("ATTACHMENT_FETCH_TIMEOUT_SECONDS: %w", err)
	}
	c.AttachmentFetchTimeout = time.Duration(attTimeoutSec) * time.Second

	// MODERATION_TOKEN is deliberately allowed to be empty — see the doc
	// comment on Config.ModerationToken. No validation beyond that: if
	// it's set but insufficiently privileged, individual API calls fail
	// at call time and the bot logs that and degrades gracefully, rather
	// than refusing to start.
	c.ModerationToken = os.Getenv("MODERATION_TOKEN")
	c.ModerationStatePath = envDefault("MODERATION_STATE_PATH", ".github/ai-triage/moderation-state.json")

	c.ModerationLogIssue, err = strconv.Atoi(envDefault("MODERATION_LOG_ISSUE", "0"))
	if err != nil {
		return nil, fmt.Errorf("MODERATION_LOG_ISSUE: %w", err)
	}
	if c.ModerationLogIssue < 0 {
		c.ModerationLogIssue = 0
	}

	c.EventName = os.Getenv("GITHUB_EVENT_NAME")
	c.EventPath = os.Getenv("GITHUB_EVENT_PATH")
	if c.EventName == "" || c.EventPath == "" {
		return nil, errors.New("GITHUB_EVENT_NAME/GITHUB_EVENT_PATH are not set — the bot is only meant to run inside GitHub Actions")
	}

	// Optional override for non-standard runners.
	c.RepoOwner = os.Getenv("REPO_OWNER_OVERRIDE")
	c.RepoName = os.Getenv("REPO_NAME_OVERRIDE")

	return c, nil
}

func splitKeys(raw string) []string {
	fields := strings.FieldsFunc(raw, func(r rune) bool {
		switch r {
		case ',', ';', '\n', '\r', '\t', ' ':
			return true
		}
		return false
	})
	seen := make(map[string]bool, len(fields))
	out := make([]string, 0, len(fields))
	for _, f := range fields {
		f = strings.TrimSpace(f)
		if f == "" || seen[f] {
			continue
		}
		seen[f] = true
		out = append(out, f)
	}
	return out
}

func envDefault(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
