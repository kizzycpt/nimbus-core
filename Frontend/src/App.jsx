import { useState } from "react";

function App() {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);

  const callApi = async () => {
    try {
      const res = await fetch("http://localhost:8080/health");
      const text = await res.text();
      setData(text);
      setError(null);
    } catch (err) {
      setError("Failed to reach backend");
      setData(null);
    }
  };

  return (
    <div style={{ padding: 20 }}>
      <h1>Frontend ↔ Backend Test</h1>
      <button onClick={callApi}>Call Backend</button>
      {data && <p>Response: {data}</p>}
      {error && <p style={{ color: "red" }}>{error}</p>}
    </div>
  );
}

export default App;