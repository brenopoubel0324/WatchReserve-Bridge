// Package ai wraps the official Google Gen AI Go SDK
// (google.golang.org/genai) with support for a pool of 1..100 API keys: if
// the current key hits a rate limit / transient error, the bot transparently
// switches to the next one.
package ai

import (
	"context"
	"errors"
	"fmt"
	"log"
	"strings"
	"time"

	"google.golang.org/genai"
)

// Client is a Gemini client with key rotation.
type Client struct {
	keys  []string
	model string
	idx   int // index of the key to start the next request with
}

// ImagePart is a single screenshot attached as multimodal input alongside
// the text prompt. The data has already been validated and bounded by
// internal/attachments — only bytes whose signature actually matches an
// image end up here.
type ImagePart struct {
	MIMEType string
	Data     []byte
}

// New creates a client. keys is a list of 1..100 API keys, model is the
// model id, e.g. "gemini-3.5-flash-lite".
func New(keys []string, model string) *Client {
	return &Client{keys: keys, model: model}
}

// Generate sends the system prompt + user prompt and asks the model to
// respond with strict JSON (responseMimeType=application/json).
// On a rate-limit/transient error it automatically tries the next key in
// the pool. This is a thin wrapper over GenerateWithImages with no
// attachments, kept for callers that don't need screenshots.
func (c *Client) Generate(ctx context.Context, systemPrompt, userPrompt string) (string, error) {
	return c.GenerateWithImages(ctx, systemPrompt, userPrompt, nil)
}

// GenerateWithImages is the same as Generate, but also passes the model any
// screenshots (multimodal input) the user attached to the issue/PR.
// gemini-3.5-flash-lite and the rest of the Gemini 3.x line accept images
// natively in the same request as text — no separate "recognize this image"
// call is needed.
func (c *Client) GenerateWithImages(ctx context.Context, systemPrompt, userPrompt string, images []ImagePart) (string, error) {
	if len(c.keys) == 0 {
		return "", errors.New("gemini key pool is empty")
	}

	var lastErr error
	for attempt := 0; attempt < len(c.keys); attempt++ {
		keyIdx := (c.idx + attempt) % len(c.keys)
		text, err := c.tryOnce(ctx, c.keys[keyIdx], systemPrompt, userPrompt, images)
		if err == nil {
			c.idx = keyIdx // next call starts with this same working key
			return text, nil
		}
		lastErr = err
		if !isRateLimitOrTransient(err) {
			// Not a rate limit (e.g. an invalid request) — burning through
			// the rest of the keys would just repeat the same error.
			return "", err
		}
		log.Printf("gemini: key #%d unavailable (rate limit/transient error), trying the next one: %v", keyIdx, err)
	}

	return "", fmt.Errorf("all %d gemini keys exhausted/unavailable: %w", len(c.keys), lastErr)
}

func (c *Client) tryOnce(ctx context.Context, apiKey, systemPrompt, userPrompt string, images []ImagePart) (string, error) {
	cctx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()

	client, err := genai.NewClient(cctx, &genai.ClientConfig{
		APIKey:  apiKey,
		Backend: genai.BackendGeminiAPI,
	})
	if err != nil {
		return "", fmt.Errorf("creating genai client: %w", err)
	}

	cfg := &genai.GenerateContentConfig{
		SystemInstruction: genai.NewContentFromText(systemPrompt, genai.RoleUser),
		ResponseMIMEType:  "application/json",
		MaxOutputTokens:   2048,
		// Temperature/TopP/TopK are intentionally left unset: for the
		// Gemini 3.x line, Google recommends not overriding the sampling
		// defaults — determinism here comes from the system prompt's
		// instructions, not from sampling parameters.
	}

	parts := make([]*genai.Part, 0, len(images)+1)
	parts = append(parts, genai.NewPartFromText(userPrompt))
	for _, img := range images {
		parts = append(parts, genai.NewPartFromBytes(img.Data, img.MIMEType))
	}
	contents := []*genai.Content{genai.NewContentFromParts(parts, genai.RoleUser)}

	resp, err := client.Models.GenerateContent(cctx, c.model, contents, cfg)
	if err != nil {
		return "", err
	}

	text := resp.Text()
	if strings.TrimSpace(text) == "" {
		return "", errors.New("model returned an empty response")
	}
	return text, nil
}

// isRateLimitOrTransient is a rough but practical check of whether the
// error text suggests a retry with the next key is worth it (429/quota/
// unavailable), versus a substantive error a retry won't fix.
func isRateLimitOrTransient(err error) bool {
	msg := strings.ToLower(err.Error())
	needles := []string{
		"429", "resource_exhausted", "rate limit", "quota",
		"unavailable", "503", "internal error", "500", "deadline exceeded",
	}
	for _, n := range needles {
		if strings.Contains(msg, n) {
			return true
		}
	}
	return false
}
