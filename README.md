# Secure Backend API (data-config)
IMPORTANT!!- Do Squash and merge when merging onto to transfer everything safely

A secure backend REST API built with Spring Boot that supports user registration, login, and JWT-based authentication. The application connects to a cloud-hosted PostgreSQL database on AWS RDS and uses a production-style configuration.

## Features
- User registration with password hashing
- User login with JWT token generation
- Protected API endpoints using JWT authorization
- Health check endpoint
- AWS RDS PostgreSQL integration
- Spring Security configuration
- Production profile support

## Tech Stack
- Java
- Spring Boot
- Spring Security
- JWT (JSON Web Tokens)
- PostgreSQL (AWS RDS)
- JPA / Hibernate
- Maven

## Demo (Local)
```bash
curl http://localhost:8080/health

curl -X POST http://localhost:8080/register \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"password123"}'

curl -X POST http://localhost:8080/login \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"password123"}'

curl http://localhost:8080/protected \
  -H "Authorization: Bearer <JWT_TOKEN>"
