# Nimbus Core – Cloud-Deployed Backend Service

Nimbus Core is a cloud-deployed service with a strong emphasis on a secure backend API, supported by a minimal frontend GUI for validation. The platform runs on real, self-managed cloud infrastructure using containerization and environment-based configuration.

This repository is designed to be portable and runnable using environment variables (no secrets committed).

## Demo Screenshots

### Frontend Authentication Flow
<img src="screenshots/api-gui1.jpg" alt="Login Screen" width="300"/>

<img src="screenshots/api-gui2.jpg" alt="Authenticated State" width="300"/>


## Project Roles & Contributions

### Backend & Application (Bereket)
- Designed and implemented the core backend API  
- Implemented JWT-based authentication and protected routes  
- Integrated PostgreSQL with application-level validation  
- Built a minimal frontend GUI to validate authentication flows  
- Structured and documented the project for clarity and portability  

### Infrastructure & Deployment (Sam)
- Provisioned and managed cloud server infrastructure  
- Containerized services for deployment  
- Configured networking, ports, and runtime environment  
- Integrated and maintained the live backend system  

---

## Core Features

### Backend
- User registration and login with JWT authentication  
- JWT-protected endpoints  
- PostgreSQL database integration  
- Environment-based configuration  

### Frontend
- Static multi-page GUI for auth flow validation  
- Secure JWT handling via `localStorage`  
- Protected UI states and auto-logout  
- Served by nginx, which reverse-proxies `/api/` to the backend  

---

## Tech Stack

**Backend**
- Java, Spring Boot, Spring Security  
- JWT, PostgreSQL (self-managed cloud server)  
- JPA / Hibernate, Maven

**Frontend**
- Static HTML / CSS / vanilla JS
- nginx (static hosting + `/api/` reverse proxy)
---

## Project Structure

```
nimbus-core/
├── Backend/
│   ├── Dockerfile              # multi-stage: Temurin JDK 21 build -> JRE runtime
│   ├── .dockerignore
│   ├── pom.xml
│   └── src/main/java/com/nimbus/api/
├── Frontend/
│   ├── Dockerfile              # nginx, static site baked in
│   ├── .dockerignore
│   ├── nginx/
│   │   ├── 00-ratelimit.conf   # limit_req zone for auth endpoints
│   │   └── 10-nimbus-gui.conf  # site + /api proxy + security headers
│   └── public/                 # document root
│       ├── index.html  login.html  register.html  dashboard.html
│       └── assets/             # styles.css, config.js, app.js, <page>.js
├── docker-compose.yml          # db (isolated) + backend + web
├── run.sh                      # build / start / stop / logs
├── .env.example
└── README.md
```

---

## Environment Variables

`run.sh` generates a `.env` with strong random secrets on first run, so there is
usually nothing to do by hand. See `.env.example` for the full list.

| Variable | Purpose |
|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | Database credentials. The backend builds its JDBC URL from these; Postgres is never reachable outside the container network. |
| `JWT_SECRET` | HS256 signing key. **Must be ≥ 32 bytes** — the app refuses to start otherwise. Rotating it invalidates every issued token. |
| `JWT_EXPIRATION_MS` | Token lifetime (default 600000 = 10 min). |
| `APP_CORS_ALLOWED_ORIGIN_PATTERNS` | Leave **empty** for same-origin (correct behind the bundled nginx). Set only if a browser on another origin must call the API. |

`.env` is gitignored and written mode `600`. Never commit it.

---

## Running

Everything runs in containers. From a fresh clone:

```bash
git clone https://github.com/kizzycpt/nimbus-core.git
cd nimbus-core
./run.sh
```

That builds the images, starts the stack, waits for every container to report
healthy, and smoke-tests the API through nginx.

| Command | Effect |
|---|---|
| `./run.sh` | Build if needed and start |
| `./run.sh rebuild` | Clean rebuild (`--no-cache --pull`) and restart |
| `./run.sh logs` | Follow logs |
| `./run.sh status` | Container and health state |
| `./run.sh down` | Stop; **keeps** the database volume |
| `./run.sh destroy` | Stop and **delete** the database volume (prompts for confirmation) |

Once up:

- GUI — `http://127.0.0.1:8080/`
- API — `http://127.0.0.1:8080/api/health`

### Container layout

| Service | Image | Host port | Notes |
|---|---|---|---|
| `db` | `postgres:16` | **none** | On an `internal: true` network — no host port, no LAN exposure, no outbound internet. SCRAM-SHA-256 auth. |
| `backend` | built from `Backend/` | **none** | Reachable only through nginx. Runs as UID 10001, read-only rootfs, all capabilities dropped. |
| `web` | built from `Frontend/` | `127.0.0.1:8080` | The only published port, bound to loopback. Read-only rootfs. |

To reach the database:

```bash
docker compose exec db psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"
```

### Exposing it publicly

The nginx port is bound to `127.0.0.1` on purpose. Put a TLS terminator in
front of it — `cloudflared`, Caddy, or a host nginx — rather than changing the
bind address to `0.0.0.0`. If you do publish it directly, set
`APP_CORS_ALLOWED_ORIGIN_PATTERNS` to the exact origin, never `*`.

---

## Auth Flow Overview

1. User registers via frontend
2. User logs in → backend returns JWT
3. Frontend stores JWT and attaches it automatically
4. Protected endpoints validate JWT
5. `/me` returns authenticated user info
6. Token expiration triggers automatic logout

---

## Demo (Backend Only)

```bash
curl http://localhost:8080/health

curl -X POST http://localhost:8080/register \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"password123"}'

curl -X POST http://localhost:8080/login \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"password123"}'

curl http://localhost:8080/me \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

---

## Notes
- No secrets are committed to the repository
- `.env.example` files document required configuration
- `/health` exists for connectivity testing and may be removed later
