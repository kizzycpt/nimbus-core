# Nimbus Core – Secure API + Frontend

Nimbus Core is a full-stack project consisting of a Spring Boot backend API and a React frontend GUI.  
It implements secure user registration, login, and JWT-based authentication with protected endpoints.

This repo is configured to be portable and runnable by anyone using environment variables (no secrets committed).

---

## Features

### Backend
- User registration with password hashing
- User login with JWT generation
- JWT-protected endpoints (`/me`, `/protected`)
- Backend validation (empty input + unique usernames)
- PostgreSQL database (AWS RDS compatible)
- Spring Security configuration
- Environment-based configuration

### Frontend
- React-based GUI
- Register and Login flows
- JWT stored securely in localStorage
- Automatic Authorization header attachment
- Auto-logout on token expiration (401)
- Protected UI sections
- Environment-based API base URL

---

## Tech Stack

**Backend**
- Java
- Spring Boot
- Spring Security
- JWT
- PostgreSQL (AWS RDS)
- JPA / Hibernate
- Maven

**Frontend**
- React (Vite)
- JavaScript
- Fetch API

---

## Project Structure

```
nimbus-core/
├── Backend/
├── Frontend/
│   ├── src/
│   ├── .env.example
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

### Frontend (`Frontend/.env.local`)
```env
VITE_API_BASE_URL=http://localhost:8080
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
```bash
cd Frontend
npm install
npm run dev
```

Frontend runs on: `http://localhost:5173`

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