$("#registerForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const toast = $("#toast");
  const submit = $("#submitBtn");

  const password = $("#password").value;
  if (password !== $("#confirm").value) {
    setToast(toast, "Passwords do not match", false);
    return;
  }

  submit.disabled = true;
  try {
    // Registering signs you in, so there is no second trip through /login.
    const user = await apiFetch("/register", {
      method: "POST",
      body: JSON.stringify({ username: $("#username").value.trim(), password })
    });

    setToast(toast, `Welcome, ${user.username}`, true);
    setTimeout(() => location.href = "/dashboard.html", 600);
  } catch (err) {
    setToast(toast, `Registration failed: ${err.message}`, false);
    submit.disabled = false;
  }
});
