import { useState } from "react";

function App() {
  const [health, setHealth] = useState(null);
  const [registerError, setRegisterError] = useState(null);
  const [loginError, setLoginError] = useState(null);

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [loginUsername, setLoginUsername] = useState("");
  const [loginPassword, setLoginPassword] = useState("");
  const [registerMsg, setRegisterMsg] = useState(null);
  const [loginMsg, setLoginMsg] = useState(null);
  const [token, setToken] = useState(null);

  const callHealth = async () => {
    try {
      const res = await fetch("http://localhost:8080/health");
      const text = await res.text();
      setHealth(text);
      setError(null);
    } catch {
      setError("Health check failed");
      setHealth(null);
    }
  };

  const register = async () => {
    try {
      const res = await fetch("http://localhost:8080/register", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, password })
      });

      if (!res.ok) throw new Error();
      setRegisterMsg("Registered successfully");
      setRegisterError(null);
    } catch {
      setRegisterMsg(null);
      setRegisterError("Registration failed");
    }
  };

  const login = async () => {
    try {
      const res = await fetch("http://localhost:8080/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: loginUsername, password: loginPassword })
      });

      if (!res.ok) throw new Error();
      const data = await res.json();

      setToken(data.token);
      setLoginMsg("Login successful");
      setLoginError(null);
    } catch {
      setLoginMsg(null);
      setLoginError("Login failed");
    }
  };

  return (
    <div style={{ padding: 20 }}>
      <h1>API GUI</h1>

      <h2>Health</h2>
      <button onClick={callHealth}>Call /health</button>
      {health && <p>Response: {health}</p>}

      <h2>Register</h2>
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
      <button onClick={register}>Register</button>

      {registerMsg && <p>{registerMsg}</p>}
      {registerError && <p style={{ color: "red" }}>{registerError}</p>}

      <h2>Login</h2>
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

      <button onClick={login}>Login</button>

      {loginMsg && <p>{loginMsg}</p>}
      {loginError && <p style={{ color: "red" }}>{loginError}</p>}
    </div>
  );
}

export default App;