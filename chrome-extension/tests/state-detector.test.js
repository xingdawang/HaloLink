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

test("does not classify the generic ChatGPT thinking spinner as working", () => {
  const result = detector.classifyActivityEvidence({
    text: "Thinking",
    ariaBusy: "true",
    hasSpinner: true,
    recentlyMutated: true
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

test("generation phases advance without regressing during DOM gaps", () => {
  let phase = detector.advanceGenerationPhase("", "THINKING");
  assert.equal(phase, "THINKING");

  phase = detector.advanceGenerationPhase(phase, "WORKING");
  assert.equal(phase, "WORKING");

  phase = detector.advanceGenerationPhase(phase, "THINKING");
  assert.equal(phase, "WORKING");

  phase = detector.advanceGenerationPhase(phase, "STREAMING");
  assert.equal(phase, "STREAMING");

  phase = detector.advanceGenerationPhase(phase, "WORKING");
  assert.equal(phase, "STREAMING");

  phase = detector.advanceGenerationPhase(phase, "THINKING");
  assert.equal(phase, "STREAMING");
});

test("a tool request emits each visible generation phase once", () => {
  const candidates = [
    "THINKING",
    "THINKING",
    "WORKING",
    "THINKING",
    "WORKING",
    "STREAMING",
    "THINKING",
    "STREAMING"
  ];
  let phase = "";
  const emitted = [];
  for (const candidate of candidates) {
    const next = detector.advanceGenerationPhase(phase, candidate);
    if (next !== phase) emitted.push(next);
    phase = next;
  }
  assert.deepEqual(emitted, ["THINKING", "WORKING", "STREAMING"]);
});

test("state changes to STREAMING when the current answer mutates", () => {
  assert.equal(detector.deriveGenerationState({
    generationActive: true,
    working: false,
    assistantChangedRecently: true
  }), "STREAMING");
});

test("state stays STREAMING after the response body has started", () => {
  assert.equal(detector.deriveGenerationState({
    generationActive: true,
    working: false,
    assistantChangedRecently: false,
    assistantResponseStarted: true
  }), "STREAMING");
});

test("a stable latched response does not keep resetting completion", () => {
  assert.equal(detector.hasOngoingGenerationActivity({
    stopVisible: false,
    working: false,
    assistantChangedRecently: false,
    assistantResponseStarted: true
  }), false);

  assert.equal(detector.hasOngoingGenerationActivity({
    stopVisible: true,
    working: false,
    assistantChangedRecently: false
  }), true);
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
