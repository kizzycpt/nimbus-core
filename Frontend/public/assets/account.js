/* Account settings: profile, password, active sessions, API tokens, deletion. */

async function loadProfile() {
  const user = await apiFetch("/me");
  $("#profileUser").textContent = user.username;
  $("#profileId").textContent = user.id;
  $("#profileCreated").textContent = formatTime(user.createdAt);
  $("#profilePwChanged").textContent = formatTime(user.passwordChangedAt);
}

async function loadSessions() {
  const list = $("#sessionList");
  const sessions = await apiFetch("/me/sessions");

  if (!sessions.length) {
    list.innerHTML = `<div class="toast">No active sessions.</div>`;
    return;
  }

  list.innerHTML = sessions.map(s => `
    <div class="toast">
      <div>
        <strong>${s.current ? "This device" : "Other device"}</strong>
        ${s.current ? `<span class="badge ok">current</span>` : ""}
      </div>
      <div class="small mono">${esc(s.ip)} · ${esc(s.userAgent)}</div>
      <div class="small">last seen ${esc(formatTime(s.lastSeenAt))} · expires ${esc(formatTime(s.expiresAt))}</div>
      ${s.current ? "" : `<button class="btn btn-danger mt-10" data-revoke="${esc(s.id)}">Revoke</button>`}
    </div>
  `).join("");
}

window.addEventListener("DOMContentLoaded", async () => {
  const user = await requireUser();
  if (!user) return;

  await loadProfile();
  await loadSessions();

  // ---------------------------------------------------------- password
  $("#passwordForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const toast = $("#passwordToast");

    if ($("#newPassword").value !== $("#newPasswordConfirm").value) {
      setToast(toast, "New passwords do not match", false);
      return;
    }

    try {
      const res = await apiFetch("/me/password", {
        method: "POST",
        body: JSON.stringify({
          currentPassword: $("#currentPassword").value,
          newPassword: $("#newPassword").value
        })
      });
      setToast(toast,
        `Password changed. ${res.otherSessionsRevoked} other session(s) signed out.`, true);
      $("#passwordForm").reset();
      await loadProfile();
      await loadSessions();
    } catch (err) {
      setToast(toast, err.message, false);
    }
  });

  // ---------------------------------------------------------- sessions
  $("#sessionList").addEventListener("click", async (e) => {
    const id = e.target.dataset.revoke;
    if (!id) return;
    const toast = $("#sessionToast");
    try {
      await apiFetch(`/me/sessions/${id}`, { method: "DELETE" });
      setToast(toast, "Session revoked.", true);
      await loadSessions();
    } catch (err) {
      setToast(toast, err.message, false);
    }
  });

  $("#revokeAllBtn").addEventListener("click", async () => {
    const toast = $("#sessionToast");
    try {
      const res = await apiFetch("/me/sessions", { method: "DELETE" });
      setToast(toast, `${res.revoked} other session(s) signed out.`, true);
      await loadSessions();
    } catch (err) {
      setToast(toast, err.message, false);
    }
  });

  // --------------------------------------------------------- api token
  $("#tokenBtn").addEventListener("click", async () => {
    const toast = $("#tokenToast");
    try {
      const res = await apiFetch("/me/api-token", { method: "POST" });
      setToast(toast, res.token, true);
      $("#tokenHint").hidden = false;
    } catch (err) {
      setToast(toast, err.message, false);
    }
  });

  // ------------------------------------------------------------ delete
  $("#deleteForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const toast = $("#deleteToast");

    if ($("#deleteConfirm").value !== "DELETE") {
      setToast(toast, 'Type DELETE exactly to confirm.', false);
      return;
    }

    try {
      await apiFetch("/me/delete", {
        method: "POST",
        body: JSON.stringify({ password: $("#deletePassword").value })
      });
      location.href = "/?deleted=1";
    } catch (err) {
      setToast(toast, err.message, false);
    }
  });
});
