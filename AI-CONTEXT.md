# AI-CONTEXT.md — shithead-backend

> **SINGLE SOURCE OF TRUTH** for AI models working with this codebase. `CLAUDE.md` and `AGENTS.md` only point here.
> Update this file when patterns change, new components are added, or architectural decisions are made.
>
> Last Updated: 2026-10-10

---

## Table of Contents

1. Repository Overview
2. Essential Commands
3. Architecture
4. Code Patterns
5. Common Tasks
6. Repository Conventions
7. Clean Code Guidelines
8. Design Patterns in Use
9. Error Handling
10. Testing Guidelines
11. Logging
12. Security
13. AI Model-Specific Guidelines (incl. Gotchas)
14. Documentation Update Protocol

---

## 1. Repository Overview

The card game *Shithead* as a serverless app: a React SPA on GitHub Pages, AWS API Gateway (REST + WebSocket), one Java game Lambda, one Go "glue" binary, DynamoDB and Cognito. All game logic runs server-side; state is pushed to clients over WebSocket. Voice chat uses LiveKit Cloud.

### Owner Priorities (do not change without asking)

- **Cost is the number one priority.** Stay inside the AWS free tier and the LiveKit free plan: on-demand DynamoDB, no provisioned concurrency, no NAT/ECS/always-on compute, no paid add-ons.
- **Hosting stays on GitHub Pages** for the frontend.
- **SnapStart stays enabled** on the Java Lambda (`snap_start { apply_on = "PublishedVersions" }`, invoked through alias `LIVE`).
- **Game logic stays in Java; small glue stays in Go.** Do not reintroduce Python Lambdas or per-route Java functions.

### Tech Stack

| Layer | Technology |
|---|---|
| Game API | Java 17, Spring Boot 3.4.5 (non-web context in Lambda), Lombok 1.18.38, AWS SDK v2 (DynamoDB Enhanced Client, API Gateway Management API, Cognito Identity Provider) |
| Glue | Go 1.24, `aws-lambda-go`, `aws-sdk-go-v2` (DynamoDB; core SigV4 signer for API Gateway `GetConnection`), `net/http` for the LiveKit RoomService; `provided.al2023` on arm64 |
| API | API Gateway REST API (stage `prod`, Cognito user pool authorizer) + WebSocket API (stage `$default`, Lambda REQUEST authorizer) |
| Persistence | DynamoDB, `PAY_PER_REQUEST` |
| Auth | Cognito user pool with Google identity provider, hosted UI, ID tokens (1 h) and refresh tokens (30 d) |
| Voice | LiveKit Cloud (free plan). Java mints access tokens; Go verifies LiveKit webhooks, counts monthly usage and deletes rooms at the limit |
| Frontend | Vite 5, React 18, react-router-dom v6, TanStack Query v5 (persisted), `livekit-client` (lazy chunk), TypeScript |
| Infra | Terraform (AWS provider ~> 5.0, state in S3) |
| CI/CD | GitHub Actions `.github/workflows/ci-cd.yaml` |
| Tests | JUnit 5 (junit-bom 5.12.2), Mockito 5.17.0 (`mockito-core` only); Go `testing` |

### Directory Structure

```
shithead-backend/
├── backend/                         # Java game API (Maven module of the root pom.xml)
│   ├── src/main/java/com/tamaspinter/backend/
│   │   ├── LambdaHandler.java       # Lambda entry point (RequestStreamHandler)
│   │   ├── BackendApplication.java
│   │   ├── config/                  # Dispatcher, route table, handler beans, error texts, Cognito client, security
│   │   ├── controller/              # HealthController (local Spring web only)
│   │   ├── entity/                  # DynamoDB beans (game item and nested maps)
│   │   ├── game/                    # GameSession state machine, GameConfig, events, chat/nudge helpers
│   │   ├── handler/                 # HTTP mapping for admin users, browse games, voice token
│   │   ├── mapper/                  # SessionMapper (domain <-> entity)
│   │   ├── model/                   # Card, Player, Deck, Suit, CardRule, UserProfile; api/ views; websocket/ DTOs
│   │   ├── repository/              # Game sessions, user profiles, nickname claims
│   │   ├── rules/                   # RuleEngine + per-rule strategies
│   │   └── service/                 # Elo, profiles, admin users, blocking, browse, LiveKit, lobby/connection cleanup, account deletion
│   ├── src/main/resources/          # application.properties, checkstyle/, pmd/, spotbugs/
│   └── local/                       # LEGACY SAM template / docker-compose / notes; not used by CI
├── glue-go/                         # Go glue binary (create-game, WS lifecycle, authorizer, init-user, janitor, LiveKit webhook)
├── infra/
│   ├── state_bucket_init/           # One-off S3 state bucket
│   └── terraform/                   # Root module: cognito, lambda, dynamodb, api_gateway, cloudwatch
│       └── ecr/, ecs/               # LEGACY modules, not referenced from main.tf
├── frontend/                        # Vite + React SPA (GitHub Pages)
├── scripts/test-ws.sh               # Manual wscat WebSocket test
├── Dockerfile, deploy.sh, deploy.ps1  # LEGACY (container build / local apply); do not use for deploys
├── pom.xml                          # Root aggregator (parent spring-boot-starter-parent 3.4.5)
└── AI-CONTEXT.md
```

### Backend Package Reference

| Package | Contents |
|---|---|
| (root) | `LambdaHandler` (boots Spring once, calls `GameApiFunctionConfig.dispatch`, writes the response as-is), `BackendApplication` |
| `config` | `GameApiFunctionConfig` (event-shape dispatcher), `ApiRoutes` (route table), `GameFunctionConfig` (join/leave/start/raise decks/state/leaderboards, `playCardWS`, `pickupPileWS`), `AccountManagementFunctionConfig` (profile, admin, browse, delete account), `VoiceFunctionConfig`, `PlayErrorMessages`, `CognitoClientConfig`, `SecurityConfig` |
| `bot` | `BotType`, `BotStrategy` (+ `BeginnerBotStrategy`, `IntermediateBotStrategy` with `PlayEvaluator`/`OpponentOdds`/`CardWorth`, `BotStrategies`), `BotView` (public table view, plus `knownHand`/`unseen` for card-counting bots), `CardMemory` (public-move memory, stored as `botMemory`), `SetupSwap`, `BotTurnRunner` (bot setup and in-memory bot turns) |
| `game` | `GameSession`, `GameConfig`, `PlayResult`, `CardSelection`, `CardSource`, `GameEvent`, `GameEventType`, `ChatMessageValidator`, `NudgeMessage`, `GameManager` |
| `rules` | `RuleEngine` (static), `RuleStrategy`, `AfterEffect`, `Default/Joker/Smaller/Transparent/Reverse/BurnerRuleStrategy` |
| `model` | `Card`, `Player`, `Deck`, `Suit`, `CardRule`, `UserProfile` (users-table bean); `model.api`: `GameStateView`, `PlayerStateView`, `LeaderboardEntry`; `model.websocket`: `PlayMessage`, `PickupMessage`, `GameEnded` |
| `entity` | `GameSessionEntity`, `PlayerEntity`, `CardEntity`, `GameConfigEntity`, `GameEventEntity`, `EloChangeEntity` |
| `mapper` | `SessionMapper` (static): `toEntity`, `fromEntity`, `carryEloState`, event/card conversions |
| `repository` | `GameSessionRepository`, `UserProfileRepository`, `UsernameReservationRepository`, `DynamoDbClientProvider` |
| `service` | `EloService` (static), `AdminUserService`, `BlockedUserGuard`, `GameBrowseService`, `LiveKitAccessTokenService`, `LobbyMembershipService`, `UserConnectionService`, `UserProfileService`, `AccountDeletionService` |
| `handler` | `AdminUserHandler`, `GameBrowseHandler`, `VoiceTokenHandler`, `JsonResponses` (CORS JSON helper) |

There is no `exception/` package and no custom exception hierarchy (see §9).

---

## 2. Essential Commands

Maven: `backend/mvnw` is **not** tracked (only `backend/.mvn/wrapper/maven-wrapper.properties` is) and Maven may not be on `PATH`. CI runs plain `mvn` from the repository root with `-f backend/pom.xml`. Locally use any Maven 3.9 install (or the `maven:3.9-amazoncorretto-17` Docker image) with JDK 17.

```bash
# Backend: tests + Checkstyle/PMD/SpotBugs (exactly what CI runs)
mvn -q -f backend/pom.xml verify -P codeQuality

# Backend: tests only / fat JAR for Lambda (backend/target/backend-0.0.1-SNAPSHOT.jar)
mvn -q -f backend/pom.xml test
mvn -q -f backend/pom.xml package -DskipTests

# Glue (Go): what CI runs
cd glue-go && go vet ./... && go test ./...
# Glue build that Terraform deploys (glue-go/build/glue.zip, gitignored)
cd glue-go && mkdir -p build && GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -tags lambda.norpc -o build/bootstrap . \
  && (cd build && zip -q glue.zip bootstrap)

# Frontend
cd frontend && npm ci
npm run dev        # Vite dev server on :5173 (needs frontend/.env, see .env.example)
npm run build      # production build into frontend/dist (no separate tsc step)
npm run size       # gzip budget check of dist/ (run after build; CI fails over budget)

# Terraform: offline validation only (no AWS calls). The lambda module hashes the two
# artifacts, so create placeholders if they were not built, and delete them afterwards.
mkdir -p backend/target glue-go/build && touch backend/target/backend-0.0.1-SNAPSHOT.jar glue-go/build/glue.zip
cd infra/terraform && terraform init -backend=false && terraform validate
```

Never run `terraform plan/apply` locally: applies happen only in CI (§6).

### Static Analysis (`-P codeQuality`)

Skipped by default (profile `build` sets `*.skip=true`); CI always enables `codeQuality`. All three fail the build on violations.

| Tool | Config file | Phase | Checks |
|---|---|---|---|
| Checkstyle | `backend/src/main/resources/checkstyle/checkstyle.xml` | `validate` | Formatting, naming, line length (max 150) |
| PMD | `backend/src/main/resources/pmd/ruleset.xml` | `process-test-classes` | Best practices, design, error-prone |
| SpotBugs | `backend/src/main/resources/spotbugs/excludes.xml` | `process-test-classes` | Bytecode bug patterns |

Suppress narrowly and only with a reason, e.g. `@SuppressWarnings("PMD.CognitiveComplexity")` (used on large handler beans) or `@SuppressWarnings("checkstyle:MagicNumber")`.

---

## 3. Architecture

### Request Flow

```
Browser (GitHub Pages SPA)
  ├── REST  → API Gateway REST (Cognito authorizer, stage prod)
  │            ├── POST /create-game ─────────────────────────────→ glue (Go)
  │            └── every other route ─────────────────────────────→ game-api:LIVE (Java)
  └── WSS   → API Gateway WebSocket (route selection $request.body.action)
               ├── $connect  → authorizer (Go, ?token=) → glue: store connection
               ├── $disconnect / $default ────────────────────────→ glue ($default is a no-op)
               └── play, setup, chat, nudge, pickup ──────────────→ game-api:LIVE (Java)
                        → GameSession → DynamoDB → postToConnection to every connection of the game
LiveKit Cloud → REST POST /livekit/webhook (no authorizer, signed) → glue: voice usage counter
EventBridge rate(5 minutes) → glue: abandoned-game janitor
Cognito post-confirmation / post-authentication → glue: init-user
```

### Lambda Functions

| Function | Runtime | Built from | Serves |
|---|---|---|---|
| `${project}-game-api` | java17, 1024 MB, 60 s, SnapStart, alias `LIVE` | `backend/` fat JAR; handler `com.tamaspinter.backend.LambdaHandler::handleRequest` | All REST routes except create-game and the LiveKit webhook; WS `play`, `setup`, `chat`, `nudge`, `pickup` |
| `${project}-glue` | provided.al2023, arm64, 256 MB, 10 s | `glue-go/build/glue.zip` | REST `create-game` and `livekit/webhook`, WS `$connect`/`$disconnect`/`$default`, Cognito init-user trigger, EventBridge janitor |
| `${project}-authorizer` | provided.al2023, arm64, 256 MB, 10 s | same zip | WebSocket REQUEST authorizer |

`project_name` defaults to `shithead`. The glue zip is deployed twice on purpose: the Cognito pool references the init-user trigger and the authorizer needs the pool id, so one function would create a Terraform cycle. The glue function gets no pool id; `verifierFromEnv()` returns nil there, which is also how the dispatcher knows it may run the janitor.

### Game API Lambda (Java)

- `LambdaHandler` is a `RequestStreamHandler`: it reads the raw event as a map, calls `GameApiFunctionConfig.dispatch`, and writes the returned API Gateway response object **unwrapped**. Do not switch back to the generic Spring Cloud Function adapter (it wraps the response in a second envelope without CORS headers; browsers report "Failed to fetch"). `SPRING_CLOUD_FUNCTION_DEFINITION=gameApi` is still set in Terraform and the `gameApi` bean still exists, but the entry point does not use them.
- `GameApiFunctionConfig.dispatch` decides by shape: `requestContext.routeKey`/`eventType` present → WebSocket (route key, or body `action` when the route key is missing); `httpMethod` present → REST (path or resource + method; path templates match the end of the path, and path parameters are filled from the template when API Gateway did not supply them); anything else or an unknown route → `404 {"message":"Not found"}` with CORS headers.
- `ApiRoutes` is the route table; handlers are existing `Function` beans.

| Method | Path / WS action | Handler bean |
|---|---|---|
| POST | `/join-game` | `GameFunctionConfig.joinGame` |
| POST | `/leave-game` | `GameFunctionConfig.leaveGame` |
| POST | `/start-game` | `GameFunctionConfig.startGame` |
| GET | `/state/{sessionId}` | `GameFunctionConfig.getState` |
| GET | `/leaderboard/top` | `GameFunctionConfig.leaderboardTop` (`?limit=` 1..100, default 20) |
| GET | `/leaderboard/session/{sessionId}` | `GameFunctionConfig.leaderboardSession` |
| GET, PUT, DELETE | `/profile` | `AccountManagementFunctionConfig.accountManagement` |
| POST | `/admin/doomsday` | `accountManagement` (game-admin) |
| GET | `/admin/users` | `accountManagement` (game-admin) |
| POST | `/admin/users/{userId}/block`, `/unblock` | `accountManagement` (game-admin) |
| GET | `/games` | `accountManagement` (any signed-in user) |
| POST | `/games/{sessionId}/voice-token` | `VoiceFunctionConfig.voiceToken` |
| POST | `/games/{sessionId}/decks` | `GameFunctionConfig.raiseDecks` |
| POST | `/games/{sessionId}/bots` | `GameFunctionConfig.manageBots` |
| WS | `play`, `setup`, `chat`, `nudge` | `GameFunctionConfig.playCardWS` (branches on body `action`) |
| WS | `pickup` | `GameFunctionConfig.pickupPileWS` |

Env vars of the game API: `GAME_SESSIONS_TABLE`, `USERS_TABLE`, `WS_CONNECTIONS_TABLE`, `WS_MANAGEMENT_ENDPOINT`, `SPRING_CLOUD_FUNCTION_DEFINITION`, `COGNITO_USER_POOL_ID`, `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` (`AWS_REGION` is provided by Lambda). One IAM role `game_api_exec`: DynamoDB on the three tables plus `leaderboard-index`, `user_id-index` and `game_session_id-index` (incl. Scan everywhere and TransactWriteItems on users), `execute-api:ManageConnections`, and `cognito-idp:AdminDeleteUser` on the pool only.

### Glue Lambda (Go, `glue-go/`)

`dispatch.go` (`App.Handle`) routes by event shape, in this order:

| Event | Handler | Notes |
|---|---|---|
| `triggerSource` present | `init_user.go` | Seeds `username` (preferred_username, else email), `leaderboard_pk`=`global`, `elo_score`=1000, `created_at` with `if_not_exists`, so an edited name survives later logins. Returns the event unchanged. |
| `type`=`REQUEST` + `methodArn` | `auth.go` | Verifies the RS256 Cognito **ID** token (issuer, audience = app client id, `token_use`=`id`, expiry) from `?token=` (or a Bearer header; API Gateway's identity source is the query string). JWKS cached, refetched on unknown `kid` with a 1-minute cooldown. Users with `blocked`=true get a `Deny` policy. Context: `sub`, `username`. |
| `aws.events` + `Scheduled Event`, glue only | `janitor.go` | See "Abandoned-game janitor". |
| WS `routeKey`/`eventType` | `websocket.go` | `$connect` stores `connection_id`, `game_session_id` (query), `user_id` (authorizer `sub`), `ttl` = now + 3600. `$disconnect` deletes the row. `$default` returns 200 and does nothing (clients must not broadcast). Other route keys → 404. |
| `httpMethod` + path/resource `/livekit/webhook` | `voice_webhook.go` | See "Voice usage counter". |
| any other `httpMethod` | `games.go` | `POST /create-game` (see below). |

**create-game**: requires the Cognito `sub` (401 otherwise). `config.go` validates `config` strictly: `decksCount` 1 or 2 (burn count derived: 4 or 6), `faceDownCount`/`faceUpCount`/`handCount` integers 0..10 (default 3), booleans `allowMixedHandAndFaceUpWhenDeckEmpty`, `allowFailedFaceUpPlay`, `voiceEnabled` must be real booleans, `cardRules` values from `DEFAULT|JOKER|SMALLER|TRANSPARENT|REVERSE|BURNER` for ranks 2..14 (unset ranks = `DEFAULT`; `alwaysPlayable` = JOKER/TRANSPARENT ranks, `canPlayAgain` = BURNER ranks); unknown keys ignored. Invalid → 400 `{"error":"Invalid game configuration"}` and nothing written. `voiceEnabled` requires `cognito:groups` to contain `game-admin` (array or bracketed string), else 403 `{"message":"Only administrators can enable voice chat."}`; with voice on, a month at 4,500 or more participant-minutes returns 409 (voice paused, see below). Then the caller's unstarted owned lobbies are handed to the next player or deleted, a random 6-character code (A-Z, 0-9) is chosen, and the item is written with `created_at` (ISO string), `updated_at` (epoch s) and `ttl` = now + 3600. Response `{"sessionId": "..."}` with CORS headers.

**Abandoned-game janitor** (`janitor.go`, `connection_check.go`; rule in `infra/terraform/lambda/janitor.tf`, `rate(5 minutes)`): scans every unfinished game, both waiting lobbies and started games. A game is deleted only when its `started` attribute is a real BOOL (otherwise skipped), its last activity (`updated_at`, else `created_at`, else `ttl` - 3600) is older than `janitorGracePeriod` = 15 minutes, **and** it has no live connection. Liveness: every row in the connection registry's `game_session_id-index` whose `ttl` is in the future (a row without `ttl` counts) is confirmed with API Gateway `GetConnection` (SigV4-signed `GET {WS_MANAGEMENT_ENDPOINT}/@connections/{id}`, 3 s timeout). HTTP 200 keeps the game; HTTP 410 deletes that stale row and does not count; any other status, a timeout or a missing endpoint keeps the game. The delete is conditional (same `started` value, still unfinished, `updated_at` absent or not newer than scanned), so a game saved meanwhile survives; a query error skips the game. Finished games are never touched. About 8,640 invocations a month, inside the free tier, and it never calls the Java Lambda.

**Voice usage counter** (`voice_usage.go`, `voice_webhook.go`, `voice_roomservice.go`, `voice_jwt.go`): LiveKit posts room events to `POST /livekit/webhook` (REST, no Cognito authorizer, no CORS `OPTIONS`). The `Authorization` header must be an HS256 JWT signed with `LIVEKIT_API_SECRET`, issued by `LIVEKIT_API_KEY`, whose `sha256` claim is the base64 SHA-256 of the raw body (`exp`/`nbf` enforced); otherwise 401 and nothing is written (empty LiveKit values reject every request). Rows live in the users table:
- `participant_joined` → put `__voice_open#<room>#<identity>` with `joined_at` (overwrites an older row, so a missed leave is never counted twice).
- `participant_left` → conditional `REMOVE joined_at` (`attribute_exists`), then atomic `ADD minutes` on `__voice_usage#YYYY-MM` (UTC month of the leave event). Each connection rounds up to whole minutes, minimum 1. A duplicate or unmatched leave counts nothing; the key-only open row stays until the next join overwrites it.
- When a leave brings the month to `voiceMonthlyLimitMinutes` = 5,000, the glue lists every LiveKit room and deletes it (Twirp `ListRooms`/`DeleteRoom` with a short-lived server token holding `roomList` + `roomCreate`). Failures are logged and retried on the next leave.
- create-game reads the usage item strongly consistent and refuses new voice games from `voiceGuardMinutes` = 4,500 with 409 `Voice chat is paused until next month to stay within the free LiveKit allowance.`

Terraform does not configure LiveKit itself; pointing the LiveKit project's webhook at this URL is done outside the repo.

Glue env vars: glue gets `GAME_SESSIONS_TABLE`, `USERS_TABLE`, `WS_CONNECTIONS_TABLE`, `WS_MANAGEMENT_ENDPOINT` (same value as the game API), `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`; authorizer gets `COGNITO_USER_POOL_ID`, `COGNITO_APP_CLIENT_ID`, `REGION`, `USERS_TABLE`. Both share role `lambda_exec`: DynamoDB on the three tables and `leaderboard-index`/`user_id-index`/`game_session_id-index` (no DeleteItem on users), Scan on games (janitor), and `execute-api:ManageConnections` (janitor `GetConnection`).

### Game Lifecycle

1. **Create**: glue `create-game` (owner = `user_id`, owner is the only player).
2. **Join / leave** (`/join-game`, `/leave-game`): only before start. Join first removes the user from other unstarted lobbies they own (`cleanupOldSessions`). Seats: `GameSession.seatCapacity` = min(`MAX_PLAYERS` = 10, decks × 52 / (faceDown + faceUp + hand)); with the default 3 + 3 + 3 layout one deck seats 5 and two decks seat 10. A join past capacity returns 409 with a message (one deck: `Game is full: one deck seats N players. The owner can add a second deck.`). Leaving hands ownership to the next player or deletes an empty lobby; leaving a started game returns 409 `{"error":"Cannot leave a started game"}`.
3. **Second deck** (`POST /games/{sessionId}/decks`, body `{"decksCount":2}`, the integer 2 only, else 400): owner only (403), before start (409 `Game already started`), one-deck games only (409 `This game already uses two decks`). Sets `decksCount` 2 and `burnCount` 6 and returns `{"decksCount":2,"burnCount":6}`; nothing is broadcast. `GameStateView.decksCount` (1 when the item has no config) drives the Room's "Add a second deck" button.
3a. **Bots** (`POST /games/{sessionId}/bots`): owner only (403), before start/starting (409), seats limited like joins (409 with the same message). Body `{"action":"add","botType":"BEGINNER"}` returns `{"playerId","username"}` (ids `bot-<uuid>`, names `Beginner Bot N`, lowest free N); `{"action":"remove","botId":...}` (404 if not a bot of the game). A bot is a normal `players` entry with `botType` set (humans have none). Ownership only ever passes to a human: `GameSession.removePlayer` (and the glue's `handOverOrDelete`) empties/deletes a lobby where only bots would remain.
4. **Start** (owner only, at least 2 players, else 400): `POST /start-game {"phase":"prepare"}` persists `starting=true`; the owner's client sends WS `setup` with `setupAction:"announce"` so everyone sees the transition immediately (the room also polls state every second as a fallback); then `POST /start-game` without `phase:"prepare"` deals. Dealing checks deck feasibility: if players × (faceDown + faceUp + hand) > decks × 52, it returns 409 `Not enough cards: this setup supports at most N players with D deck(s)` and resets `starting`.
5. **Setup phase** (`started` true, `setupComplete` false): hand and face-up are sorted by rank and suit, face-down order is kept. WS `setup` actions:
   - `swap`: `handIndices` + `faceUpIndices` (i-th pairs with i-th; non-empty, equal size, no duplicates, in range, player not ready); legacy `handIndex`/`faceUpIndex` still accepted. No event is recorded.
   - `ready`: marks the player ready; when all are ready `setupComplete` becomes true and play is allowed.
   - `starter` + `starterId`: owner override of who starts (`GameSession.setStarter`), only during setup, only for a player in the game. By default the **lowest Elo** player starts (`start(ratings)`, missing rating = 1000, ties → first in list).
   - `announce`: see step 4.
6. **Play**: WS `play` (with `selections` `[{source: HAND|FACE_UP|FACE_DOWN, index}]`, or legacy `cards`) and `pickup`. Every `playCardWS` action requires the sender to be a player of the game (403 otherwise); the not-your-turn check happens in `GameFunctionConfig` before the session is touched.
6a. **Bot turns** (`bot/BotTurnRunner`): bots have no client, connection or Cognito identity. `startGame` runs `completeSetup` (bots swap via their strategy and mark ready at once). Every play, pickup and setup action saves through `GameFunctionConfig.saveAfterMove`, which first runs `playBotTurns` in memory: while it is a bot's turn, the strategy picks one entry of `GameSession.legalPlays()` (or a pickup); a choice that is not legal, a rejected move or a strategy exception falls back to the first legal play, else pickup. The session is then saved once and broadcast once (broadcasts reach connections only, so bots are skipped naturally). `MAX_BOT_MOVES` = 2000 per invocation; a human seat comes up after at most one round, so only an all-bot remainder can reach it, and that game is ended by `GameSession.finishStalledBotGame()` (most cards = shithead). No timers, schedules or extra Lambdas: bots only move inside the invocation of the triggering action.
7. **Finish**: when at most one player is not out, the game is `finished` and the remaining player is `shitheadId`; Elo is updated once (see Elo).

### Game State Machine (`GameSession`)

`playCards(List<Card>)` / `playSelections(List<CardSelection>)` / `pickupPile()` return `PlayResult`:

| Result | Meaning |
|---|---|
| `SUCCESS` | Cards played; after-effects applied; turn advances unless the card's rank is BURNER or in `canPlayAgain`, or the play burned the pile by count (and the player is not out) |
| `PICKUP` | Player picked up the pile (explicit pickup, failed blind flip, or failed face-up play when allowed) |
| `INVALID` | Rejected; `getLastInvalidReason()` says why |

`InvalidReason` (cleared at the start of each call) → `PlayErrorMessages.forReason()` text sent as WS error. Messages never reveal face-down values.

| Reason | Message |
|---|---|
| (not your turn, checked in `GameFunctionConfig`) | It's not your turn yet. |
| `SETUP_NOT_COMPLETE` | Wait until everyone is ready. |
| `GAME_FINISHED` | This game has already ended. |
| `EMPTY_SELECTION` | Select a card to play first. |
| `CARD_NOT_AVAILABLE` | Those cards are not available to play. |
| `WRONG_ZONE` | You can't play those cards right now: use your hand first, then your face-up cards, then the face-down ones. |
| `FACE_DOWN_ONE_AT_A_TIME` | Flip your face-down cards one at a time. |
| `MIXED_VALUES` | Cards played together must have the same value. |
| `TOO_LOW` / `TOO_HIGH` | That card is too low/high: play {rank} or higher/lower. (`getLastRequiredPileValue()`; 11-14 named Jack..Ace; decided by the effective top, transparent cards looked through; a `SMALLER` top needs equal or lower) |
| `MIXED_HAND_FACEUP_NOT_ALLOWED` | Hand and face-up cards can only be played together when the draw pile is empty and that option is on. |
| `PILE_EMPTY` | There is nothing to pick up. |

Rules worth knowing:
- Source priority: **hand → faceUp → faceDown** (blind flip, one card at a time). The client sends explicit source + index; face-down values stay hidden until the server reveals them. When a single face-down (or single face-up) selection ends in `PICKUP`, the broadcast carries a transient `revealedCard`.
- `allowMixedHandAndFaceUpWhenDeckEmpty`: same-value hand + face-up combination only when the draw pile is empty.
- `allowFailedFaceUpPlay` (default false; missing attribute = false): a player with an empty hand who plays an illegal face-up selection puts it on the pile and picks up the whole pile (`PICKUP`), instead of `INVALID`.
- `playSelections` must end in `finishSuccessfulPlay` so after-effects run and the turn advances; new selection sources must keep that, and must record events through `recordEvent`/`commitPlay`/`pickUpPile`.
- `postPlayCleanup`: burn check → refill hand from the deck → out check. `nextPlayer()` skips players who are out.

### Card Rule Engine

`RuleEngine` is static; each `CardRule` maps to a `RuleStrategy`. Default mapping (`GameConfig.defaultGameConfig()` and Go `defaultCardRules()`), overridable per game from the `/config` screen:

| Value | Rule | Behaviour |
|---|---|---|
| 2 | `JOKER` | Always playable; any card may follow it |
| 6 | `SMALLER` | Next card must be ≤ 6 |
| 8 | `TRANSPARENT` | Always playable; see-through (next card judged against the card below) |
| 9 | `REVERSE` | After-effect: reverses player order |
| 10 | `BURNER` | After-effect: burns the pile; player plays again |
| other | `DEFAULT` | Next card must be ≥ |

Cards whose rank is in `alwaysPlayable` (JOKER/TRANSPARENT ranks) bypass `canPlay`. A pile burns when the top `burnCount` cards (4 with one deck, 6 with two) share a value.

### Activity Feed, Chat, Nudge

- **Events**: `GameSession` keeps the last `MAX_EVENTS` = 30 `GameEvent`s (`seq`, `type`, `playerId`, `username`, `cards`, `count`, `ts`; types `PLAYED`, `PLAYED_AGAIN`, `REVERSED`, `BURNED`, `PICKED_UP`, `FAILED_FLIP`, `FAILED_PLAY`, `READY`, `OUT`, `FINISHED`), persisted as `events` and returned in `GameStateView.events`. Missing on old items = empty list.
- **Chat** (`chat` action): `ChatMessageValidator` (1–300 code points after trim; empty dropped silently, too long → error), relays `{type:"chat", userId, username, text, ts}` to every connection of the game. Never stored or logged. Rate limiting relies on the WS stage throttling (burst 100, rate 50/s); in-memory counters do not work across Lambda instances.
- **Nudge** (`nudge` action): relays `{type:"nudge", userId, username, ts}` (`NudgeMessage`); nothing stored. The frontend plays `public/sounds/fart.mp3` (`lib/fartSound.ts`, see Frontend) and `NudgeButton` has a 3 s client cooldown. After login the callback sets sessionStorage `shithead_login_sound` and the lobby plays the sound once (queued until the first tap if audio is still locked).
- **Broadcast**: `broadcastState` posts a per-viewer `GameStateView` (own hand only) to every connection found via `game_session_id-index`; `GoneException` rows are deleted. WS errors go only to the sender as `{type:"error", status, message}`.

### Elo

- `EloService` (static, K = 32): multi-player expected score averaged over opponents; the shithead scores 0, everyone else 1. `calculateChanges` returns `before`/`after` per player.
- Bots are never rated: `updateElo` uses human players only and skips the update when the shithead is a bot (otherwise every human would gain with nobody losing); `loadRatings` does not query bot ids. Bots have no users-table row, so they never appear on `/leaderboard/top`; the session leaderboard lists them by seat name with rating 1000 and no change.
- On finish, `GameFunctionConfig.updateElo` writes new ratings to the users table and stores `eloChanges` (playerId → `EloChangeEntity{before, after}`) plus `eloUpdated=true` on the game. Nothing is stored when skipped (fewer than two profiles) or failed, so it can be retried. `SessionMapper.carryEloState` keeps both fields on saves that rebuild the entity from a `GameSession`.
- `GET /leaderboard/session/{id}` adds `eloBefore`/`eloAfter` when recorded; `/leaderboard/top` rows omit them. The session leaderboard shows the delta (`styles/elo-change.css`).
- `PlayerStateView.eloScore` carries the current rating to the table.

### Profiles, Admin, Browse, Voice, Account Deletion

- **Profile**: `GET /profile` returns `username` and `canClearGames` (= caller is in `game-admin`). `PUT /profile` changes the nickname (2–24 letters, digits, spaces, `-`, `_`; else 400). `UsernameReservationRepository` reserves unique nicknames (case-insensitive; a hidden claim row `__username__#<normalized>` with `owner_user_id`, written transactionally). Taken → 409. A rename is copied into every game item that contains the player.
- **Blocking**: users item `blocked` (missing = false), written only by `UserProfileRepository.setBlocked`; profile saves use `withoutBlockedFlag()` + ignore-nulls so they never reset it. `BlockedUserGuard.isBlocked` is checked at the top of `accountManagement` (except `DELETE /profile`), in join/leave/start/raise decks/state, the voice token and the WS handlers (play, setup, chat, nudge, pickup); response 403 `{"message":"Your account has been blocked."}`. Leaderboards are not guarded. The authorizer denies blocked users at `$connect`.
- **Admin** (`game-admin` group from `cognito:groups`): `GET /admin/users` (scan; skips `__username__#` claim rows and `__voice_` rows); `POST /admin/users/{userId}/block|unblock` (self-block refused; then best-effort closes the user's connections via `UserConnectionService` and removes them from unstarted lobbies via `LobbyMembershipService`); `POST /admin/doomsday` closes every WS connection and deletes **every** game item (profiles and Elo untouched).
- **Browse** (`GET /games`): unfinished games whose `ttl` has not passed, newest first, max 50, with `status` `waiting`/`in_progress`, `playerCount`, `decksCount` and `maxPlayers` = `GameSession.seatCapacity(...)`.
- **Voice** (LiveKit): `voiceEnabled` is part of the game config (only game-admins can enable it at create time) and exposed as `GameStateView.voiceEnabled`. `POST /games/{sessionId}/voice-token` → `{url, token, room}` (room = session id). Errors: 401 no `sub`, 404 unknown game, 403 non-player or voice off, 503 `Voice chat is not configured` when any `LIVEKIT_*` value is empty, 503 `Voice chat is paused until next month...` when `__voice_usage#YYYY-MM` (UTC, consistent read) is at 5,000 minutes or more, and 503 `Voice chat is unavailable right now.` when that item cannot be read or is not a number (fail closed). The token is an HS256 JWT built with the JDK only, valid 2 h, `video` grant for that room, microphone publishing only. Never log the secret or tokens.
- **Delete account** (`DELETE /profile`, `AccountDeletionService`): uses only the authorizer `sub` and `cognito:username`; allowed for blocked users. Order: remove from unstarted lobbies → close and delete WS connection rows → delete profile row (Elo) and own nickname claim → Cognito `AdminDeleteUser` (pool from `COGNITO_USER_POOL_ID` / `cognito.user-pool-id`). Earlier steps are best-effort; a Cognito failure returns 500 (no automatic retry). Success `{"deleted":true}`. Finished games keep the name until their TTL.

### DynamoDB Tables

| Table | Key | GSIs | TTL | Notes |
|---|---|---|---|---|
| `${project}-game-sessions` | `game_id` (S) | `user_id-index` (owner), `created_at-index` | `ttl` | One item per game |
| `${project}-users` | `user_id` (S) | `username-index`, `leaderboard-index` (`leaderboard_pk` + `elo_score`) | — | Profiles, nickname claim rows, voice usage rows |
| `${project}-connection-registry` | `connection_id` (S) | `game_session_id-index` | `ttl` | One row per WS connection |

**Game item** (`GameSessionEntity`): `game_id`, `user_id` (owner), `players` (list of `playerId`, `username`, `hand`, `faceUp`, `faceDown`, `out`, `ready`, optional `botType`), `discardPile`, `deck`, `currentPlayerId`, `started`, `starting`, `setupComplete`, `finished`, `shitheadId`, `eloUpdated`, `eloChanges`, `events`, `config` (`decksCount`, `burnCount`, `faceDownCount`, `faceUpCount`, `handCount`, `allowMixedHandAndFaceUpWhenDeckEmpty`, `allowFailedFaceUpPlay`, `voiceEnabled`, `cardRules` map rank→rule, `alwaysPlayable`, `canPlayAgain`), `created_at` (ISO string), `updated_at` (epoch s, stamped by every `GameSessionRepository.save` and by create-game), `ttl` (epoch s, set once at creation to +1 hour and carried unchanged by every save; every game expires about an hour after creation). Cards: `suit`, `value`, `rule`, `alwaysPlayable`.

**User item** (`UserProfile`): `user_id`, `username`, `avatarUrl`, `elo_score` (default 1000), `leaderboard_pk` (`global`), `blocked` (optional), `created_at` (seeded by init-user). Other rows keyed by `user_id`: nickname claims `__username__#<normalized>` (`owner_user_id`), voice usage `__voice_usage#YYYY-MM` (`minutes`, N), open voice sessions `__voice_open#<room>#<identity>` (`joined_at`, N; removed on leave). Code that scans users must skip the prefixed rows.

**Connection item**: `connection_id`, `game_session_id`, `user_id`, `ttl` (connect time + 1 hour).

### Terraform (`infra/terraform`)

- Root `main.tf` wires modules `cognito`, `lambda`, `dynamodb`, `api_gateway`, `cloudwatch`. State: S3 bucket `shithead-game-state-bucket`, key `shithead/terraform.tfstate`, **no lock table**. `ecr/` and `ecs/` are leftovers not referenced by `main.tf` (but root variables `vpc_id`/`subnets` are still required).
- `lambda/`: `java_lambda_functions.tf` (game API, alias, role), `glue.tf` (glue + authorizer), `janitor.tf` (EventBridge rule, target, permission), `iam.tf` (`lambda_exec`).
- `api_gateway/`: `api_gateway.tf` (REST resources, methods, MOCK `OPTIONS` integrations for CORS, gateway responses `cors_4xx`/`cors_5xx`, deployment, stage `prod`, and the WebSocket REQUEST authorizer with identity source `route.request.querystring.token`), `admin_games.tf` (`/admin/users...`, `/games`), `profile_delete.tf`, `voice.tf` (`/games/{sessionId}` and `/voice-token`), `decks.tf` (`/games/{sessionId}/decks`), `bots.tf` (`/games/{sessionId}/bots`), `livekit_webhook.tf` (`/livekit/webhook`, `authorization = "NONE"`, glue integration), `websocket.tf` (API, routes, integrations, `$default` stage with throttling and access logs), `permissions.tf` (`aws_lambda_permission` per WS route, REST `/*/*` on the `LIVE` qualifier, and REST `/*/*` for the glue function), `iam.tf` (API Gateway CloudWatch role, extra WS invoke permissions). `main.tf` still passes many legacy per-route variables (`join_game_invoke_arn`, ...); they all point to the game API alias, and `create_game_invoke_arn` is the glue function.
- `cognito/`: user pool (Google IdP, callback `${app_url}/auth/callback` and `http://localhost:5173/auth/callback`, ID/access token 1 h, refresh 30 d), triggers `post_confirmation` and `post_authentication` → glue, group `game-admin` (no members), REST Cognito authorizer.
- `cloudwatch/`: WebSocket access log group (14-day retention).

### Frontend (`frontend/`)

- **Routing** (`src/app/routes.tsx`, BrowserRouter with the `VITE_BASE_PATH` base as basename; `public/404.html` and the CI copy of `index.html` to `404.html` make deep links work on Pages): `/login`, `/auth/callback`, inside `MenuLayout` (desktop sidebar / phone navigation): `/lobby`, `/profile`, `/config`, `/leaderboard`, `/leaderboard/:sessionId`, `/games`, `/admin`; bare: `/room/:sessionId`, `/game/:sessionId`. Everything except Login and Lobby is lazy (`routeModules.ts` loaders + `components/LazyRoute.tsx` skeleton fallback); `components/PrefetchNavLink.tsx` and `lib/prefetch.ts` prefetch chunks and data on hover/focus/touch/visibility; `lib/chunkReload.ts` reloads once when a stale chunk fails after a deploy.
- **Data** (`src/app/data/`): TanStack Query; keys namespaced per user `['u', sub, ...]` (`keys.ts`); only profile, games and leaderboards are persisted to localStorage (`isPersistedQueryKey`; admin lists and game state stay in memory), 7-day max age, busted by `APP_VERSION`; `AppDataProvider` clears the cache on user switch, on account block and via `onAuthCleared`. Freshness (`TTL` in `queries.ts`): profile 5 min, global leaderboard 1 min, session leaderboard 5 min, games 5 s (and refetched every 5 s while the Games screen is mounted, paused in hidden tabs), admin users 30 s. Lobby top 3 and the Leaderboard share one 100-row `/leaderboard/top` query.
- **Auth** (`src/app/auth/`): `authStore.ts` keeps the session in localStorage `shithead_auth`; a timer refreshes 2 minutes before the ID token expires and `getFreshToken()` refreshes first when less than 1 minute remains; `api/client.ts` `apiFetch` retries once after a 401 (`refreshAfterUnauthorized`). `claims.ts` decodes the ID token (`getUserId`, `isAdmin`). `accountBlocked.ts` (403 with the blocked message → full-screen `AccountBlockedGate`), `accountDeleted.ts` (notice on `/login` after deletion).
- **API clients** (`src/app/api/`): `client.ts` (`apiFetch`, `throwForError`, `ApiError`), `game.ts` (REST calls incl. `raiseDecks`, `addBot`/`removeBot`, and `openGameSocket` with `?game_session_id=&token=`), `games.ts`, `leaderboard.ts`, `profile.ts`, `admin.ts`, `voice.ts`. REST calls go to `VITE_API_BASE_URL`; create-game is just another REST path.
- **Screens**: `Login`, `Lobby` (create/join, top players), `Room` (wait room, start, owner-only bot type select + "Add bot" and a "Remove" button on bot rows (`room-bots.css`; bot rows carry a "Bot" badge for everyone), "Add a second deck" for the owner of an unstarted, not-starting one-deck game with at least 5 players; `ONE_DECK_SEATS` = 5 is hardcoded because the state does not carry the card layout; polls state every second and uses a plain WebSocket without reconnect), `GameTable` (+ `GameTableRoute`), `Games` (browse), `Leaderboard` (name search, 10 per page with `Pager`; ranks stay global and the top-3 styling follows the rank), `Profile` (nickname, game maintenance for admins, delete account via `DeleteAccountDialog`), `GameConfig` (two-column layout, On/Off switches ordered Off then On, Card Rules description as a bullet list in `styles/rule-list.css`, rules picker, `RulesModal`, settings in localStorage `shithead_game_config`), `Admin` (users, block/unblock; 5 users per page on every screen size, loading skeleton renders the same rows with the pinned `--admin-row-height`).
- **Table components**: `PlayerPanel`, `Pile`, `Hand`, `FaceUp`, `FaceDownCount`, `CardFace`, `PeekWrap`, `SeatTableView`, `StarterPicker`, `ShitheadModal`, `GameFeed`, `ChatPanel` (feed + chat + `VoicePanel`), `ChatBubble`, `NudgeButton` (`TurnBadge` still exists but is not used). Phones (`(max-width: 700px)`) use a compact mode with `SeatChip`/`SeatPeek` (`table-mobile.css`); desktop fitting lives in `table-fit.css`, `table-desktop-fix.css`, `many-players.css` (up to 10 seats); the swap phase has its own `swap-phase-phone.css` and `swap-phase-desktop.css`.
- **Table behaviour**:
  - Connection: `lib/gameSocket.ts` (`GameSocket`) owns the table's WebSocket. A drop or failed attempt retries with backoff 1 s, 2 s, 4 s, ... capped at 15 s; an attempt not open after 10 s counts as failed; after 3 failures in a row the player sees "Still can't reach the game server"; automatic retries stop after 10 minutes of continuous failures. Page resume (`visibilitychange`, `pageshow` from bfcache), `online` and any send always reopen. `send()` never queues: it shows "Reconnecting..." and the player repeats the action. Every reconnect re-reads `GET /state`, and the table also polls it every 4 s.
  - The top bar reads `ROOMCODE · username · own Elo` (`styles/table-bar.css`). The username truncates first so the Elo stays visible on phones.
  - Seats show no PLAYING/NEXT text. The current turn is a gold solid inset ring and the next player a teal dashed inset outline (`styles/seat-indicators.css`); a player speaking in voice chat gets a pulsing green ring (`styles/voice-speaking.css`, solid under reduced motion). All are drawn inside the seat so scroll containers do not clip them; screen readers get visually hidden labels.
  - `PeekWrap` hover previews are portalled, `position: fixed`, and placed above the element (else below or beside). They are `pointer-events: none` until pinned, so they never block clicks on the pile; touch devices get a centred dialog instead. An opponent's peek opens whenever they have any card (hand, face-up or face-down); hand-only opponents get an invisible overlay target so the seat keeps its size. The phone-layout game log popover (`GameFeed`, `feed.css`) always renders its list so it is placed at full size, and takes pointer events so it can scroll.
  - Forced pick-up: when the current player has no legal move (`lib/rules.ts` `mustPickUp`), `GameTable` auto-selects the discard pile. Only hand cards are candidates, or face-up cards when the hand is empty; with only face-down cards nothing is forced (a blind flip is always allowed). It runs from an effect keyed on `moveSignature`, so it fires once per position.
  - Failed blind flip: the revealed card stays on screen for `FAILED_FLIP_NOTICE_MS` = 3500 ms (`GameTable.tsx`); `applyState` keeps it across the 4 s state polls, which never carry one.
  - Piles: draw and discard share one box width at every card size (`piles.css`). In the phone play phase the own cards grow with the spare height (`table-mobile.css`). The swap button reads "Swap selected cards" on phones and desktop (pair counts in its title).
- **Shared UI**: `Icon` (single Lucide-style SVG icon set so every device shows the same glyphs; add new icons there), `ErrorAlert` (auto-dismissing toasts, 3 s), `Pager` (`styles/pagination.css`), `Skeleton`, `Tabs`, `MenuLayout`.
- **lib/**:
  - `gameSocket.ts`: the reconnecting socket manager above.
  - `voice.ts`: module-level LiveKit connection; `livekit-client` loads lazily; `useVoiceSnapshot` feeds the speaking ring.
  - `fartSound.ts`: one shared Web Audio `AudioContext`, created or resumed by the first `pointerdown`/`touchend`/`keydown` on any screen (capture listeners installed when the module loads). Nudges that arrive before that are queued (max 50) and flushed `FLUSH_SPACING_MS` = 250 ms apart; at most `MAX_FART_VOICES` = 8 play at once (the oldest is cut). Falls back to `HTMLAudioElement` without Web Audio. `NudgeButton` shows a hint while a nudge waits for a tap.
  - `rules.ts`: pure mirror of the backend `canPlay` rules (`canPlayOn`, `playableSourceCards`, `mustPickUp`, `moveSignature`). The server stays authoritative, so change it together with `rules/` in Java.
  - `gameFeed.ts`: event sentences.
  - `sessionChat.ts`: 300-character limit, 200 messages kept in memory.
  - `selection.ts`, `tableAnimations.ts` (state-diff animations; respects reduced motion), `prefetch.ts`, `chunkReload.ts`.
- **LiveKit free-tier safeguards**: client side (`lib/voice.ts`) leaves after the tab is hidden 5 min, after being alone 3 min, after 90 min connected, on game end and on page close; `VoicePanel` tells users audio goes through LiveKit and uses the free allowance. Server side: the glue usage counter (409 on new voice games from 4,500 minutes, room deletion at 5,000) and the voice token guard (503 at 5,000, fail closed).
- **CSS conventions**: plain CSS, tokens in `styles/theme.css`; global sheets imported in `src/main.tsx`; **new styles go in a new CSS file** imported by the component/screen that needs it rather than growing existing sheets; scrollable areas use the `themed-scroll` class (`styles/scrollbars.css`).
- **Size budget**: `scripts/size-check.mjs` (gzip budgets for the entry JS and main CSS; lazy chunks informational). Vendor (`react*`) and `@tanstack` are manual chunks.

---

## 4. Code Patterns

### Lombok

Use Lombok rather than handwritten boilerplate: `@Data`/`@Getter`/`@Setter` on mutable beans, `@Builder` (+ `@Builder.Default` for collections and non-null defaults) for multi-field types, `@RequiredArgsConstructor` with `final` fields for Spring beans, `@Slf4j` for logging. DynamoDB beans (`entity/`, `UserProfile`) need `@DynamoDbBean` and a no-args constructor. Lombok does not copy `@Value` onto generated constructor parameters, so a bean that injects a property writes its constructor by hand (`VoiceTokenHandler`, `UserConnectionService`).

```java
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AccountManagementFunctionConfig {
    private final GameSessionRepository sessionRepo;
    private final UserProfileRepository userRepo;
    ...
}
```

### Records for DTOs

Immutable messages and API views are records: `PlayMessage`, `GameStateView`, `PlayerStateView`, `EloService.EloChange`, `ApiRoutes.RestRoute`. Add `@Builder` when callers need optional fields.

### Handler Beans

Handlers are `@Bean` methods returning `Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent>` (REST) or `Function<APIGatewayV2WebSocketEvent, APIGatewayProxyResponseEvent>` (WebSocket) on a `*FunctionConfig`. The bean name is not a deployed function. REST claims: `req.getRequestContext().getAuthorizer().get("claims")` (Cognito authorizer). The WS user id is read from the connection registry row (`user_id`, stored at `$connect` from the authorizer `sub`). Every REST response is built with the class's `corsResponse(...)` helper or `handler/JsonResponses`.

### Naming Conventions

| Element | Convention | Example |
|---|---|---|
| Services | `XxxService` | `EloService`, `AccountDeletionService` |
| Repositories | `XxxRepository` | `GameSessionRepository` |
| HTTP mapping helpers | `XxxHandler` | `VoiceTokenHandler` |
| Handler bean holders | `XxxFunctionConfig` | `GameFunctionConfig` |
| Entities | `XxxEntity` | `GameSessionEntity` |
| API views | `XxxView` | `GameStateView` |
| Mappers | `XxxMapper` | `SessionMapper` |
| Methods | camelCase verbs | `playCards()`, `shouldBurn()` |
| Constants / enum values | UPPER_SNAKE | `MAX_PLAYERS`, `CardRule.BURNER` |
| Booleans | `is`/`can`/`should` | `isStarted()`, `canPlay()` |
| DynamoDB attribute names | as stored (snake_case keys, camelCase nested) | `game_id`, `updated_at`, `discardPile` |
| Go | standard Go style, one concern per file | `janitor.go`, `voice_usage.go` |

---

## 5. Common Tasks

### Adding a Route to the Game API

1. Write the handler as a `Function` bean on a `*FunctionConfig` (or a branch in `accountManagement`).
2. Register it in `config/ApiRoutes.java` (`rest("GET", "/path/{id}", ...)` or `websocket("action", ...)`). Unregistered routes return 404.
3. Terraform (`infra/terraform/api_gateway/`):
   - REST: `aws_api_gateway_resource` (if new path) + method (`COGNITO_USER_POOLS`) + `AWS_PROXY` integration to the game API alias + MOCK `OPTIONS` method/integration/responses for CORS. **Add the new integration ids to the `triggers` hash of `aws_api_gateway_deployment.deployment`**, otherwise the `prod` stage never redeploys. The existing `/*/*` `LIVE` permission covers new REST paths.
   - WebSocket: `aws_apigatewayv2_route` targeting the `play_card` (or `pickup_pile`) integration plus an `aws_lambda_permission` for `execution_arn/*/<route>` (WS stage auto-deploys).
4. Return CORS headers on every response, including errors.
5. Add tests (`GameApiFunctionConfigTest`, the handler's test).

### Adding a Glue Feature (Go)

Add a file with the handler, a case in `App.Handle` (`dispatch.go`) if it is a new event shape or REST path (REST paths other than the webhook fall through to create-game), tests using `fake_dynamo_test.go`, and any env var/permission in `infra/terraform/lambda/glue.tf` / `iam.tf`. A REST route served by glue integrates with `create_game_invoke_arn` and goes into the deployment `triggers`; the existing glue `/*/*` permission covers it. Keep it free-tier friendly (no scheduled work more frequent than needed).

### Adding a Game Config Option

Touch all of: Go `GameConfig` + `buildGameConfig` (validation) in `glue-go/config.go`, `GameConfigEntity`, `GameConfig` (`fromEntity`/`toEntity`/default), `GameStateView` if clients need it, frontend `config/gameConfig.ts` and `screens/GameConfig.tsx`. Missing attributes on old items must read as the default.

### Adding a Card Rule

1. Add a `CardRule` value and `XxxRuleStrategy implements RuleStrategy` (optionally `AfterEffect`).
2. Register it in `RuleEngine.STRATEGIES`, and mirror its `canPlay` logic in `frontend/src/app/lib/rules.ts`.
3. Allow it in Go `validCardRuleValues` (and `rankValues` mapping if it affects `alwaysPlayable`/`canPlayAgain`).
4. Expose it in the frontend rules picker and `RulesModal`.
5. Tests in `rules/` and `glue-go/config_test.go`.

### Adding a Repository

Entity bean in `entity/` (or `model/`), `XxxRepository` in `repository/` using `DynamoDbEnhancedClient`, table name from `application.properties` (`${ENV_VAR:default}`), injected via `final` field. Add IAM actions to `game_api` policy in `java_lambda_functions.tf`.

### Adding a Frontend Screen

Add a loader to `routeModules.ts`, a `routeScreen(...)` + `<LazyRoute>` route in `routes.tsx`, a `PrefetchNavLink` in `MenuLayout` if it is a menu item, a query in `data/queries.ts` (decide explicitly whether its key may be persisted), and a new CSS file for its styles. Run `npm run build && npm run size`.

---

## 6. Repository Conventions

### How Tasks Are Shipped

- Work on a feature branch `<type>/<short-description>` (e.g. `feat/ten-players`, `fix/janitor-stale-lobbies`) and open a PR to `main`.
- Commits follow Conventional Commits: `<type>(<scope>): <description>` with types `feat`, `fix`, `refactor`, `test`, `chore`, `docs`, `style`, `ci`, `revert`.
- CI (`Code Quality & Tests`) must be green before merge. PRs are merged with **merge commits** (no squash/rebase).
- Merging to `main` deploys: backend paths (`backend/**`, `glue-go/**`, `infra/**`, `pom.xml`, `Dockerfile`) run **Deploy Backend** (build JAR + glue zip, `terraform apply` to production); `frontend/**` or the workflow file run **Deploy Frontend** to GitHub Pages. `workflow_dispatch` runs both.
- Terraform is applied **only by CI**. Never run `terraform apply`/`plan` against the real state locally, and never let two backend deploys overlap (no state lock).

### CI (`.github/workflows/ci-cd.yaml`)

Jobs: `changes` (`dorny/paths-filter@v4`), `test` (Java verify with `-P codeQuality`, Go vet + test, frontend `npm ci && npm run build`, `npm run size`), `deploy-backend`, `deploy-frontend`. Runners pinned to `ubuntu-24.04`. Actions: `actions/checkout@v7`, `actions/setup-java@v6` (temurin 17), `actions/setup-go@v7` (1.24), `actions/setup-node@v7` (20), `aws-actions/configure-aws-credentials@v6`, `hashicorp/setup-terraform@v4` (1.6.6, wrapper off), `actions/configure-pages@v6`, `actions/upload-pages-artifact@v5` (include hidden files for `.nojekyll`), `actions/deploy-pages@v5`. `deploy-backend` first waits for `shithead-*` functions to settle and sets SnapStart to `None` on functions that have it, then `terraform apply -parallelism=3` turns it back on from the Terraform config. Keep that pairing intact.

### Required GitHub Configuration (names only)

| Kind | Names |
|---|---|
| Secrets | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` |
| Variables | `AWS_REGION`, `VPC_ID`, `SUBNETS_JSON`, `APP_URL`, `LIVEKIT_URL`, `VITE_API_BASE_URL`, `VITE_WS_BASE_URL`, `VITE_COGNITO_DOMAIN`, `VITE_COGNITO_CLIENT_ID`, optional `VITE_BASE_PATH`, `VITE_COGNITO_REDIRECT_URI`, `VITE_COGNITO_LOGOUT_URI` (derived from `APP_URL` when set) |

Empty LiveKit values keep voice chat off (token 503, every webhook rejected). Frontend local env: `frontend/.env` with the `VITE_*` names from `.env.example`.

---

## 7. Clean Code Guidelines

- **Single responsibility**: `GameSession` owns state transitions, `RuleEngine` evaluates rules, `SessionMapper` converts, `*FunctionConfig` maps HTTP/WS to domain calls, services hold cross-cutting work.
- **Open/closed**: a new card rule is a new strategy plus registration.
- Prefer short methods at one level of abstraction; **return early** for guard clauses.
- Avoid boolean parameters that hide intent; prefer enums or two methods.
- Order inside a class: constants, fields, constructors, public methods, private methods, nested types.
- **Fail fast at boundaries** (handler entry, Go `config.go` validation); trust internal invariants.
- **No over-engineering**: three similar lines beat a premature abstraction.
- **Delete unused code** instead of keeping compatibility shims, except where old clients or old DynamoDB items must still work (e.g. legacy `handIndex`/`faceUpIndex`, missing attributes reading as defaults); say so in a comment.
- **Static for stateless utilities**: `RuleEngine`, `SessionMapper`, `EloService`.
- Backwards compatibility of stored data matters: items live up to an hour and new attributes must tolerate absence.

---

## 8. Design Patterns in Use

| Pattern | Where |
|---|---|
| Front controller / route table | `LambdaHandler` → `GameApiFunctionConfig.dispatch` → `ApiRoutes`; Go `App.Handle` |
| Strategy | `RuleStrategy` implementations |
| After-effect hook | `AfterEffect` (REVERSE, BURNER) |
| Builder | `@Builder` on domain types, entities, DTOs |
| Repository | `GameSessionRepository`, `UserProfileRepository`, `UsernameReservationRepository` |
| Static factory | `GameConfig.defaultGameConfig()`, `GameConfig.fromEntity()`, `NudgeMessage.build()` |
| State machine | `GameSession` with `PlayResult` + `InvalidReason` |
| Optimistic/conditional write | Janitor conditional delete; nickname claim transaction; voice leave (`REMOVE` with `attribute_exists`) + atomic `ADD` counter |
| Null object | `Optional<Card>` from `Deck.draw()`; `@Builder.Default` empty collections |

---

## 9. Error Handling

- There is **no custom exception hierarchy**. Handlers translate outcomes to HTTP status codes directly with JSON bodies (`{"message": "..."}` for user-facing text; some older paths use `{"error": "..."}`; the frontend `throwForError` reads either).
- Domain rule violations in `GameSession` are `IllegalStateException`s (game full, not enough cards), caught at the handler and returned as 409 with the message (`conflictResponse`). Messages shown to players must not contain double quotes (the body is built by string concatenation).
- Invalid moves are not exceptions: `PlayResult.INVALID` + `InvalidReason` → `PlayErrorMessages`.
- WebSocket errors are posted to the sender only: `{type:"error", status, message}`; the Lambda also returns that status.
- Service outcomes are enums where callers branch (`AccountDeletionService.DeletionOutcome`, `AdminUserService.BlockOutcome`).
- Best-effort cleanup (connections, lobbies, Elo) logs failures and continues; AWS SDK failures are caught as `SdkException`.
- Go: return `jsonResponse(status, ...)` for client errors, wrapped `error`s for infrastructure failures. The janitor skips (never deletes) on uncertainty; the voice guards fail closed (Java token 503 when usage cannot be read; Go create-game errors when it cannot read usage).
- Every REST response, success or error, carries CORS headers.

---

## 10. Testing Guidelines

- Java tests mirror source packages under `backend/src/test/java/com/tamaspinter/backend/` (e.g. `game/GameSessionTest`, `game/GameSessionCapacityTest`, `game/GameSessionInvalidReasonTest`, `config/GameApiFunctionConfigTest`, `config/GameFunctionConfigTest`, `handler/VoiceTokenHandlerTest`, `LambdaHandlerTest`, `service/AccountDeletionServiceTest`, `mapper/SessionMapperEloChangesTest`).
- Go tests live next to the code (`*_test.go`): in-memory DynamoDB fake `fake_dynamo_test.go` (extended for `ADD`/`ReturnValues` in `voice_fake_test.go`), `strictDynamo` in `janitor_test.go` (rejects unused expression names like DynamoDB does), `httptest` servers for JWKS (`auth_test.go`, generated RSA keys), `GetConnection` (`connection_check_test.go`) and the LiveKit RoomService (`voice_roomservice_test.go`).
- The frontend has no automated tests; `npm run build` and `npm run size` are the CI gates.
- No coverage tool is configured (no JaCoCo); every new class or behaviour still gets a test.
- Naming: descriptive `subject_scenario_expectation` style, e.g. `admin_blockingSelf_isRefused` (`AccountManagementFunctionConfigTest`).
- Structure tests with `// Given`, `// When`, `// Then` comments.
- Mockito: `mockito-core` only (no `mockito-junit-jupiter`); use `Mockito.mock()` or `@Mock` with `MockitoAnnotations.openMocks(this)` in `@BeforeEach`.
- Deterministic decks: `new Deck(List.of(...))` (no shuffle) and `session.setDeck(...)`.
- Floating point: `assertEquals(expected, actual, 0.001)`.

---

## 11. Logging

- Java: `@Slf4j`; never `System.out`. `log.error(msg, e)` with the exception for failures, `log.warn` for recoverable issues, `log.info` for significant events. Go: `log.Printf`.
- **Never log** tokens, LiveKit secrets, webhook bodies, chat text, nudges, or full request bodies. `application.properties` keeps `AWSLambdaUtils` at WARN because payloads contain bearer tokens. The room deletion at the voice limit logs counts only.
- Use placeholders with ids: `log.error("Elo update failed for session {}", sessionId, e)`.
- Logs go to CloudWatch (Lambda default groups; WS API access logs in `/api-gateway/${project}-websocket`, 14 days).

---

## 12. Security

- User identity comes **only** from authorizer claims (`sub`, `cognito:username`, `cognito:groups`), never from request bodies. WS handlers take the user id from the connection row the glue wrote from the authorizer `sub`.
- Admin features require the Cognito `game-admin` group (checked server-side: Java `hasAdminGroup`, Go `isGameAdmin`). Membership is assigned manually in Cognito; Terraform only creates the group.
- Blocked users are rejected by the WS authorizer and `BlockedUserGuard` (except self-deletion).
- `$default` WS route is a no-op so clients cannot broadcast arbitrary data.
- `POST /livekit/webhook` is the only REST route without Cognito; its LiveKit HS256 signature (issuer + body hash) is the authentication, and empty LiveKit credentials reject everything.
- IAM is least-privilege per role: `game_api_exec` (Java) and `lambda_exec` (Go); `AdminDeleteUser` is scoped to the one pool.
- Validate inputs at the boundary (Go config validation, chat length, setup index checks, nickname pattern).
- No secrets, account ids or tokens in code, docs, logs or Terraform defaults; they come from GitHub secrets/variables.

---

## 13. AI Model-Specific Guidelines

### Before Making Changes

1. Read the relevant files first; verify claims here against the code when in doubt.
2. Follow existing patterns (route table, `Function` beans, records, Lombok, CORS helpers).
3. Add tests; run `mvn -q -f backend/pom.xml verify -P codeQuality`, `go vet ./... && go test ./...`, and for frontend changes `npm run build && npm run size`.
4. Keep cost at zero: no new always-on resources, no provisioned concurrency, no paid services.

### Key Files

| File | Why it matters |
|---|---|
| `backend/.../LambdaHandler.java` | Java entry point; writes the response unwrapped |
| `backend/.../config/ApiRoutes.java` | Route table |
| `backend/.../config/GameApiFunctionConfig.java` | Event-shape dispatch, 404 with CORS |
| `backend/.../config/GameFunctionConfig.java` | Game REST + WS handlers, raise decks, broadcast, Elo |
| `backend/.../config/AccountManagementFunctionConfig.java` | Profile, admin, browse, delete account |
| `backend/.../game/GameSession.java` | State machine, `MAX_PLAYERS`, `seatCapacity`, `InvalidReason` |
| `backend/.../handler/VoiceTokenHandler.java` | Voice token and its usage guard |
| `backend/.../mapper/SessionMapper.java` | Domain ↔ entity (all card fields incl. suit round-trip) |
| `glue-go/dispatch.go`, `games.go`, `config.go`, `auth.go`, `janitor.go`, `connection_check.go`, `voice_*.go` | Go glue |
| `infra/terraform/api_gateway/api_gateway.tf` | REST API and deployment `triggers` |
| `infra/terraform/lambda/java_lambda_functions.tf`, `glue.tf` | Lambdas, env vars, IAM |
| `.github/workflows/ci-cd.yaml` | CI/CD |
| `frontend/src/app/routes.tsx`, `data/`, `auth/authStore.ts`, `screens/GameTable.tsx`, `lib/gameSocket.ts` | Frontend core |

### Gotchas (learned in production)

- **REST stage redeploys only when the deployment `triggers` hash changes.** It hashes integration ids and the invoke ARNs (ids alone do not change when a route is retargeted). A new method/integration not added to the hash is live in the API but not in stage `prod`.
- **Java entry point must write the API Gateway response unwrapped** (`LambdaHandler`). The generic Spring Cloud Function adapter double-wraps it (status 200, no CORS), which browsers show as "Failed to fetch".
- **New routes need both** an `ApiRoutes` entry and Terraform (resource/method/integration/OPTIONS + triggers for REST; route + permission for WS). Either alone gives 404 or 403/"Missing Authentication Token".
- **CORS headers on every response**, including 4xx/5xx from the Lambda; API Gateway's own errors are covered by gateway responses `cors_4xx`/`cors_5xx`. When adding an HTTP method, update both the MOCK `OPTIONS` integration response `Access-Control-Allow-Methods` in Terraform (preflight) and the `CORS_HEADERS` map of the Java class that answers the route (`DELETE` is only in `AccountManagementFunctionConfig`).
- **DynamoDB rejects expression attribute names or values the expressions do not use** (`ValidationException`). Before PR #95 the janitor's conditional delete passed `#gid`/`#created`/`#ttl` without using them, so DynamoDB refused every janitor delete and abandoned started games were never removed. Name only what each request uses; `strictDynamo` in `janitor_test.go` checks this, the shared fake does not.
- **Terraform state has no lock**: backend deploys must not overlap. Wait for a running `Deploy Backend` to finish before merging another backend PR.
- **SnapStart**: CI disables it before apply and Terraform re-enables it; never remove `snap_start` or route API Gateway to `$LATEST` instead of `LIVE`.
- **Cognito group `game-admin` is assigned manually** (console/CLI by the owner), never by Terraform or code.
- **DynamoDB TTL deletion lags** (minutes to hours). Code that lists games filters `ttl <= now` itself (`GameBrowseService`), and the janitor ignores connection rows whose `ttl` has passed.
- **Game TTL is fixed at creation + 1 hour**; long games disappear after that regardless of activity.
- **WebSocket limits** (API Gateway quotas): idle connections close after 10 minutes and any connection after 2 hours. There is no keep-alive. The table (`GameSocket`) reconnects and re-reads state; the Room relies on its 1 s state poll. Connection rows carry a 1-hour `ttl`, so a socket open longer than an hour can lose its row once TTL deletion runs (broadcasts then miss it), and the janitor already treats such a row as gone.
- **LiveKit free tier** (5,000 participant-minutes a month): keep both layers, the client safeguards in `lib/voice.ts` and the server guards (glue counter, 4,500-minute create guard, room deletion at 5,000, token 503 at 5,000), and keep voice admin-only. The counter only works while LiveKit posts webhooks to `/livekit/webhook`.
- **Users table holds non-profile rows** (`__username__#`, `__voice_usage#`, `__voice_open#`). Any scan of users must skip them, as `AdminUserService` does.
- **Root `vpc_id`/`subnets` variables** are still required by Terraform even though ECS is unused.

---

## 14. Documentation Update Protocol

Update this file when:
- A package, significant class, route, table attribute or Terraform module is added, removed or renamed.
- A gotcha is discovered or a known issue is fixed.
- A dependency, CI action or runtime version changes significantly.
- A convention is adopted or changed.

Add a change log row (date, change, model) for every update. Keep facts verified against the code; delete statements that are no longer true instead of appending corrections.

### Change Log

| Date | Change | Model |
|---|---|---|
| 2026-02-20 | Initial file created | Claude Sonnet 4.6 |
| 2026-02-20 | Added Builder pattern, Records, constructor injection, test naming, exception hierarchy | Claude Sonnet 4.6 |
| 2026-02-20 | Added PMD, Checkstyle, SpotBugs setup and codeQuality profile docs | Claude Sonnet 4.6 |
| 2026-10-09 | Added game activity feed (`events` attribute) and session chat (`chat` WebSocket route) | Claude Sonnet 5.5 |
| 2026-10-09 | Added nudge (`nudge` WebSocket route, fart sound) and login sound | Claude Sonnet 5.5 |
| 2026-10-10 | Added `DELETE /profile` account deletion (AccountDeletionService, Cognito AdminDeleteUser via `cognitoidentityprovider` SDK module) | Claude Haiku 5.5 |
| 2026-10-10 | Full refresh to match the Go glue + single Java Lambda architecture and the features added since (incl. PRs #84-#91: pagination, seat indicators, table bar, PeekWrap, `lib/rules.ts` auto pick-up) | Claude Opus 5.5 |
| 2026-10-10 | Final refresh for PRs #92-#108: second deck route and seat capacity, janitor lobbies + `GetConnection` + unused-name fix, LiveKit webhook and voice usage guards, game socket reconnect, Web Audio nudge queue, forced pick-up candidates, peek, flip notice, piles, speaking ring, log popover, swap label | Claude Opus 5.5 |
| 2026-10-11 | Added beginner bots: `bot/` package, `POST /games/{sessionId}/bots`, in-memory bot turns, `botType` player attribute, Elo exclusion | Claude Opus 5.5 |
| 2026-10-11 | Added the card-counting `IntermediateBotStrategy` (scores legal plays from public info: shedding, burns, pressure on the next player); `PublicMoveObserver.handCardsPutFaceUp` | Claude Opus 5.5 |
