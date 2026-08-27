document.querySelector("#logoutBtn").addEventListener("click", () => {
  clearToken();
  updateAuthUI();
  alert("Logged out.");
});

document.querySelector("#loginForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const toast = document.querySelector("#toast");

  const payload = {
    username: document.querySelector("#username").value.trim(),
    password: document.querySelector("#password").value
  };

  try {
    const res = await apiFetch("/login", {
      method: "POST",
      body: JSON.stringify(payload)
    });

    if (!res || !res.token) throw new Error("No token in response");
    saveToken(res.token);
    updateAuthUI();

    setToast(toast, "Logged in!", true);
    setTimeout(() => location.href = "/dashboard.html", 900);
  } catch (err) {
    setToast(toast, `Login failed: ${err.message}`, false);
  }
});
