// Package model contains data structures shared across the bot's packages.
package model

// LabelDef describes one label from .github/ai-triage/labels.json.
type LabelDef struct {
	Name        string   `json:"name"`
	Color       string   `json:"color"`       // hex without "#", e.g. "d73a4a"
	Description string   `json:"description"` // shown to the AI and in the GitHub UI
	AppliesTo   []string `json:"applies_to"`  // "issue" and/or "pull_request"
}

// LabelsConfig is the root of labels.json.
type LabelsConfig struct {
	Labels []LabelDef `json:"labels"`
}

// AIVerdict is exactly what the model must return as JSON.
type AIVerdict struct {
	Verdict    string          `json:"verdict"`               // "trash" | "valid"
	Label      string          `json:"label"`                 // a label name from the allowed list (or "trash")
	Comment    string          `json:"comment"`                // short comment for the issue/PR
	Moderation *ModerationFlag `json:"moderation,omitempty"` // doxxing/abuse verdict, independent of the above
}

// ModerationFlag is the model's judgment on whether the issue/PR content
// ITSELF is a moderation violation (personal-data exposure or severe
// targeted abuse) — as opposed to "trash", which is a quality verdict. The
// two are independent: a well-formed, substantive bug report can still
// doxx someone, and junk text can be abusive without containing anyone's
// personal data. See internal/triage/moderation.go for what happens when
// Violation is true.
type ModerationFlag struct {
	Violation bool   `json:"violation"`
	Kind      string `json:"kind"`   // "doxxing" | "abuse" | "both" | "" (empty when Violation is false)
	Reason    string `json:"reason"` // one factual sentence, English, without repeating the exposed data/slurs verbatim
}
