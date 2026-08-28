/* Nimbus GUI — shared helpers.
 *
 * There is no token in localStorage any more. The session lives in an HttpOnly
 * cookie the browser attaches automatically and this script cannot read, so an
 * XSS bug has nothing to steal. The trade-off is CSRF: because the cookie rides
 * along on every same-site request, each mutating call has to prove it came
 * from our own page by echoing the CSRF cookie back in a header.
 */

const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";
const SAFE_METHODS = ["GET", "HEAD", "OPTIONS", "TRACE"];

function $(sel) { return document.querySelector(sel); }
function $$(sel) { return Array.from(document.querySelectorAll(sel)); }

function setToast(el, msg, ok = true) {
  if (!el) return;
  el.classList.remove("ok", "err");
  el.classList.add(ok ? "ok" : "err");
  el.textContent = msg;
  el.hidden = false;
}

/** Escapes anything that came from the database before it reaches innerHTML. */
function esc(value) {
  return String(value === null || value === undefined ? "" : value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function readCookie(name) {
  const prefix = name + "=";
  for (const part of document.cookie.split("; ")) {
    if (part.startsWith(prefix)) return decodeURIComponent(part.slice(prefix.length));
  }
  return null;
}

/** Asks the API to mint a CSRF token when we don't have the cookie yet. */
async function ensureCsrfToken() {
  const existing = readCookie(CSRF_COOKIE);
  if (existing) return existing;
  try {
    await fetch(`${API_BASE}/csrf`, { credentials: "same-origin" });
  } catch (_) { /* offline — the request below will surface the real error */ }
  return readCookie(CSRF_COOKIE);
}

async function apiFetch(path, opts = {}) {
  const method = (opts.method || "GET").toUpperCase();
  const headers = Object.assign({}, opts.headers || {});

  if (opts.body) headers["Content-Type"] = "application/json";

  if (!SAFE_METHODS.includes(method)) {
    const token = await ensureCsrfToken();
    if (token) headers[CSRF_HEADER] = token;
  }

  const res = await fetch(`${API_BASE}${path}`, {
    ...opts,
    method,
    headers,
    credentials: "same-origin"
  });

  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }

  if (!res.ok) {
    const msg = (data && data.message) ? data.message : `HTTP ${res.status}`;
    const err = new Error(msg);
    err.status = res.status;
    throw err;
  }
  return data;
}

/** Current user, or null when signed out. Never throws for a plain 401. */
async function currentUser() {
  try {
    return await apiFetch("/me");
  } catch (err) {
    if (err.status === 401 || err.status === 403) return null;
    throw err;
  }
}

async function logout() {
  try { await apiFetch("/logout", { method: "POST" }); } catch (_) { /* already gone */ }
  location.href = "/login.html";
}

/** Shows/hides nav entries and the auth badge to match the session. */
async function updateAuthUI() {
  const user = await currentUser();

  const authState = $("#authState");
  if (authState) authState.textContent = user ? user.username : "Guest";

  $$("[data-auth='in']").forEach(el => { el.hidden = !user; });
  $$("[data-auth='out']").forEach(el => { el.hidden = !!user; });

  const logoutBtn = $("#logoutBtn");
  if (logoutBtn && !logoutBtn.dataset.bound) {
    logoutBtn.dataset.bound = "1";
    logoutBtn.addEventListener("click", logout);
  }
  return user;
}

/** Bounces to the login page when a protected page is opened signed out. */
async function requireUser() {
  const user = await updateAuthUI();
  if (!user) {
    location.href = "/login.html";
    return null;
  }
  return user;
}

function formatTime(value) {
  if (!value) return "—";
  const d = new Date(value);
  return isNaN(d) ? String(value) : d.toLocaleString();
}

window.addEventListener("DOMContentLoaded", () => {
  // Protected pages call requireUser() themselves; this keeps the nav honest
  // everywhere else.
  if (!document.body.dataset.requiresAuth) updateAuthUI();
});
