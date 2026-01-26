# Nimbus Core – Frontend GUI

This frontend is a minimal React-based GUI used to validate and interact with the Nimbus Core backend API.

## Purpose
- Exercise authentication flows (login, protected routes)
- Verify JWT handling and expiration behavior
- Provide a simple interface for end-to-end testing

This frontend is intentionally lightweight and is not the primary focus of the project.

## Tech
- React
- Vite

## Running Locally
```bash
npm install
npm run dev
```

Set the API base URL in `.env.local`:
```env
VITE_API_BASE_URL=http://localhost:8080
```
