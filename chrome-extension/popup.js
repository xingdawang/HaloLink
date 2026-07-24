const dot = document.getElementById("dot");
const connection = document.getElementById("connection");
const stateEl = document.getElementById("state");
const timeEl = document.getElementById("time");
const reasonEl = document.getElementById("reason");
const evidenceEl = document.getElementById("evidence");
const bridgeAddress = document.getElementById("bridgeAddress");
const bridgePid = document.getElementById("bridgePid");
const bridgePort = document.getElementById("bridgePort");
const bridgeVersion = document.getElementById("bridgeVersion");
const phoneClients = document.getElementById("phoneClients");

function refresh() {
  chrome.runtime.sendMessage({ type: "HALOLINK_GET_STATE" }, response => {
    if (chrome.runtime.lastError || !response) return;
    dot.classList.toggle("on", Boolean(response.bridgeConnected));
    connection.textContent = response.bridgeConnected ? "Mac Bridge connected" : "Mac Bridge not connected";
    bridgeAddress.textContent = response.bridgePort ? `127.0.0.1:${response.bridgePort}` : "searching 8766–8775";
    bridgePid.textContent = response.bridgeInfo?.pid ?? "—";
    bridgePort.textContent = response.bridgeInfo?.port ?? response.bridgePort ?? "—";
    bridgeVersion.textContent = response.bridgeInfo?.version ?? "—";
    phoneClients.textContent = response.bridgeInfo?.phoneClients ?? "—";
    stateEl.textContent = response.lastStatus?.label || response.lastStatus?.state || "—";
    reasonEl.textContent = response.lastStatus?.reason || "No detection detail";
    const evidence = response.lastStatus?.evidence;
    evidenceEl.textContent = evidence?.source
      ? [evidence.source, evidence.match, evidence.selector].filter(Boolean).join(" · ")
      : "—";
    if (response.lastStatus?.timestamp) timeEl.textContent = new Date(response.lastStatus.timestamp).toLocaleTimeString();
  });
}

document.querySelectorAll("button[data-state]").forEach(button => {
  button.addEventListener("click", () => {
    const state = button.dataset.state;
    const label = state === "COMPLETED" ? "Completed!" : `${state[0]}${state.slice(1).toLowerCase()}...`;
    chrome.runtime.sendMessage({ type: "HALOLINK_TEST", state, label }, refresh);
  });
});
refresh();
setInterval(refresh, 1000);
