# DOM fixture plan

Real ChatGPT DOM snapshots should be sanitized before they are committed. Keep only the active conversation turn, remove user content, and preserve semantic attributes such as `role`, `aria-live`, `aria-busy`, `data-state`, and `data-testid`.

Recommended fixtures:

- `thinking.html`
- `web-search.html`
- `code-running.html`
- `streaming.html`
- `completed.html`
- `error.html`
