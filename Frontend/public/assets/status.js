/* Public status page. Polls /status and reports the last time it answered. */

const REFRESH_MS = 15000;

function pill(state) {
  const ok = state === "up" || state === "operational";
  return `<span class="badge ${ok ? "ok" : "err"}">${esc(state)}</span>`;
}

async function refresh() {
  const banner = $("#banner");
  const detail = $("#detail");

  try {
    // A degraded stack answers 503, which apiFetch treats as an error — read the
    // response directly so the page can still show which check failed.
    const res = await fetch(`${API_BASE}/status`, { credentials: "same-origin" });
    const data = await res.json();

    const operational = data.status === "operational";
    banner.className = `toast ${operational ? "ok" : "err"}`;
    banner.textContent = operational
      ? "All systems operational"
      : "Degraded — see the checks below";
    banner.hidden = false;

    detail.innerHTML = `
      <div class="toast">API <span class="mono">${pill(data.checks.api)}</span></div>
      <div class="toast">Database <span class="mono">${pill(data.checks.database)}</span></div>
      <div class="toast">Uptime <span class="mono">${esc(data.uptime)}</span></div>
      <div class="toast">Started <span class="mono">${esc(formatTime(data.startedAt))}</span></div>
      <div class="toast">Server time <span class="mono">${esc(formatTime(data.serverTime))}</span></div>
    `;
  } catch (err) {
    banner.className = "toast err";
    banner.textContent = "Unreachable — the API did not respond";
    banner.hidden = false;
    detail.innerHTML = `<div class="toast err mono">${esc(err.message)}</div>`;
  }

  $("#checkedAt").textContent = new Date().toLocaleTimeString();
}

window.addEventListener("DOMContentLoaded", () => {
  refresh();
  setInterval(refresh, REFRESH_MS);
  $("#refreshBtn").addEventListener("click", refresh);
});
