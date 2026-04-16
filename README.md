# Kin-Keeper

An AI-powered family document vault. Organize government IDs, medical records, education certificates, and more — for yourself and your family — using your own Google Drive as storage and Claude as your personal document assistant.

## How it works

1. **Sign in with Google** — Firebase Auth, no passwords to manage
2. **Add family members** — wife, parents, kids — each person gets their own document space
3. **Upload documents** — files go to your Google Drive, organized in folders by member and category
4. **Ask the AI** — "Find my wife's Aadhaar" or "Upload this to medical records" through a chat interface
5. **Invite family** — share access with other Google accounts (admin/viewer roles)

### AI Chat (BYOK)

The chat interface is the primary way to interact with Kin-Keeper. Powered by Claude's tool-use capability, the AI can search, upload, categorize, and retrieve documents on your behalf.

You provide your own Claude API key (from [console.anthropic.com](https://console.anthropic.com)) — Kin-Keeper never charges for AI features. No key? The app still works in manual mode.

**Example prompts:**
- "Find all medical records for Dad"
- "Upload this to education certificates"
- "Show me documents expiring this year"
- "Move this file to Government ID → Passport"

## Architecture

```
┌─────────────────────────────────┐
│  React + TypeScript + Vite      │
│  (Firebase Auth, shadcn/ui)     │
└──────────────┬──────────────────┘
               │ /api/*
┌──────────────▼──────────────────┐
│  Spring Boot 3.3.4 (Java 21)   │
│  ├── Firebase token validation  │
│  ├── Firestore (metadata)       │
│  ├── Google Drive API (files)   │
│  └── Claude API (tool-use chat) │
└─────────────────────────────────┘
```

- **No local database** — all metadata lives in Firestore
- **No server-side file storage** — documents live in the user's own Google Drive
- **No AI cost to the operator** — each user brings their own Claude API key

## Default categories

| Category | Examples |
|----------|---------|
| Government ID | Aadhaar, PAN, Passport, Voter ID, Driving License |
| Medical | Prescriptions, Lab Reports, Vaccination Records, Insurance |
| Education | Certificates, Marksheets, Transcripts |
| Financial | Bank Statements, Tax Returns, Property Docs |
| Legal | Contracts, Wills, Power of Attorney |

Users can also create custom categories.

## Tech stack

| Layer | Technology |
|-------|-----------|
| Frontend | React 18, TypeScript, Vite, Tailwind CSS, shadcn/ui |
| Backend | Spring Boot 3.3.4, Java 21, Maven |
| Auth | Firebase Auth (Google provider) |
| Metadata | Cloud Firestore |
| File storage | Google Drive (per-user) |
| AI | Claude API via [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java) |

## Prerequisites

- Java 21 (via [SDKMAN](https://sdkman.io/))
- Node.js 20+
- A [Firebase project](https://console.firebase.google.com/) with Authentication (Google provider) and Firestore enabled
- A Google Cloud project with the Drive API enabled
- (Optional) A Claude API key from [console.anthropic.com](https://console.anthropic.com)

## Getting started

### 1. Clone

```bash
git clone https://github.com/omprakash201194/kin-keeper.git
cd kin-keeper
```

### 2. Firebase setup

- Create a Firebase project at [console.firebase.google.com](https://console.firebase.google.com/)
- Enable **Authentication** → Sign-in method → Google
- Enable **Cloud Firestore**
- Download the service account JSON (Project Settings → Service Accounts → Generate new private key)
- Copy the web app config values (Project Settings → General → Your apps → Web app)

### 3. Backend

```bash
cd backend

# Set environment variables
export FIREBASE_PROJECT_ID=your-project-id
export FIREBASE_CREDENTIALS_PATH=/path/to/service-account.json

# Run
mvn spring-boot:run
```

The backend starts on `http://localhost:8080`.

### 4. Frontend

```bash
cd frontend

# Create .env from template
cp .env.example .env
# Fill in your Firebase web app config values:
#   VITE_FIREBASE_API_KEY=...
#   VITE_FIREBASE_AUTH_DOMAIN=...
#   VITE_FIREBASE_PROJECT_ID=...

npm install
npm run dev
```

The frontend starts on `http://localhost:5173` and proxies `/api` requests to the backend.

## Project structure

```
kin-keeper/
├── backend/                     # Spring Boot API
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/ogautam/kinkeeper/
│       ├── config/              # Firebase, CORS
│       ├── security/            # Firebase auth filter
│       ├── controller/          # REST endpoints
│       ├── service/             # Business logic
│       ├── drive/               # Google Drive API
│       ├── agent/               # Claude tool-use orchestration
│       │   └── tools/           # AI tool definitions
│       ├── model/               # Domain objects
│       └── exception/           # Error handling
└── frontend/                    # React SPA
    ├── package.json
    ├── Dockerfile
    └── src/
        ├── lib/                 # Firebase init, utilities
        ├── hooks/               # useAuth
        ├── services/            # API client
        ├── components/          # Layout, shadcn/ui
        └── pages/               # Chat, Documents, Members, Settings, Login
```

## Build for production

```bash
# Backend
cd backend
mvn package -DskipTests
# Output: target/kin-keeper-1.0.0.jar

# Frontend
cd frontend
npm run build
# Output: dist/
```

### Docker

```bash
# Backend
cd backend && docker build -t kin-keeper-backend:1.0.0 .

# Frontend
cd frontend && docker build -t kin-keeper-frontend:1.0.0 .
```

## License

Private project.
