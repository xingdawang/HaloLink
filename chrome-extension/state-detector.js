(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.HaloLinkDetector = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  const WORKING_PATTERNS = [
    "searching the web", "searching", "browsing", "opening source", "checking sources",
    "reading files", "reading file", "running code", "executing code", "using tool",
    "researching", "looking up", "fetching", "generating image", "creating image",
    "analyzing data", "search in progress", "tool in progress",
    "正在搜索网页", "正在搜索", "搜索中", "浏览网页", "正在浏览", "读取文件",
    "正在读取", "运行代码", "正在运行代码", "执行代码", "正在使用工具",
    "正在研究", "正在查找", "正在生成图片", "正在分析数据"
  ];

  const SEMANTIC_WORKING_PATTERNS = [
    "tool", "web-search", "web_search", "search-progress", "search_progress", "browse",
    "browser", "research", "code-interpreter", "code_interpreter", "python", "connector",
    "tool-progress", "tool_progress", "activity-progress", "activity_progress"
  ];

  function normalize(value) {
    return String(value || "").replace(/\s+/g, " ").trim().toLowerCase();
  }

  function firstMatch(text, patterns) {
    const normalized = normalize(text);
    return patterns.find(pattern => normalized.includes(pattern)) || "";
  }

  function classifyActivityEvidence(evidence = {}) {
    const visibleText = normalize(evidence.text);
    const semanticText = normalize([
      evidence.ariaLabel,
      evidence.title,
      evidence.testId,
      evidence.dataState,
      evidence.className
    ].filter(Boolean).join(" "));
    const role = normalize(evidence.role);
    const ariaLive = normalize(evidence.ariaLive);
    const ariaBusy = String(evidence.ariaBusy || "").toLowerCase() === "true";
    const dataState = normalize(evidence.dataState);
    const textMatch = firstMatch(visibleText, WORKING_PATTERNS);
    const semanticMatch = firstMatch(semanticText, SEMANTIC_WORKING_PATTERNS);
    const progressbar = role === "progressbar" || Boolean(evidence.hasProgressbar);
    const liveRegion = role === "status" || ariaLive === "polite" || ariaLive === "assertive";
    const activeState = ["loading", "pending", "running", "active", "busy", "streaming"]
      .some(value => dataState.includes(value));
    const animated = Boolean(evidence.hasSpinner || evidence.hasProgressbar);
    const recentlyMutated = Boolean(evidence.recentlyMutated);

    let score = 0;
    if (textMatch) score += 6;
    if (semanticMatch) score += 5;
    if (progressbar) score += 4;
    if (ariaBusy) score += 2;
    if (activeState) score += 2;
    if (liveRegion) score += 1;
    if (animated) score += 1;
    if (recentlyMutated) score += 1;

    const active = Boolean(
      textMatch ||
      semanticMatch ||
      progressbar ||
      (ariaBusy && (activeState || animated || liveRegion)) ||
      (recentlyMutated && liveRegion && animated)
    );

    let source = "";
    let match = "";
    if (textMatch) {
      source = "visible-text";
      match = textMatch;
    } else if (semanticMatch) {
      source = "semantic-attribute";
      match = semanticMatch;
    } else if (progressbar) {
      source = "progressbar";
      match = "progressbar";
    } else if (ariaBusy) {
      source = "aria-busy";
      match = "aria-busy=true";
    } else if (recentlyMutated && liveRegion && animated) {
      source = "live-region";
      match = "recent animated live region";
    }

    return { active, score, source, match };
  }

  function deriveGenerationState(input = {}) {
    if (input.error) return "ERROR";
    if (input.listening) return "LISTENING";
    if (input.working) return "WORKING";
    if (input.assistantChangedRecently) return "STREAMING";
    if (input.generationActive || input.stopVisible) return "THINKING";
    return "";
  }

  function shouldComplete(input = {}) {
    return Boolean(
      input.generationActive &&
      !input.stopVisible &&
      !input.working &&
      !input.assistantChangedRecently &&
      Number(input.stableForMs || 0) >= Number(input.requiredStableMs || 0)
    );
  }

  return {
    WORKING_PATTERNS,
    SEMANTIC_WORKING_PATTERNS,
    normalize,
    firstMatch,
    classifyActivityEvidence,
    deriveGenerationState,
    shouldComplete
  };
});
