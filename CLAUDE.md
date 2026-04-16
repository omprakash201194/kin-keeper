# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What is Kin-Keeper

An AI-powered family document vault. Users sign in with Google, store document metadata in Firebase/Firestore, and files in their own Google Drive. A chat interface powered by Claude (BYOK — user provides their own API key) lets users upload, search, and organize documents through natural language.

## Architecture

```
Browser (React + TS + Vite)
   ├── Auth: Firebase Auth (Google OAuth)
   └── All data: through Spring Boot REST API
            │
            ├── Metadata: Firestore
            ├── Files: Google Drive (per-user, via OAuth tokens)
            └── AI Chat: Claude API (tool use, user's own key)
```

Everything goes through the Spring Boot backend — no direct Firestore or Drive access from the frontend. The backend validates Firebase ID tokens on every request, then proxies to Firestore/Drive/Claude as needed.

**Family model:** One user is the family admin. All documents go to the admin's Google Drive. Invited family members (other Google accounts) see the same data with role-based access (admin/viewer).

**BYOK AI:** Each user stores their own Claude API key (encrypted in Firestore). The backend uses it for tool-use chat — Claude decides which tools to call (search docs, upload, recategorize), the backend executes them, returns results to Claude for a final response. No API key = app works in manual mode only.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 3.3.4, Java 21, Maven |
| Frontend | React 18, TypeScript, Vite, Tailwind CSS, shadcn/ui |
| Auth | Firebase Auth (Google provider) |
| Metadata DB | Firestore (no local DB) |
| File Storage | Google Drive (user's own account) |
| AI | Claude API via Anthropic Java SDK (tool use) |

## Build & Run

### Backend
```bash
cd backend
mvn compile                    # compile
mvn spring-boot:run            # run (port 8080)
mvn package -DskipTests        # build jar
mvn test                       # run tests
```
Requires env vars: `FIREBASE_PROJECT_ID`, `FIREBASE_CREDENTIALS_PATH` (path to Firebase service account JSON).

### Frontend
```bash
cd frontend
npm install                    # install deps
npm run dev                    # dev server (port 5173, proxies /api to :8080)
npm run build                  # production build
npx tsc --noEmit               # type check only
```
Requires `.env` file (copy from `.env.example`): `VITE_FIREBASE_API_KEY`, `VITE_FIREBASE_AUTH_DOMAIN`, `VITE_FIREBASE_PROJECT_ID`.

### Docker
```bash
# Backend
cd backend && docker build -t homelab/kin-keeper-backend:1.0.0 .

# Frontend
cd frontend && docker build -t homelab/kin-keeper-frontend:1.0.0 .
```

## Project Structure

```
kin-keeper/
├��─ backend/                        # Spring Boot API
���   ├── pom.xml
│   └── src/main/java/com/ogautam/kinkeeper/
│       ├── config/                 # Firebase, CORS config
│       ├── security/               # Firebase token filter + Spring Security
│       ├── controller/             # REST endpoints (auth, documents, family, chat, settings)
│       ├��─ service/                # Business logic (Firestore reads/writes)
│       ├── drive/                  # Google Drive API wrapper
│       ├── agent/                  # Claude tool-use orchestration
│       │   └── tools/              # Individual tool definitions for Claude
│       ├── model/                  # POJOs: UserProfile, Family, FamilyMember, Document, Category
│       └── exception/              # Global error handler
└── frontend/                       # React SPA
    └── src/
        ├── lib/                    # firebase.ts, utils.ts (cn helper)
        ├── hooks/                  # useAuth
        ├── services/               # axios client with Firebase token interceptor
        ├── components/             # Layout, ProtectedRoute, shadcn ui/
        └── pages/                  # ChatPage, DocumentsPage, MembersPage, SettingsPage, LoginPage
```

## Key API Endpoints

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/api/auth/verify` | No | Sync user profile after Google sign-in |
| GET | `/api/auth/me` | Yes | Get current user profile |
| GET | `/api/documents` | Yes | List documents (filter by member/category) |
| POST | `/api/documents/upload` | Yes | Upload file to Drive + Firestore metadata |
| GET | `/api/documents/{id}/download` | Yes | Stream file from Drive |
| POST | `/api/chat/message` | Yes | Send message to AI agent |
| POST | `/api/family` | Yes | Create family (caller becomes admin) |
| POST | `/api/family/members` | Yes | Add a family member (person) |
| POST | `/api/family/invite` | Yes | Invite Google user to family |
| PUT | `/api/settings/api-key` | Yes | Save encrypted Claude API key |

## Firestore Collections

```
users/{uid}           → UserProfile (email, displayName, familyId, claudeApiKeyEncrypted)
families/{id}         → Family (name, adminUid)
members/{id}          → FamilyMember (familyId, name, relationship, dateOfBirth)
documents/{id}        → Document (familyId, memberId, categoryId, driveFileId, fileName, tags)
categories/{id}       → Category (familyId, name, parentId, isDefault)
```

## Auth Flow

1. Frontend: `signInWithPopup(auth, googleProvider)` → Firebase ID token
2. Frontend: axios interceptor attaches `Authorization: Bearer <id-token>` to every `/api/*` request
3. Backend: `FirebaseAuthFilter` verifies token via Firebase Admin SDK, sets `FirebaseUserPrincipal` in SecurityContext
4. Controllers access the principal via `@AuthenticationPrincipal FirebaseUserPrincipal`

## Conventions

Follows the parent HomeLab CLAUDE.md conventions:
- Constructor injection only, `@Slf4j` for logging, `application.yml` for config
- `ResponseEntity<?>` for all controller returns
- `@ConfigurationProperties` for externalised config
- shadcn/ui components use `@/` path alias (mapped in tsconfig + vite config)
- shadcn/ui components added via: `npx shadcn-ui@latest add <component>` (only `button` is scaffolded so far)
