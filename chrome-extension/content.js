(() => {
  const detector = globalThis.HaloLinkDetector;
  if (!detector) return;

  const STOP_PATTERNS = [
    "stop generating", "stop response", "stop", "停止生成", "停止回答", "停止",
    "arrêter", "detener", "beenden"
  ];
  const SEND_PATTERNS = ["send message", "send", "发送消息", "发送"];
  const ERROR_PATTERNS = [
    "something went wrong", "network error", "rate limit", "error generating response",
    "an error occurred", "there was an error", "connection interrupted", "tool failed",
    "couldn't browse", "failed to read", "出了点问题", "网络错误", "请求过多",
    "发生错误", "连接已中断", "工具运行失败", "读取失败"
  ];
  const LISTENING_PATTERNS = ["listening", "正在聆听", "正在听"];
  const TURN_SELECTOR = '[data-testid^="conversation-turn"], article';
  const ASSISTANT_SELECTOR = '[data-message-author-role="assistant"]';
  const RESPONSE_BODY_SELECTOR = '.markdown, [data-testid="assistant-message-content"]';
  const ACTIVITY_SELECTOR = [
    '[role="status"]',
    '[role="progressbar"]',
    '[aria-live="polite"]',
    '[aria-live="assertive"]',
    '[aria-busy="true"]',
    '[data-state="loading"]',
    '[data-state="pending"]',
    '[data-state="running"]',
    '[data-testid*="tool"]',
    '[data-testid*="search"]',
    '[data-testid*="browse"]',
    '[data-testid*="research"]',
    '[data-testid*="progress"]',
    '[data-testid*="code"]'
  ].join(",");

  const WORKING_HOLD_MS = 650;
  const STREAMING_RECENT_MS = 1000;
  const COMPLETION_STABLE_MS = 1500;
  const RECENT_ACTIVITY_MUTATION_MS = 3000;

  let generationActive = false;
  let generationStartedAt = 0;
  let generationTurn = null;
  let currentAssistant = null;
  let lastAssistantSignature = "";
  let lastAssistantChangeAt = 0;
  let assistantResponseStarted = false;
  let lastWorkingSeenAt = 0;
  let generationPhase = "";
  let lastState = "";
  let completionTimer = null;
  let evaluateTimer = null;
  let generationSequence = 0;

  const mutationTimes = new WeakMap();
  const normalize = detector.normalize;
  const matchesAny = (text, patterns) => patterns.some(pattern => normalize(text).includes(pattern));

  function visible(el) {
    if (!(el instanceof Element)) return false;
    const style = getComputedStyle(el);
    const rect = el.getBoundingClientRect();
    return style.display !== "none" && style.visibility !== "hidden" && rect.width > 0 && rect.height > 0;
  }

  function descriptor(el) {
    if (!(el instanceof Element)) return "";
    return normalize([
      el.getAttribute("aria-label"),
      el.getAttribute("title"),
      el.getAttribute("data-testid"),
      el.getAttribute("data-state"),
      el.textContent
    ].filter(Boolean).join(" "));
  }

  function statusLabel(state) {
    return {
      READY: "Ready",
      THINKING: "Thinking...",
      WORKING: "Working...",
      STREAMING: "Responding...",
      LISTENING: "Listening...",
      COMPLETED: "Completed!",
      ERROR: "Error"
    }[state] || state;
  }

  function emit(state, reason = "", evidence = null) {
    if (state === lastState) return;
    lastState = state;
    try {
      if (!chrome.runtime?.id) return;
      chrome.runtime.sendMessage({
        type: "HALOLINK_STATUS",
        payload: {
          state,
          label: statusLabel(state),
          timestamp: new Date().toISOString(),
          reason,
          evidence: evidence ? {
            source: evidence.source || "",
            match: evidence.match || "",
            selector: evidence.selector || ""
          } : null,
          url: location.href
        }
      }).catch(() => {});
    } catch {
      // Reloading an unpacked extension invalidates the old content-script context.
    }
  }

  function allButtons() {
    return Array.from(document.querySelectorAll("button")).filter(visible);
  }

  function stopButton() {
    return allButtons().find(button =>
      button.matches('[data-testid="stop-button"], [data-testid*="stop"]') ||
      matchesAny(descriptor(button), STOP_PATTERNS)
    ) || null;
  }

  function hasStopButton() {
    return Boolean(stopButton());
  }

  function isSendControl(el) {
    const button = el?.closest?.("button");
    if (!button) return false;
    return button.matches('[data-testid="send-button"], [data-testid="composer-submit-button"], [data-testid*="send"]') ||
      matchesAny(descriptor(button), SEND_PATTERNS);
  }

  function conversationTurns() {
    return Array.from(document.querySelectorAll(TURN_SELECTOR)).filter(visible);
  }

  function latestConversationTurn() {
    const turns = conversationTurns();
    return turns.length ? turns[turns.length - 1] : null;
  }

  function lastAssistantElement() {
    const exact = Array.from(document.querySelectorAll(ASSISTANT_SELECTOR)).filter(visible);
    if (exact.length) return exact[exact.length - 1];
    return conversationTurns().reverse().find(el => matchesAny(descriptor(el), ["assistant", "chatgpt"])) || null;
  }

  function turnFor(el) {
    return el?.closest?.(TURN_SELECTOR) || null;
  }

  function assistantSignature(el) {
    if (!el) return "";
    const bodies = Array.from(el.querySelectorAll(RESPONSE_BODY_SELECTOR)).filter(visible);
    const body = bodies.length ? bodies[bodies.length - 1] : null;
    if (!body) return "";
    const text = normalize(body.innerText || body.textContent || "");
    const richNodes = body.querySelectorAll("pre, code, table, img, svg, a, blockquote").length;
    return [text.length, text.slice(-160), richNodes, body.childElementCount].join(":");
  }

  function refreshAssistantTracking(now) {
    const latest = lastAssistantElement();
    if (latest !== currentAssistant) {
      currentAssistant = latest;
      lastAssistantSignature = assistantSignature(latest);
      if (generationActive && lastAssistantSignature) {
        assistantResponseStarted = true;
        lastAssistantChangeAt = now;
      }
      const turn = turnFor(latest);
      if (turn) generationTurn = turn;
      return;
    }

    const signature = assistantSignature(latest);
    if (signature !== lastAssistantSignature) {
      lastAssistantSignature = signature;
      if (signature) {
        assistantResponseStarted = true;
        lastAssistantChangeAt = now;
      }
    }
  }

  function markMutation(node, timestamp) {
    let current = node instanceof Element ? node : node?.parentElement;
    let depth = 0;
    while (current && depth < 7) {
      mutationTimes.set(current, timestamp);
      if (current.matches?.(TURN_SELECTOR)) break;
      current = current.parentElement;
      depth += 1;
    }
  }

  function recentlyMutated(el, now) {
    return now - (mutationTimes.get(el) || 0) <= RECENT_ACTIVITY_MUTATION_MS;
  }

  function isWithinRelevantTurn(node, roots) {
    return roots.some(root => root && (root === node || root.contains(node)));
  }

  function activityEvidenceFor(node, now, scoped) {
    const result = detector.classifyActivityEvidence({
      text: node.innerText || node.textContent || "",
      ariaLabel: node.getAttribute("aria-label"),
      title: node.getAttribute("title"),
      testId: node.getAttribute("data-testid"),
      dataState: node.getAttribute("data-state"),
      className: typeof node.className === "string" ? node.className : "",
      role: node.getAttribute("role"),
      ariaLive: node.getAttribute("aria-live"),
      ariaBusy: node.getAttribute("aria-busy"),
      hasProgressbar: node.matches('[role="progressbar"]') || Boolean(node.querySelector('[role="progressbar"]')),
      hasSpinner: Boolean(node.querySelector('[class*="spin"], [class*="loading"], [data-testid*="spinner"]')),
      recentlyMutated: recentlyMutated(node, now)
    });

    if (!result.active) return null;
    if (!scoped && !recentlyMutated(node, now)) return null;

    return {
      ...result,
      selector: [
        node.getAttribute("data-testid") ? `[data-testid="${node.getAttribute("data-testid")}"]` : "",
        node.getAttribute("role") ? `[role="${node.getAttribute("role")}"]` : "",
        node.getAttribute("aria-live") ? `[aria-live="${node.getAttribute("aria-live")}"]` : ""
      ].filter(Boolean).join("") || node.tagName.toLowerCase()
    };
  }

  function detectWorking(now) {
    const latestTurn = latestConversationTurn();
    const assistantTurn = turnFor(currentAssistant);
    const roots = Array.from(new Set([generationTurn, assistantTurn, latestTurn].filter(Boolean)));
    const candidates = new Set();

    for (const root of roots) {
      if (root.matches?.(ACTIVITY_SELECTOR)) candidates.add(root);
      root.querySelectorAll?.(ACTIVITY_SELECTOR).forEach(node => candidates.add(node));
      // New ChatGPT layouts sometimes render the progress card as a sibling of the answer body.
      root.parentElement?.querySelectorAll?.(ACTIVITY_SELECTOR).forEach(node => {
        if (node === root || root.parentElement === node.parentElement || recentlyMutated(node, now)) {
          candidates.add(node);
        }
      });
    }

    document.querySelectorAll(ACTIVITY_SELECTOR).forEach(node => {
      if (recentlyMutated(node, now)) candidates.add(node);
    });

    let best = null;
    for (const node of candidates) {
      if (!visible(node)) continue;
      const scoped = isWithinRelevantTurn(node, roots);
      const evidence = activityEvidenceFor(node, now, scoped);
      if (!evidence) continue;
      if (!best || evidence.score > best.score) best = evidence;
    }
    return best;
  }

  function detectError() {
    const nodes = Array.from(document.querySelectorAll(
      '[role="alert"], [data-testid*="error"], [data-testid*="retry"], .text-red-500, .text-red-400'
    )).filter(visible);
    return nodes.some(node => matchesAny(descriptor(node), ERROR_PATTERNS));
  }

  function detectListening() {
    const nodes = Array.from(document.querySelectorAll(
      '[role="dialog"], [data-testid*="voice"], [data-testid*="dictation"]'
    )).filter(visible);
    return nodes.some(node => matchesAny(descriptor(node), LISTENING_PATTERNS));
  }

  function beginGeneration(reason) {
    if (generationActive) return;
    clearTimeout(completionTimer);
    completionTimer = null;
    generationActive = true;
    generationPhase = "THINKING";
    generationStartedAt = Date.now();
    generationSequence += 1;
    generationTurn = latestConversationTurn();
    currentAssistant = lastAssistantElement();
    lastAssistantSignature = assistantSignature(currentAssistant);
    lastAssistantChangeAt = 0;
    assistantResponseStarted = false;
    lastWorkingSeenAt = 0;
    emit("THINKING", reason, { source: "generation", match: `request-${generationSequence}` });
  }

  function cancelCompletion() {
    clearTimeout(completionTimer);
    completionTimer = null;
  }

  function scheduleCompletion(reason) {
    if (completionTimer) return;
    completionTimer = setTimeout(() => {
      completionTimer = null;
      const now = Date.now();
      refreshAssistantTracking(now);
      const working = detectWorking(now);
      const assistantChangedRecently = now - lastAssistantChangeAt < STREAMING_RECENT_MS;
      const stableForMs = now - Math.max(lastAssistantChangeAt, lastWorkingSeenAt, generationStartedAt);
      if (detector.shouldComplete({
        generationActive,
        stopVisible: hasStopButton(),
        working: Boolean(working),
        assistantChangedRecently,
        stableForMs,
        requiredStableMs: COMPLETION_STABLE_MS
      })) {
        generationActive = false;
        generationPhase = "";
        assistantResponseStarted = false;
        emit("COMPLETED", reason, { source: "stable-generation", match: `${stableForMs}ms stable` });
      } else if (generationActive && !hasStopButton()) {
        scheduleCompletion(reason);
      }
    }, COMPLETION_STABLE_MS);
  }

  function evaluate() {
    const now = Date.now();

    if (detectError()) {
      generationActive = false;
      generationPhase = "";
      assistantResponseStarted = false;
      cancelCompletion();
      emit("ERROR", "visible error alert", { source: "error-region", match: "error pattern" });
      return;
    }
    if (detectListening()) {
      cancelCompletion();
      emit("LISTENING", "voice UI detected", { source: "voice-region", match: "listening pattern" });
      return;
    }

    const stop = hasStopButton();
    if (stop && !generationActive) beginGeneration("stop control appeared");
    refreshAssistantTracking(now);

    if (generationActive || stop) {
      const workingEvidence = detectWorking(now);
      if (workingEvidence) lastWorkingSeenAt = now;
      const workingHeld = lastWorkingSeenAt > 0 && now - lastWorkingSeenAt < WORKING_HOLD_MS;
      const assistantChangedRecently = lastAssistantChangeAt > 0 && now - lastAssistantChangeAt < STREAMING_RECENT_MS;
      const candidateState = detector.deriveGenerationState({
        working: Boolean(workingEvidence) || workingHeld,
        assistantChangedRecently,
        assistantResponseStarted,
        generationActive,
        stopVisible: stop
      });
      generationPhase = detector.advanceGenerationPhase(generationPhase, candidateState);
      const state = generationPhase;
      const activityOngoing = detector.hasOngoingGenerationActivity({
        stopVisible: stop,
        working: Boolean(workingEvidence) || workingHeld,
        assistantChangedRecently
      });
      if (activityOngoing) cancelCompletion();

      if (state === "WORKING") {
        emit("WORKING", workingEvidence ? "active tool region detected" : "holding recent tool activity", workingEvidence || {
          source: "working-hold",
          match: `${now - lastWorkingSeenAt}ms since tool activity`
        });
      } else if (state === "STREAMING") {
        emit("STREAMING", "current assistant turn is changing", {
          source: "assistant-turn",
          match: `${now - lastAssistantChangeAt}ms since change`
        });
      } else {
        emit("THINKING", "generation active without current tool or response activity", {
          source: "generation",
          match: stop ? "stop control visible" : "generation session active"
        });
      }

      if (!stop) scheduleCompletion("generation became stable");
      return;
    }

    if (!lastState) emit("READY", "page initialized", { source: "page", match: "initial state" });
  }

  function scheduleEvaluate() {
    clearTimeout(evaluateTimer);
    evaluateTimer = setTimeout(evaluate, 90);
  }

  document.addEventListener("submit", event => {
    if (event.target instanceof HTMLFormElement) beginGeneration("composer form submitted");
  }, true);

  document.addEventListener("click", event => {
    if (isSendControl(event.target)) beginGeneration("send button clicked");
  }, true);

  document.addEventListener("keydown", event => {
    if (event.key !== "Enter" || event.shiftKey || event.isComposing) return;
    const composer = event.target?.closest?.('textarea, [contenteditable="true"]');
    if (composer) setTimeout(() => {
      if (hasStopButton()) beginGeneration("enter submitted composer");
    }, 40);
  }, true);

  const observer = new MutationObserver(mutations => {
    const now = Date.now();
    for (const mutation of mutations) markMutation(mutation.target, now);
    scheduleEvaluate();
  });
  observer.observe(document.documentElement, {
    subtree: true,
    childList: true,
    characterData: true,
    attributes: true,
    attributeFilter: [
      "aria-label", "aria-live", "aria-busy", "role", "data-testid", "data-state", "disabled", "class"
    ]
  });

  setInterval(evaluate, 1000);
  evaluate();
})();
