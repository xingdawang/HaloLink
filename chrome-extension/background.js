const PORT_START = 8765;
const PORT_END = 8775;

let socket = null;
let retryTimer = null;
let keepAliveTimer = null;
let connectAttempt = 0;
let lastStatus = { state: "READY", label: "Ready", timestamp: new Date().toISOString() };
let bridgeConnected = false;
let bridgePort = null;
let scanning = false;

function saveSnapshot() {
  chrome.storage.local.set({ bridgeConnected, bridgePort, lastStatus });
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
    if (!response.ok) return false;
    const payload = await response.json();
    return payload?.ok === true && typeof payload?.version === "string";
  } catch (_) {
    return false;
  } finally {
    clearTimeout(timeout);
  }
}

async function discoverBridgePort() {
  if (bridgePort && await probePort(bridgePort)) return bridgePort;
  for (let port = PORT_START; port <= PORT_END; port += 1) {
    if (await probePort(port)) return port;
  }
  return null;
}

async function connect() {
  if (scanning) return;
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) return;
  scanning = true;
  clearTimeout(retryTimer);
  const port = await discoverBridgePort();
  scanning = false;

  if (!port) {
    bridgeConnected = false;
    bridgePort = null;
    saveSnapshot();
    scheduleReconnect(Math.min(1500 + connectAttempt * 500, 8000));
    connectAttempt += 1;
    return;
  }

  bridgePort = port;
  const url = `ws://127.0.0.1:${port}/ws/browser`;
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
    sendRaw({ type: "hello", role: "browser-extension", version: "0.1.1" });
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
}

function sendRaw(payload) {
  if (socket?.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(payload));
    return true;
  }
  connect();
  return false;
}

function sendStatus(status) {
  lastStatus = {
    state: String(status.state || "READY").toUpperCase(),
    label: String(status.label || status.state || "Ready"),
    timestamp: status.timestamp || new Date().toISOString(),
    reason: status.reason || "",
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
    sendResponse({ bridgeConnected, bridgePort, lastStatus });
    return;
  }
  if (message?.type === "HALOLINK_TEST") {
    sendStatus({ state: message.state, label: message.label || message.state });
    sendResponse({ ok: true, bridgeConnected, bridgePort });
  }
});

chrome.runtime.onInstalled.addListener(connect);
chrome.runtime.onStartup.addListener(connect);
connect();
