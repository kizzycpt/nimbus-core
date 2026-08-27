document.querySelector("#logoutBtn").addEventListener("click", () => {
  clearToken();
  updateAuthUI();
  alert("Logged out.");
});

document.querySelector("#clearBtn").addEventListener("click", () => {
  clearToken();
  updateAuthUI();
  alert("Token cleared.");
});

document.querySelector("#checkBtn").addEventListener("click", async () => {
  const toast = document.querySelector("#toast");
  try {
    const res = await apiFetch("/health", { method: "GET" });
    setToast(toast, JSON.stringify(res, null, 2), true);
  } catch (err) {
    setToast(toast, `Health check failed: ${err.message}`, false);
  }
});
