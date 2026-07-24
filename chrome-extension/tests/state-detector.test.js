const test = require("node:test");
const assert = require("node:assert/strict");
const detector = require("../state-detector.js");

test("detects web-search text rendered outside the answer body", () => {
  const result = detector.classifyActivityEvidence({
    text: "Searching the web",
    role: "status",
    ariaLive: "polite",
    recentlyMutated: true
  });
  assert.equal(result.active, true);
  assert.equal(result.source, "visible-text");
});

test("detects semantic tool cards without visible text", () => {
  const result = detector.classifyActivityEvidence({
    testId: "web-search-progress",
    dataState: "running",
    ariaBusy: "true"
  });
  assert.equal(result.active, true);
  assert.equal(result.source, "semantic-attribute");
});

test("detects a progressbar-only tool activity region", () => {
  const result = detector.classifyActivityEvidence({ role: "progressbar" });
  assert.equal(result.active, true);
  assert.equal(result.source, "progressbar");
});

test("does not classify a generic stable live region as working", () => {
  const result = detector.classifyActivityEvidence({
    text: "ChatGPT can make mistakes. Check important info.",
    role: "status",
    ariaLive: "polite",
    recentlyMutated: false
  });
  assert.equal(result.active, false);
});

test("state priority keeps WORKING above STREAMING", () => {
  assert.equal(detector.deriveGenerationState({
    generationActive: true,
    working: true,
    assistantChangedRecently: true
  }), "WORKING");
});

test("state can return from WORKING to THINKING before text resumes", () => {
  assert.equal(detector.deriveGenerationState({
    generationActive: true,
    working: false,
    assistantChangedRecently: false
  }), "THINKING");
});

test("state changes to STREAMING when the current answer mutates", () => {
  assert.equal(detector.deriveGenerationState({
    generationActive: true,
    working: false,
    assistantChangedRecently: true
  }), "STREAMING");
});

test("completion requires no stop, no tool activity, and a stable answer", () => {
  assert.equal(detector.shouldComplete({
    generationActive: true,
    stopVisible: false,
    working: false,
    assistantChangedRecently: false,
    stableForMs: 1600,
    requiredStableMs: 1500
  }), true);

  assert.equal(detector.shouldComplete({
    generationActive: true,
    stopVisible: false,
    working: true,
    assistantChangedRecently: false,
    stableForMs: 2000,
    requiredStableMs: 1500
  }), false);
});
