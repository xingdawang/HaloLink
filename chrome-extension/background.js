const PORT_START = 8766;
const PORT_END = 8775;
const EXTENSION_VERSION = "0.1.4";

let socket = null;
let retryTimer = null;
let keepAliveTimer = null;
let connectAttempt = 0;
let lastStatus = { state: "READY", label: "Ready", timestamp: new Date().toISOString() };
let bridgeConnected = false;
let bridgePort = null;
let bridgeInfo = null;
let scanning = false;

function saveSnapshot() {
  chrome.storage.local.set({ bridgeConnected, bridgePort, bridgeInfo, lastStatus });
}

function closeCurrentSocket() {
  if (socket) {
    try { socket.close(); } catch (_) { }
  }
  socket = null;
  clearInterval(keepAliveTimer);
}

function scheduleReconnect(delay = 1500) {
  clearTimeout(retryTimer);
  retryTimer = setTimeout(connect, delay);
}

async function probePort(port) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 450);
  try {
    const response = await fetch(`http://127.0.0.1:${port}/health`, {
      cache: "no-store",
      signal: controller.signal
    });
    if (!response.ok) return null;
    const payload = await response.json();
    if (
      payload?.ok !== true ||
      payload?.product !== "HaloLink" ||
      typeof payload?.version !== "string" ||
      !Number.isInteger(payload?.pid) ||
      payload?.port !== port
    ) return null;
    return payload;
  } catch (_) {
    return null;
  } finally {
    clearTimeout(timeout);
  }
}

async function discoverBridgePort() {
  if (bridgePort) {
    const payload = await probePort(bridgePort);
    if (payload) return { port: bridgePort, payload };
  }
  for (let port = PORT_START; port <= PORT_END; port += 1) {
    const payload = await probePort(port);
    if (payload) return { port, payload };
  }
  return null;
}

async function connect() {
  if (scanning) return;
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) return;
  scanning = true;
  clearTimeout(retryTimer);
  const discovered = await discoverBridgePort();
  scanning = false;

  if (!discovered) {
    bridgeConnected = false;
    bridgePort = null;
    bridgeInfo = null;
    saveSnapshot();
    scheduleReconnect(Math.min(1500 + connectAttempt * 500, 8000));
    connectAttempt += 1;
    return;
  }

  bridgePort = discovered.port;
  bridgeInfo = {
    pid: discovered.payload.pid,
    port: discovered.payload.port,
    version: discovered.payload.version,
    state: discovered.payload.state,
    phoneClients: discovered.payload.phoneClients
  };
  const url = `ws://127.0.0.1:${bridgePort}/ws/browser`;
  try {
    socket = new WebSocket(url);
  } catch (_) {
    bridgeConnected = false;
    saveSnapshot();
    scheduleReconnect();
    return;
  }

  socket.addEventListener("open", () => {
    bridgeConnected = true;
    connectAttempt = 0;
    saveSnapshot();
    sendRaw({ type: "hello", role: "browser-extension", version: EXTENSION_VERSION });
    sendStatus(lastStatus);
    clearInterval(keepAliveTimer);
    keepAliveTimer = setInterval(() => {
      sendRaw({ type: "ping", timestamp: new Date().toISOString() });
    }, 20000);
  });

  socket.addEventListener("close", () => {
    bridgeConnected = false;
    socket = null;
    clearInterval(keepAliveTimer);
    saveSnapshot();
    scheduleReconnect();
  });

  socket.addEventListener("error", () => {
    bridgeConnected = false;
    saveSnapshot();
  });

  socket.addEventListener("message", event => {
    try {
      const message = JSON.parse(event.data);
      if (message?.type === "hello" && message?.role === "bridge") {
        bridgeInfo = {
          ...(bridgeInfo || {}),
          pid: message.pid,
          port: message.port,
          version: message.version
        };
        saveSnapshot();
      }
    } catch (_) { }
  });
}

function sendRaw(payload) {
  if (socket?.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(payload));
    return true;
  }
  connect();
  return false;
}

function sanitizeEvidence(evidence) {
  if (!evidence || typeof evidence !== "object") return null;
  return {
    source: String(evidence.source || "").slice(0, 80),
    match: String(evidence.match || "").slice(0, 120),
    selector: String(evidence.selector || "").slice(0, 160)
  };
}

function sendStatus(status) {
  lastStatus = {
    state: String(status.state || "READY").toUpperCase(),
    label: String(status.label || status.state || "Ready"),
    timestamp: status.timestamp || new Date().toISOString(),
    reason: String(status.reason || "").slice(0, 160),
    evidence: sanitizeEvidence(status.evidence),
    url: status.url || ""
  };
  saveSnapshot();
  sendRaw({ type: "status", ...lastStatus });
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.type === "HALOLINK_STATUS") {
    sendStatus({
      ...message.payload,
      url: sender.tab?.url || message.payload?.url || "",
      tabId: sender.tab?.id
    });
    sendResponse({ ok: true, bridgeConnected, bridgePort });
    return;
  }
  if (message?.type === "HALOLINK_GET_STATE") {
    (async () => {
      if (bridgePort) {
        const payload = await probePort(bridgePort);
        if (payload) {
          bridgeInfo = {
            pid: payload.pid,
            port: payload.port,
            version: payload.version,
            state: payload.state,
            phoneClients: payload.phoneClients
          };
          saveSnapshot();
        }
      }
      sendResponse({ bridgeConnected, bridgePort, bridgeInfo, lastStatus });
    })();
    return true;
  }
  if (message?.type === "HALOLINK_TEST") {
    sendStatus({ state: message.state, label: message.label || message.state, reason: "manual popup test" });
    sendResponse({ ok: true, bridgeConnected, bridgePort });
  }
});

chrome.runtime.onInstalled.addListener(connect);
chrome.runtime.onStartup.addListener(connect);
connect();
