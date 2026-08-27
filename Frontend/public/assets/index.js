document.querySelector("#logoutBtn").addEventListener("click", () => {
  clearToken();
  updateAuthUI();
  alert("Logged out.");
});
