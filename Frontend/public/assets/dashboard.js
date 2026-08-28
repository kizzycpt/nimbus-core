window.addEventListener("DOMContentLoaded", async () => {
  const user = await requireUser();
  if (!user) return;

  $("#who").textContent = user.username;
  $("#memberSince").textContent = formatTime(user.createdAt);
  $("#sessionCount").textContent = user.activeSessions;
  $("#openTickets").textContent = user.openTickets;
  $("#siteCount").textContent = user.siteCount;
  $("#storage").textContent =
    `${user.storageUsedHuman} of ${user.storageQuotaHuman} (${user.storagePercentUsed}%)`;

  $("#checkBtn").addEventListener("click", async () => {
    const toast = $("#toast");
    try {
      const health = await apiFetch("/health");
      setToast(toast, JSON.stringify(health, null, 2), true);
    } catch (err) {
      setToast(toast, `Health check failed: ${err.message}`, false);
    }
  });

  $("#protectedBtn").addEventListener("click", async () => {
    const toast = $("#toast");
    try {
      const res = await apiFetch("/protected");
      setToast(toast, JSON.stringify(res, null, 2), true);
    } catch (err) {
      setToast(toast, `Protected call failed: ${err.message}`, false);
    }
  });
});
