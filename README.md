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
│   └── src/main/java/com/nimbus/api/
├── Frontend/
│   ├── public/            # static site (document root)
│   │   ├── index.html
│   │   ├── login.html
│   │   ├── register.html
│   │   ├── dashboard.html
│   │   └── assets/        # styles.css, config.js, app.js
│   └── nginx/
│       └── nimbus-gui.conf
├── .env.example
├── README.md
```

---

## Environment Variables

### Backend (`.env`)
Create a `.env` file in the repo root (see `.env.example`):

```env
SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<database>
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=your_password
JWT_SECRET=your_jwt_secret
JWT_EXPIRATION_MS=600000
```

### Frontend (`Frontend/public/assets/config.js`)
```js
const API_BASE = "/api";   // same-origin, proxied to the backend by nginx
```

---

## Running Locally

### Backend
```bash
cd Backend
./mvnw spring-boot:run
```

Backend runs on: `http://localhost:8080`

### Frontend
No build step. Serve `Frontend/public/` as a static site:

```bash
cd Frontend/public
python3 -m http.server 5173
```

Frontend runs on: `http://localhost:5173`

Note: with a plain static server there is no `/api/` proxy, so API calls
will 404. For the full flow, deploy behind nginx using
`Frontend/nginx/nimbus-gui.conf` (copy `Frontend/public/` to
`/var/www/nimbus-gui`), or temporarily point `API_BASE` at
`http://localhost:8080` in `config.js`.

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
