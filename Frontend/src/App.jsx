import { useState, useEffect } from "react";

const API_BASE = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

function App() {
  const [health, setHealth] = useState(null);
  const [error, setError] = useState(null);
  const [registerError, setRegisterError] = useState(null);
  const [loginError, setLoginError] = useState(null);

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [loginUsername, setLoginUsername] = useState("");
  const [loginPassword, setLoginPassword] = useState("");
  const [registerMsg, setRegisterMsg] = useState(null);
  const [loginMsg, setLoginMsg] = useState(null);
  const [token, setToken] = useState(null);

  useEffect(() => {
    const savedToken = localStorage.getItem("token");
    if (savedToken) {
      setToken(savedToken);
    }
  }, []);

  const [registerLoading, setRegisterLoading] = useState(false);
  const [loginLoading, setLoginLoading] = useState(false);
  const [meUser, setMeUser] = useState(null);

  const authFetch = async (url, options = {}) => {
    const headers = {
      ...(options.headers || {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    };

    const res = await fetch(url, {
      ...options,
      headers,
    });

    if (res.status === 401) {
      setToken(null);
      setMeUser(null);
      setLoginMsg(null);
      setLoginError("Session expired. Please log in again.");
      localStorage.removeItem("token");
      throw new Error("Unauthorized");
    }

    return res;
  };

  const callHealth = async () => {
    try {
      const res = await fetch(`${API_BASE}/health`);
      const text = await res.text();
      setHealth(text);
      
    } catch {
      setHealth(null);
    }
  };

  // Protected request: getMe using authFetch
  const getMe = async () => {
    try {
      const res = await authFetch(`${API_BASE}/me`);
      if (!res.ok) throw new Error();
      const data = await res.json();
      setMeUser(data.username);
    } catch {
      setMeUser(null);
    }
  };

  const logout = () => {
    setToken(null);
    setMeUser(null);
    setLoginMsg(null);
    setLoginError(null);
    localStorage.removeItem("token");
  };

  const register = async () => {
    if (!username.trim() || !password.trim()) {
      setRegisterError("Username and password cannot be empty.");
      setRegisterMsg(null);
      return;
    }
    try {
      setRegisterLoading(true);

      const res = await fetch(`${API_BASE}/register`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, password })
      });

      if (res.status === 409) {
        setRegisterMsg(null);
        setRegisterError("Username already exists. Try a different one.");
        return;
      }

      if (!res.ok) throw new Error();

      setRegisterMsg("Registered successfully");
      setRegisterError(null);
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
    try {
      setLoginLoading(true);

      const res = await fetch(`${API_BASE}/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: loginUsername, password: loginPassword })
      });

      if (!res.ok) throw new Error();
      const data = await res.json();

      setToken(data.token);
      localStorage.setItem("token", data.token);
      setLoginMsg("Login successful");
      setLoginError(null);
      setLoginUsername("");
      setLoginPassword("");
    } catch {
      setLoginMsg(null);
      setLoginError("Login failed");
    } finally {
      setLoginLoading(false);
    }
  };

  return (
    <div style={{ padding: 20, maxWidth: 420, margin: "0 auto", fontFamily: "system-ui, sans-serif" }}>
      <h1>API GUI</h1>

    <h2>Health</h2>
    <div style={{ marginBottom: 8 }} />
      <button onClick={callHealth}>Call /health</button>
      {health && (
  <div style={{ marginTop: 8, color: "#555", fontSize: 14 }}>
    Health response: {health}
  </div>
)}

      {!token && (
        <>
          <h2>Register</h2>
          <div style={{ marginBottom: 8 }} />
          <input
            placeholder="username"
            value={username}
            onChange={e => setUsername(e.target.value)}
          />
          <input
            placeholder="password"
            type="password"
            value={password}
            onChange={e => setPassword(e.target.value)}
          />
          <button onClick={register} disabled={registerLoading}>
            {registerLoading ? "Registering..." : "Register"}
          </button>

          {registerMsg && (
  <div style={{ marginTop: 8, color: "green", fontSize: 14 }}>
    {registerMsg}
  </div>
)}
{registerError && (
  <div style={{ marginTop: 8, color: "#b00020", fontSize: 14 }}>
    {registerError}
  </div>
)}
        </>
      )}

      {!token && (
        <>
          <h2>Login</h2>
          <div style={{ marginBottom: 8 }} />
          <input
          placeholder="username"
          value={loginUsername}
          onChange={e => setLoginUsername(e.target.value)}
          />
          <input
             placeholder="password"
             type="password"
             value={loginPassword}
             onChange={e => setLoginPassword(e.target.value)}
          />

          <button onClick={login} disabled={loginLoading}>
            {loginLoading ? "Logging in..." : "Login"}
          </button>

          {loginMsg && (
  <div style={{ marginTop: 8, color: "green", fontSize: 14 }}>
    {loginMsg}
  </div>
)}
{loginError && (
  <div style={{ marginTop: 8, color: "#b00020", fontSize: 14 }}>
    {loginError}
  </div>
)}
        </>
      )}

      {token && (
        <>
          <h2>Protected</h2>
          <div style={{ marginBottom: 8 }} />
          <button onClick={getMe} disabled={!token}>
            Call /me
          </button>

          <button onClick={logout} disabled={!token} style={{ marginLeft: 10 }}>
            Logout
          </button>

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

export default App;
