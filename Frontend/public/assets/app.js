/* Nimbus GUI helper functions */

function $(sel){ return document.querySelector(sel); }

function setToast(el, msg, ok=true){
  if(!el) return;
  el.classList.remove("ok","err");
  el.classList.add(ok ? "ok" : "err");
  el.textContent = msg;
  el.hidden = false;
}

function saveToken(token){
  localStorage.setItem("nimbus_token", token);
}

function getToken(){
  return localStorage.getItem("nimbus_token");
}

function clearToken(){
  localStorage.removeItem("nimbus_token");
}

async function apiFetch(path, opts={}){
  const headers = Object.assign(
    { "Content-Type": "application/json" },
    opts.headers || {}
  );

  const token = getToken();
  if(token) headers["Authorization"] = `Bearer ${token}`;

  const res = await fetch(`${API_BASE}${path}`, {
    ...opts,
    headers
  });

  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }

  if(!res.ok){
    const msg = (data && data.message) ? data.message : `HTTP ${res.status}`;
    throw new Error(msg);
  }
  return data;
}

function updateAuthUI(){
  const token = getToken();
  const authState = $("#authState");
  const logoutBtn = $("#logoutBtn");
  if(authState) authState.textContent = token ? "Authenticated" : "Guest";
  if(logoutBtn) logoutBtn.hidden = !token;
}

window.addEventListener("DOMContentLoaded", updateAuthUI);
