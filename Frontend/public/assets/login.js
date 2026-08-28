$("#loginForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const toast = $("#toast");
  const submit = $("#submitBtn");
  submit.disabled = true;

  try {
    const user = await apiFetch("/login", {
      method: "POST",
      body: JSON.stringify({
        username: $("#username").value.trim(),
        password: $("#password").value
      })
    });

    setToast(toast, `Signed in as ${user.username}`, true);
    setTimeout(() => location.href = "/dashboard.html", 600);
  } catch (err) {
    setToast(toast, `Login failed: ${err.message}`, false);
    submit.disabled = false;
  }
});
