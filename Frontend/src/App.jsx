<<<<<<< HEAD
import { useState, useEffect } from "react";

=======
import { useEffect, useMemo, useState } from "react";
>>>>>>> a565d52 (Fix CORS for Cloudflare + stabilize GUI auth flow)

function App() {
  // --- API base: prefer same-origin (nginx proxies /health,/register,/login,/me)
  // If you *want* a different backend during dev, set either:
  //   VITE_API_BASE="http://127.0.0.1:8080"
  // or VITE_API_BASE_URL="http://127.0.0.1:8080"
  const API_BASE = useMemo(() => {
    return (
      import.meta.env.VITE_API_BASE ??
      import.meta.env.VITE_API_BASE_URL ??
      ""
    );
  }, []);

  // --- UI state
  const [health, setHealth] = useState(null);
<<<<<<< HEAD
  const [error, setError] = useState(null);
  const [registerError, setRegisterError] = useState(null);
  const [loginError, setLoginError] = useState(null);
  const API_BASE = import.meta.env.VITE_API_BASE ?? "";
=======
  const [healthError, setHealthError] = useState(null);

>>>>>>> a565d52 (Fix CORS for Cloudflare + stabilize GUI auth flow)
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [registerMsg, setRegisterMsg] = useState(null);
  const [registerError, setRegisterError] = useState(null);
  const [registerLoading, setRegisterLoading] = useState(false);

  const [loginUsername, setLoginUsername] = useState("");
  const [loginPassword, setLoginPassword] = useState("");
  const [loginMsg, setLoginMsg] = useState(null);
  const [loginError, setLoginError] = useState(null);
  const [loginLoading, setLoginLoading] = useState(false);

  const [token, setToken] = useState(null);
  const [meUser, setMeUser] = useState(null);
  const [meError, setMeError] = useState(null);
  const [meLoading, setMeLoading] = useState(false);

  // --- Helpers
  const readTextSafe = async (res) => {
    try {
      return await res.text();
    } catch {
      return "";
    }
  };

  const setAuthToken = (newToken) => {
    setToken(newToken);
    if (newToken) localStorage.setItem("token", newToken);
    else localStorage.removeItem("token");
  };

  const logout = () => {
    setAuthToken(null);
    setMeUser(null);
    setMeError(null);
    setLoginMsg(null);
    setLoginError(null);
  };

  const authFetch = async (url, options = {}) => {
    const headers = {
      ...(options.headers || {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    };

    const res = await fetch(url, { ...options, headers });

    // If token expired or invalid, auto-logout
    if (res.status === 401) {
      logout();
      throw new Error("401 Unauthorized (token expired or invalid)");
    }

    return res;
  };

  // --- Load saved token on start
  useEffect(() => {
    const saved = localStorage.getItem("token");
    if (saved) setToken(saved);
  }, []);

  // --- Actions
  const callHealth = async () => {
    setHealth(null);
    setHealthError(null);

    try {
      const res = await fetch(`${API_BASE}/health`);
      const text = await readTextSafe(res);

      if (!res.ok) {
        throw new Error(`HEALTH ${res.status}: ${text || "(no body)"}`);
      }

      setHealth(text);
    } catch (e) {
      console.error(e);
      setHealthError(e.message || "Health check failed");
    }
  };

  const register = async () => {
    setRegisterMsg(null);
    setRegisterError(null);

    if (!username.trim() || !password.trim()) {
      setRegisterError("Username and password cannot be empty.");
      return;
    }

    try {
      setRegisterLoading(true);

      const res = await fetch(`${API_BASE}/register`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: username.trim(), password }),
      });

      const text = await readTextSafe(res);

      if (res.status === 409) {
        setRegisterError("Username already exists. Try a different one.");
        return;
      }

      if (!res.ok) {
        throw new Error(`REGISTER ${res.status}: ${text || "(no body)"}`);
      }

      setRegisterMsg("Registered successfully ✅");
      setUsername("");
      setPassword("");
    } catch (e) {
      console.error(e);
      setRegisterError(e.message || "Registration failed");
    } finally {
      setRegisterLoading(false);
    }
  };

  const login = async () => {
    setLoginMsg(null);
    setLoginError(null);

    if (!loginUsername.trim() || !loginPassword.trim()) {
      setLoginError("Username and password cannot be empty.");
      return;
    }

    try {
      setLoginLoading(true);

      const res = await fetch(`${API_BASE}/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          username: loginUsername.trim(),
          password: loginPassword,
        }),
      });

      const text = await readTextSafe(res);

      if (!res.ok) {
        throw new Error(`LOGIN ${res.status}: ${text || "(no body)"}`);
      }

      let data;
      try {
        data = JSON.parse(text);
      } catch {
        throw new Error(`LOGIN: expected JSON but got: ${text || "(empty)"}`);
      }

      if (!data?.token) {
        throw new Error("LOGIN: response missing token");
      }

      setAuthToken(data.token);
      setLoginMsg("Login successful ✅");
      setLoginUsername("");
      setLoginPassword("");
    } catch (e) {
      console.error(e);
      setLoginError(e.message || "Login failed");
      setAuthToken(null);
    } finally {
      setLoginLoading(false);
    }
  };

  const getMe = async () => {
    setMeUser(null);
    setMeError(null);

    try {
      setMeLoading(true);

      const res = await authFetch(`${API_BASE}/me`);
      const text = await readTextSafe(res);

      if (!res.ok) {
        throw new Error(`ME ${res.status}: ${text || "(no body)"}`);
      }

      let data;
      try {
        data = JSON.parse(text);
      } catch {
        throw new Error(`ME: expected JSON but got: ${text || "(empty)"}`);
      }

      setMeUser(data?.username ?? "(missing username)");
    } catch (e) {
      console.error(e);
      setMeError(e.message || "Failed to call /me");
    } finally {
      setMeLoading(false);
    }
  };

  // --- UI
  return (
    <div
      style={{
        padding: 20,
        maxWidth: 520,
        margin: "0 auto",
        fontFamily: "system-ui, sans-serif",
        lineHeight: 1.4,
      }}
    >
      <h1 style={{ marginBottom: 6 }}>Nimbus Core — API GUI</h1>
      <div style={{ fontSize: 13, color: "#555", marginBottom: 18 }}>
        API base: <code>{API_BASE || "(same-origin)"}</code>
      </div>

      {/* HEALTH */}
      <section style={{ border: "1px solid #ddd", padding: 14, borderRadius: 10, marginBottom: 14 }}>
        <h2 style={{ marginTop: 0 }}>Health</h2>
        <button onClick={callHealth}>Call /health</button>

        {health && (
          <div style={{ marginTop: 10, fontSize: 14 }}>
            <strong>Response:</strong> {health}
          </div>
        )}

        {healthError && (
          <div style={{ marginTop: 10, fontSize: 14, color: "#b00020" }}>
            <strong>Error:</strong> {healthError}
          </div>
        )}
      </section>

      {/* AUTH */}
      {!token ? (
        <>
          {/* REGISTER */}
          <section style={{ border: "1px solid #ddd", padding: 14, borderRadius: 10, marginBottom: 14 }}>
            <h2 style={{ marginTop: 0 }}>Register</h2>

            <div style={{ display: "grid", gap: 8 }}>
              <input
                placeholder="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
              />
              <input
                placeholder="password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />

              <button onClick={register} disabled={registerLoading}>
                {registerLoading ? "Registering..." : "Register"}
              </button>
            </div>

            {registerMsg && (
              <div style={{ marginTop: 10, fontSize: 14, color: "green" }}>
                {registerMsg}
              </div>
            )}

            {registerError && (
              <div style={{ marginTop: 10, fontSize: 14, color: "#b00020" }}>
                <strong>Register failed:</strong> {registerError}
              </div>
            )}
          </section>

          {/* LOGIN */}
          <section style={{ border: "1px solid #ddd", padding: 14, borderRadius: 10 }}>
            <h2 style={{ marginTop: 0 }}>Login</h2>

            <div style={{ display: "grid", gap: 8 }}>
              <input
                placeholder="username"
                value={loginUsername}
                onChange={(e) => setLoginUsername(e.target.value)}
              />
              <input
                placeholder="password"
                type="password"
                value={loginPassword}
                onChange={(e) => setLoginPassword(e.target.value)}
              />

              <button onClick={login} disabled={loginLoading}>
                {loginLoading ? "Logging in..." : "Login"}
              </button>
            </div>

            {loginMsg && (
              <div style={{ marginTop: 10, fontSize: 14, color: "green" }}>
                {loginMsg}
              </div>
            )}

            {loginError && (
              <div style={{ marginTop: 10, fontSize: 14, color: "#b00020" }}>
                <strong>Login failed:</strong> {loginError}
              </div>
            )}
          </section>
        </>
      ) : (
        // LOGGED IN / PROTECTED
        <section style={{ border: "1px solid #ddd", padding: 14, borderRadius: 10 }}>
          <h2 style={{ marginTop: 0 }}>Protected</h2>

          <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
            <button onClick={getMe} disabled={meLoading}>
              {meLoading ? "Calling..." : "Call /me"}
            </button>

            <button onClick={logout}>Logout</button>
          </div>

          {meUser && (
            <div style={{ marginTop: 12, fontSize: 14 }}>
              Logged in as <strong>{meUser}</strong>
            </div>
          )}

          {meError && (
            <div style={{ marginTop: 12, fontSize: 14, color: "#b00020" }}>
              <strong>/me failed:</strong> {meError}
            </div>
          )}

          <div style={{ marginTop: 12, fontSize: 12, color: "#666" }}>
            Token stored in <code>localStorage</code>. If you get a 401, you’ll be auto-logged out.
          </div>
        </section>
      )}
    </div>
  );
}

export default App;
