# State detector tests

Run the dependency-free regression suite from the repository root:

```bash
node --test chrome-extension/tests/state-detector.test.js
```

The tests cover tool activity rendered outside the assistant answer body, semantic progress cards, progressbar-only activity, false-positive suppression, state priority, state fallback, streaming transitions, and stable completion.
