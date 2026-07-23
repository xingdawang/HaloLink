(() => {
  const STOP_PATTERNS = [
    "stop generating", "stop response", "stop", "停止生成", "停止回答", "停止",
    "arrêter", "detener", "beenden"
  ];
  const SEND_PATTERNS = ["send message", "send", "发送消息", "发送"];
  const ERROR_PATTERNS = [
    "something went wrong", "network error", "rate limit", "error generating response",
    "an error occurred", "出了点问题", "网络错误", "请求过多", "发生错误"
  ];
  const LISTENING_PATTERNS = ["listening", "正在聆听", "正在听"];
  const WORKING_PATTERNS = [
    "searching the web", "browsing", "searching", "running code", "analyzing",
    "using tool", "reading files", "正在搜索", "浏览网页", "运行代码", "分析中", "读取文件"
  ];

  let generationActive = false;
  let sessionStartLength = 0;
  let lastAssistantLength = 0;
  let lastAssistantChangeAt = 0;
  let lastState = "";
  let completionTimer = null;
  let evaluateTimer = null;

  const normalize = value => String(value || "").replace(/\s+/g, " ").trim().toLowerCase();
  const visible = el => {
    if (!el) return false;
    const style = getComputedStyle(el);
    const rect = el.getBoundingClientRect();
    return style.display !== "none" && style.visibility !== "hidden" && rect.width > 0 && rect.height > 0;
  };
  const descriptor = el => normalize([
    el.getAttribute?.("aria-label"), el.getAttribute?.("title"),
    el.getAttribute?.("data-testid"), el.textContent
  ].filter(Boolean).join(" "));
  const matchesAny = (text, patterns) => patterns.some(pattern => text.includes(pattern));

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

  function emit(state, reason = "") {
    if (state === lastState && state !== "STREAMING") return;
    lastState = state;
    chrome.runtime.sendMessage({
      type: "HALOLINK_STATUS",
      payload: {
        state,
        label: statusLabel(state),
        timestamp: new Date().toISOString(),
        reason,
        url: location.href
      }
    }).catch(() => {});
  }

  function allButtons() {
    return Array.from(document.querySelectorAll("button")).filter(visible);
  }

  function hasStopButton() {
    return allButtons().some(button =>
      button.matches('[data-testid="stop-button"], [data-testid*="stop"]') ||
      matchesAny(descriptor(button), STOP_PATTERNS)
    );
  }

  function isSendControl(el) {
    if (!el) return false;
    const button = el.closest?.("button");
    if (!button) return false;
    return button.matches('[data-testid="send-button"], [data-testid="composer-submit-button"], [data-testid*="send"]') ||
      matchesAny(descriptor(button), SEND_PATTERNS);
  }

  function lastAssistantElement() {
    const exact = document.querySelectorAll('[data-message-author-role="assistant"]');
    if (exact.length) return exact[exact.length - 1];
    const turns = Array.from(document.querySelectorAll('article, [data-testid^="conversation-turn"]'));
    return turns.reverse().find(el => matchesAny(descriptor(el), ["assistant", "chatgpt"])) || null;
  }

  function assistantText() {
    return normalize(lastAssistantElement()?.innerText || "");
  }

  function activeRegionText() {
    const assistant = lastAssistantElement();
    if (!assistant) return "";
    return normalize(assistant.innerText || assistant.textContent || "");
  }

  function detectError() {
    const nodes = Array.from(document.querySelectorAll('[role="alert"], [data-testid*="error"], .text-red-500, .text-red-400'));
    return nodes.filter(visible).some(node => matchesAny(normalize(node.innerText || node.textContent), ERROR_PATTERNS));
  }

  function detectListening() {
    const candidates = Array.from(document.querySelectorAll('button, [role="dialog"], [aria-label], [data-testid]')).filter(visible);
    return candidates.some(node => matchesAny(descriptor(node), LISTENING_PATTERNS));
  }

  function detectWorking() {
    return matchesAny(activeRegionText(), WORKING_PATTERNS);
  }

  function beginGeneration(reason) {
    clearTimeout(completionTimer);
    generationActive = true;
    const text = assistantText();
    sessionStartLength = text.length;
    lastAssistantLength = text.length;
    lastAssistantChangeAt = Date.now();
    emit("THINKING", reason);
  }

  function finishGeneration(reason) {
    clearTimeout(completionTimer);
    completionTimer = setTimeout(() => {
      if (!hasStopButton() && generationActive) {
        generationActive = false;
        emit("COMPLETED", reason);
      }
    }, 700);
  }

  function evaluate() {
    if (detectError()) {
      generationActive = false;
      emit("ERROR", "visible error alert");
      return;
    }
    if (detectListening()) {
      emit("LISTENING", "voice UI detected");
      return;
    }

    const stop = hasStopButton();
    const text = assistantText();
    if (text.length !== lastAssistantLength) {
      lastAssistantLength = text.length;
      lastAssistantChangeAt = Date.now();
    }

    if (stop && !generationActive) beginGeneration("stop control appeared");

    if (generationActive || stop) {
      if (detectWorking()) {
        emit("WORKING", "tool activity text detected");
      } else if (text.length > sessionStartLength + 2 || Date.now() - lastAssistantChangeAt < 800) {
        emit("STREAMING", "assistant message changing");
      } else {
        emit("THINKING", "generation active before text");
      }
      if (!stop) finishGeneration("stop control disappeared");
      return;
    }

    // Do not overwrite Completed immediately; it remains visible until the next request.
    if (!lastState) emit("READY", "page initialized");
  }

  function scheduleEvaluate() {
    clearTimeout(evaluateTimer);
    evaluateTimer = setTimeout(evaluate, 90);
  }

  document.addEventListener("submit", event => {
    const target = event.target;
    if (target instanceof HTMLFormElement) beginGeneration("composer form submitted");
  }, true);

  document.addEventListener("click", event => {
    if (isSendControl(event.target)) beginGeneration("send button clicked");
  }, true);

  document.addEventListener("keydown", event => {
    if (event.key !== "Enter" || event.shiftKey || event.isComposing) return;
    const target = event.target;
    const composer = target?.closest?.('textarea, [contenteditable="true"]');
    if (composer) setTimeout(() => {
      if (hasStopButton()) beginGeneration("enter submitted composer");
    }, 40);
  }, true);

  const observer = new MutationObserver(scheduleEvaluate);
  observer.observe(document.documentElement, {
    subtree: true, childList: true, characterData: true,
    attributes: true, attributeFilter: ["aria-label", "data-testid", "disabled"]
  });

  setInterval(evaluate, 1200);
  evaluate();
})();
