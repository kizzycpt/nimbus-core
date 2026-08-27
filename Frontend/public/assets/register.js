document.querySelector("#logoutBtn").addEventListener("click", () => {
  clearToken();
  updateAuthUI();
  alert("Logged out.");
});

document.querySelector("#registerForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const toast = document.querySelector("#toast");

  const payload = {
    username: document.querySelector("#username").value.trim(),
    password: document.querySelector("#password").value
  };

  try {
    await apiFetch("/register", {
      method: "POST",
      body: JSON.stringify(payload)
    });

    setToast(toast, "Registered! Now login.", true);
    setTimeout(() => location.href = "/login.html", 900);
  } catch (err) {
    setToast(toast, `Register failed: ${err.message}`, false);
  }
});
