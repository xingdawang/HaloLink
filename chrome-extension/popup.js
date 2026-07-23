const dot = document.getElementById("dot");
const connection = document.getElementById("connection");
const stateEl = document.getElementById("state");
const timeEl = document.getElementById("time");
const bridgeAddress = document.getElementById("bridgeAddress");

function refresh() {
  chrome.runtime.sendMessage({ type: "HALOLINK_GET_STATE" }, response => {
    if (chrome.runtime.lastError || !response) return;
    dot.classList.toggle("on", Boolean(response.bridgeConnected));
    connection.textContent = response.bridgeConnected ? "Mac Bridge connected" : "Mac Bridge not connected";
    bridgeAddress.textContent = response.bridgePort ? `127.0.0.1:${response.bridgePort}` : "searching 8765–8775";
    stateEl.textContent = response.lastStatus?.label || response.lastStatus?.state || "—";
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
