import { useEffect, useState } from "react";

const API_BASE = import.meta.env.VITE_API_BASE_URL || "";

export default function App() {
  const [health, setHealth] = useState(null);
  const [healthError, setHealthError] = useState(null);

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

  useEffect(() => {
    const saved = localStorage.getItem("token");
    if (saved) setToken(saved);
  }, []);

  const authFetch = async (url, options = {}) => {
    const headers = {
      ...(options.headers || {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    };

    const res = await fetch(url, { ...options, headers });

    if (res.status === 401) {
      localStorage.removeItem("token");
      setToken(null);
      setMeUser(null);
      setLoginMsg(null);
      setLoginError("Session expired. Please log in again.");
      throw new Error("Unauthorized");
    }

    return res;
  };

  const callHealth = async () => {
    try {
      setHealthError(null);
      const res = await fetch(`${API_BASE}/health`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const text = await res.text();
      setHealth(text);
    } catch {
      setHealth(null);
      setHealthError("Health check failed");
    }
  };

  const register = async () => {
    if (!username.trim() || !password.trim()) {
      setRegisterMsg(null);
      setRegisterError("Username and password cannot be empty.");
      return;
    }

    try {
      setRegisterLoading(true);
      setRegisterMsg(null);
      setRegisterError(null);

      const res = await fetch(`${API_BASE}/register`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, password }),
      });

      if (res.status === 409) {
        setRegisterError("Username already exists. Try a different one.");
        return;
      }

      if (!res.ok) throw new Error(`HTTP ${res.status}`);

      setRegisterMsg("Registered successfully");
      setUsername("");
      setPassword("");
    } catch {
      setRegisterMsg(null);
      setRegisterError("Registration failed");
    } finally {
      setRegisterLoading(false);
    }
  };

  const login = async () => {
    if (!loginUsername.trim() || !loginPassword.trim()) {
      setLoginMsg(null);
      setLoginError("Username and password cannot be empty.");
      return;
    }

    try {
      setLoginLoading(true);
      setLoginMsg(null);
      setLoginError(null);

      const res = await fetch(`${API_BASE}/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: loginUsername, password: loginPassword }),
      });

      if (!res.ok) throw new Error(`HTTP ${res.status}`);

      const data = await res.json();
      localStorage.setItem("token", data.token);
      setToken(data.token);

      setLoginMsg("Login successful");
      setLoginUsername("");
      setLoginPassword("");
    } catch {
      setLoginMsg(null);
      setLoginError("Login failed");
    } finally {
      setLoginLoading(false);
    }
  };

  const getMe = async () => {
    try {
      const res = await authFetch(`${API_BASE}/me`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      setMeUser(data.username);
    } catch {
      setMeUser(null);
    }
  };

  const logout = () => {
    localStorage.removeItem("token");
    setToken(null);
    setMeUser(null);
    setLoginMsg(null);
    setLoginError(null);
  };

  return (
    <div style={{ padding: 20, maxWidth: 460, margin: "0 auto", fontFamily: "system-ui, sans-serif" }}>
      <h1>API GUI</h1>

      <h2>Health</h2>
      <button onClick={callHealth}>Call /health</button>
      {health && <div style={{ marginTop: 8, color: "#555", fontSize: 14 }}>Health response: {health}</div>}
      {healthError && <div style={{ marginTop: 8, color: "#b00020", fontSize: 14 }}>{healthError}</div>}

      {!token && (
        <>
          <h2 style={{ marginTop: 20 }}>Register</h2>
          <input placeholder="username" value={username} onChange={(e) => setUsername(e.target.value)} />
          <input placeholder="password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
          <div style={{ marginTop: 8 }}>
            <button onClick={register} disabled={registerLoading}>
              {registerLoading ? "Registering..." : "Register"}
            </button>
          </div>
          {registerMsg && <div style={{ marginTop: 8, color: "green", fontSize: 14 }}>{registerMsg}</div>}
          {registerError && <div style={{ marginTop: 8, color: "#b00020", fontSize: 14 }}>{registerError}</div>}

          <h2 style={{ marginTop: 20 }}>Login</h2>
          <input placeholder="username" value={loginUsername} onChange={(e) => setLoginUsername(e.target.value)} />
          <input placeholder="password" type="password" value={loginPassword} onChange={(e) => setLoginPassword(e.target.value)} />
          <div style={{ marginTop: 8 }}>
            <button onClick={login} disabled={loginLoading}>
              {loginLoading ? "Logging in..." : "Login"}
            </button>
          </div>
          {loginMsg && <div style={{ marginTop: 8, color: "green", fontSize: 14 }}>{loginMsg}</div>}
          {loginError && <div style={{ marginTop: 8, color: "#b00020", fontSize: 14 }}>{loginError}</div>}
        </>
      )}

      {token && (
        <>
          <h2 style={{ marginTop: 20 }}>Protected</h2>
          <button onClick={getMe} disabled={!token}>Call /me</button>
          <button onClick={logout} style={{ marginLeft: 10 }}>Logout</button>

          {meUser && (
            <div style={{ marginTop: 12, color: "#333", fontSize: 14 }}>
              Logged in as <strong>{meUser}</strong>
            </div>
          )}
        </>
      )}
    </div>
  );
}
