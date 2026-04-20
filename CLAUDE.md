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
| Backend | Spring Boot 3.4.4, Java 21, Maven |
| Cache | Redis (shared homelab instance at `redis.homelab.svc.cluster.local:6379`) |
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
├── backend/                        # Spring Boot API
│   ├── pom.xml
│   └── src/main/java/com/ogautam/kinkeeper/
│       ├── config/                 # Firebase, CORS, CacheConfig (Redis)
│       ├── security/               # Firebase token filter + Spring Security
│       ├── controller/             # REST endpoints
│       ├── service/                # Business logic (Firestore reads/writes, Redis-cached)
│       ├── drive/                  # Google Drive API wrapper + OAuth flow
│       ├── crypto/                 # AES-GCM for API keys / refresh tokens
│       ├── agent/                  # Claude tool-use orchestration + attachment staging
│       ├── model/                  # POJOs: UserProfile, Family, FamilyMember, Contact,
│       │                           #        Asset (+AssetType), Category, Document (+LinkRef,
│       │                           #        LinkType), Invite, ChatSession, ChatMessage,
│       │                           #        Reminder (+ReminderRecurrence),
│       │                           #        Conversation (+ConversationFormat, Channel,
│       │                           #        ConversationMessage),
│       │                           #        NutritionEntry (+NutritionSource, NutritionFacts),
│       │                           #        Plan (+PlanType, PlanSegment, PlanSegmentKind)
│       └── exception/              # Global error handler
└── frontend/                       # React PWA
    └── src/
        ├── lib/                    # firebase.ts, categoryTree.ts
        ├── hooks/                  # useAuth, useProfile, useReminderCount
        ├── services/               # axios client with Firebase token interceptor
        ├── components/             # Layout, ProtectedRoute, shadcn ui/
        └── pages/                  # ChatPage, DocumentsPage, CategoriesPage, MembersPage,
                                    # ContactsPage, ConversationsPage, AssetsPage,
                                    # RemindersPage, NutritionPage, PlansPage,
                                    # SettingsPage, LoginPage
```

## Key API Endpoints

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/api/auth/verify` | No | Sync user profile after Google sign-in |
| GET | `/api/auth/me` | Yes | Get current user profile |
| GET | `/api/documents` | Yes | List documents (filter by member/category) |
| POST | `/api/documents/upload` | Yes | Upload file to Drive + Firestore metadata |
| POST | `/api/documents/bulk-upload` | Yes | Upload N files sharing the same member/category/labels/links; returns `{documents, failed}` |
| GET | `/api/documents/{id}/download` | Yes | Stream file from Drive |
| POST | `/api/chat/message` | Yes | Send message to AI agent |
| POST | `/api/family` | Yes | Create family (caller becomes admin) |
| POST | `/api/family/members` | Yes | Add a family member (person) |
| POST | `/api/family/invite` | Yes | Invite Google user to family |
| PUT | `/api/settings/api-key` | Yes | Save encrypted Claude API key |
| GET/POST/PUT/DELETE | `/api/contacts[/{id}]` | Yes | External contacts CRUD |
| GET/POST/PUT/DELETE | `/api/assets[/{id}]` | Yes | Asset CRUD (HOME/VEHICLE/APPLIANCE/POLICY) |
| GET/POST/PUT/DELETE | `/api/reminders[/{id}]` | Yes | Reminder CRUD |
| POST | `/api/reminders/{id}/complete` | Yes | Mark complete (rolls recurrence forward) |
| GET | `/api/reminders/count` | Yes | Badge count of open reminders due within 7 days |
| GET/POST/PUT/DELETE | `/api/conversations[/{id}]` | Yes | Interaction ledger (ENCOUNTER or THREAD); GET supports `?query=`, `?linkType=`, `?linkId=`, `?fromDate=`, `?toDate=` filters |
| GET/POST/DELETE | `/api/nutrition[/{id}]` | Yes | Nutrition entries CRUD; GET supports `?memberId=`, `?fromDate=`, `?toDate=` |
| POST | `/api/nutrition/analyze` | Yes | Multipart `file` — returns a draft NutritionEntry from Claude vision (not persisted) |
| GET/POST/PUT/DELETE | `/api/plans[/{id}]` | Yes | Plan CRUD (trips/events/celebrations) |
| POST | `/api/plans/{id}/segments` | Yes | Append an itinerary segment (FLIGHT/HOTEL/ACTIVITY/CONCERT/MEAL/TRANSPORT/OTHER) |
| POST | `/api/plans/{id}/attach-document/{documentId}` | Yes | Two-way link an existing document to the plan |

## Firestore Collections

```
users/{uid}           → UserProfile (email, displayName, familyId, role, claudeApiKeyEncrypted,
                        driveRefreshTokenEncrypted, chatRetentionDays)
families/{id}         → Family (name, adminUid)
members/{id}          → FamilyMember (familyId, name, relationship, dateOfBirth)
contacts/{id}         → Contact (familyId, name, relationship, phone, email, notes)
assets/{id}           → Asset (familyId, type: HOME|VEHICLE|APPLIANCE|POLICY, name, make,
                        model, identifier, address, provider, purchaseDate, expiryDate,
                        frequency, amount, odometerKm, ownerMemberIds, linkedAssetIds, notes)
documents/{id}        → Document (familyId, memberId, categoryId, driveFileId, fileName,
                        tags, links: List<LinkRef>, uploadedBy, uploadedAt)
categories/{id}       → Category (familyId, name, parentId, isDefault)
invites/{id}          → Invite (familyId, email, role, status, createdAt)
chat_sessions/{id}    → ChatSession (userId, title, expiresAt, pendingAttachmentId)
  /messages/{id}      → ChatMessage (role, content, createdAt)
reminders/{id}        → Reminder (familyId, title, dueAt, recurrence, dueOdometerKm,
                        recurrenceIntervalKm, linkedRefs: List<LinkRef>, completed)
conversations/{id}    → Conversation (familyId, title, format: ENCOUNTER|THREAD,
                        channel: CALL|VISIT|MESSAGE|EMAIL|MEETING|OTHER, occurredAt,
                        summary, outcome, followUp, messages: List<ConversationMessage>,
                        links: List<LinkRef>, createdBy, createdAt, updatedAt)
plans/{id}            → Plan (familyId, name, type: TRIP|EVENT|CELEBRATION|OTHER,
                        startDate, endDate, destination, notes,
                        segments: List<PlanSegment {kind, title, location,
                        confirmation, startAt, endAt, documentId, notes}>,
                        links: List<LinkRef>, createdBy, createdAt, updatedAt)
nutrition/{id}        → NutritionEntry (familyId, memberId, consumedAt, foodName,
                        description, source: PACKAGED|RAW|COOKED|DRINK|OTHER,
                        facts: NutritionFacts (servingDescription + calories/protein/
                        carbs/sugar/fat/saturatedFat/fiber/sodium), ingredients,
                        healthBenefits, warnings, createdBy, createdAt)
```

## Subject model

Documents, reminders, and conversations can link to any number of "subjects" via
`LinkRef = {type, id}` where `type` is one of: `MEMBER`, `CONTACT`, `HOME`, `VEHICLE`,
`APPLIANCE`, `POLICY`, `DOCUMENT`, `CONVERSATION`, `PLAN`. A single entity may link to several
subjects at once — e.g. car insurance links both the policyholder `MEMBER` and the
`VEHICLE` asset; a lawyer-call conversation links both the `CONTACT` lawyer and the
`HOME` asset it's about.

For backward compatibility `Document.memberId` stays as the "primary member link"; new
uploads populate both `memberId` and an entry in `links[]`. Reads that filter by
`memberId` continue to work; multi-subject reads should iterate `links`.

Assets are a unified entity with a `type` discriminator — only fields relevant to each
type are populated:
  - **HOME**:       `name`, `address`, `purchaseDate`, `ownerMemberIds`, `notes`
  - **VEHICLE**:    `name`, `make`, `model`, `identifier` (reg/VIN), `purchaseDate`,
                    `odometerKm`, `ownerMemberIds`, `notes`
  - **APPLIANCE**:  `name`, `make`, `model`, `identifier` (serial), `purchaseDate`,
                    `expiryDate` (warranty end), `ownerMemberIds`, `notes`
  - **POLICY**:     `name`, `provider`, `identifier` (policy #), `purchaseDate` (start),
                    `expiryDate` (end), `frequency`, `amount` (premium),
                    `ownerMemberIds` (insureds), `linkedAssetIds` (covered assets).
                    **POLICY is deliberately broad** — it covers insurance
                    policies AND recurring subscriptions/services (internet, gas,
                    electricity, phone recharge, credit card statements, OTT,
                    rent). The UI labels it "Policies & Subscriptions" and the
                    agent auto-files pasted bill/SMS messages under this type.
                    `expiryDate` is used for both policy-end and next-renewal-
                    due-date; reminders anchor on it with recurrence set to the
                    billing cycle.

Reminders support date-based recurrence (`NONE`/`DAILY`/`WEEKLY`/`MONTHLY`/`QUARTERLY`/
`HALF_YEARLY`/`YEARLY`) and an `ODOMETER` type for vehicle servicing (uses
`dueOdometerKm` + `recurrenceIntervalKm` against a linked `VEHICLE`). Every reminder
must be anchored to at least one asset (`HOME`/`VEHICLE`/`APPLIANCE`/`POLICY`) **or** a
`CONVERSATION` — floating reminders tied only to members/contacts/documents are
rejected by the service.

Plans are time-bounded things the family is organizing — trips, concerts, weddings.
Each plan carries an itinerary of `segments` (flight/hotel/activity/concert/meal/
transport/other) and a polymorphic `links` list for attendees (MEMBER/CONTACT),
attached documents (tickets, bookings), and related assets. Map view is deferred —
see `ROADMAP.md`.

Conversations are a ledger of real-world interactions — calls, visits, meetings, email
threads. Two formats:
  - **ENCOUNTER**: single-entry recap with `summary`, `outcome`, `followUp`. Good for
    "called Dr K, continue meds two more weeks, follow up Oct 3".
  - **THREAD**: verbatim list of `messages` (each `{from, content, at}`). Good for
    negotiations you want to replay later.

Conversations carry `links` like any other subject-aware entity — a conversation can
link to multiple subjects (a lawyer contact AND a home asset AND a specific document)
simultaneously. When an ENCOUNTER carries a `followUp`, the UI and chat agent both
offer to spawn a reminder anchored on that conversation.

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
