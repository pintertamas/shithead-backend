# AI-CONTEXT.md — shithead-backend

> **SINGLE SOURCE OF TRUTH** for AI models working with this codebase.
> Update this file when patterns change, new components are added, or architectural decisions are made.
>
> Last Updated: 2026-10-09

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
9. Error Handling Best Practices
10. Testing Guidelines
11. Logging
12. Security Best Practices
13. AI Model-Specific Guidelines
14. Documentation Update Protocol

---

## 1. Repository Overview

A serverless Java backend for the card game *Shithead*. Players join sessions; the game is dealt and managed entirely server-side; real-time state is broadcast to clients over WebSocket.

### Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.4.5 + Spring Cloud Function 4.1.2 |
| Compute | AWS Lambda: one Java game API function (`spring-cloud-function-adapter-aws`, `gameApi`) plus one Go glue binary (`glue-go/`, `provided.al2023`, arm64) |
| API | AWS API Gateway — REST (lobby) + WebSocket (gameplay) |
| Persistence | AWS DynamoDB (Enhanced Client 2.x) |
| Auth | AWS Cognito / OAuth2 JWT (`spring-boot-starter-oauth2-resource-server`) |
| Build | Maven — always use `./mvnw`, not `mvn` |
| Infra | Terraform (`infra/`) |
| Boilerplate | Lombok 1.18.x |
| Testing | JUnit 5 (junit-bom 5.12.2), Mockito 5.17.0 |

### Directory Structure

```
shithead-backend/
├── backend/
│   ├── src/main/java/com/tamaspinter/backend/
│   │   ├── config/          # Spring @Configuration — Lambda functions, SecurityConfig
│   │   ├── controller/      # REST controllers (HealthController)
│   │   ├── entity/          # DynamoDB entity POJOs
│   │   ├── exception/       # Custom exception hierarchy
│   │   ├── game/            # Core game logic (GameSession, GameConfig, PlayResult)
│   │   ├── handler/         # Scheduled/event handlers
│   │   ├── mapper/          # SessionMapper (domain ↔ entity)
│   │   ├── model/           # Domain types (Card, Player, Deck, Suit, CardRule)
│   │   │   └── websocket/   # WebSocket message DTOs (Records)
│   │   ├── repository/      # DynamoDB repositories
│   │   ├── rules/           # Rule engine + strategy implementations
│   │   └── service/         # Stateless services (EloService)
│   └── src/test/java/com/tamaspinter/backend/
│       └── <mirrors source package structure exactly>
├── glue-go/                 # Go glue Lambda (create-game, WS connect/disconnect/default, authorizer, init-user)
├── infra/                   # Terraform
├── frontend/
│   └── src/app/
│       ├── components/       # Shared UI, including persistent menu navigation
│       ├── config/           # Browser-local next-game settings
│       └── screens/          # Lobby, profile, game configuration, leaderboard, and game UI
└── AI-CONTEXT.md
```

### Package Reference

| Package | Responsibility |
|---|---|
| `game` | `GameSession` state machine, `GameConfig`, `PlayResult` enum |
| `rules` | `RuleEngine`, `RuleStrategy`, per-rule strategies, `AfterEffect` |
| `model` | Value types: `Card`, `Player`, `Deck`, `Suit`, `CardRule` |
| `model.websocket` | Immutable message Records: `PlayMessage`, `PickupMessage`, `GameEnded` |
| `entity` | DynamoDB-annotated POJOs |
| `mapper` | `SessionMapper` — domain ↔ entity conversion |
| `config` | `GameApiFunctionConfig` (the `gameApi` dispatcher bean), `ApiRoutes` (the route table), `GameFunctionConfig` and `AccountManagementFunctionConfig` (the handler `@Bean`s the table points at) |
| `repository` | `GameSessionRepository`, `UserProfileRepository` |
| `service` | `EloService` — pure stateless computations |
| `exception` | Custom exception hierarchy |

---

## 2. Essential Commands

```bash
# Run all tests  (Maven wrapper lives in backend/)
cd backend && ./mvnw test

# Build fat JAR for Lambda
cd backend && ./mvnw package

# Run static analysis (PMD + Checkstyle + SpotBugs)
cd backend && ./mvnw verify -P codeQuality

# Deploy infrastructure
cd infra && terraform apply

# Deploy Lambda code
./deploy.sh

# Glue Lambda (Go): vet and test, then build the arm64 zip that Terraform deploys
cd glue-go && go vet ./... && go test ./...
cd glue-go && GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -tags lambda.norpc -o build/bootstrap . \
  && cd build && zip -q glue.zip bootstrap

# Terraform validation (no AWS calls; needs the two build artifacts above)
cd infra/terraform && terraform init -backend=false && terraform validate
```

### Static Analysis (`-P codeQuality`)

The three tools are **skipped by default** and only enabled via the `codeQuality` Maven profile.

| Tool | Config file | Maven phase | What it checks |
|---|---|---|---|
| Checkstyle | `src/main/resources/checkstyle/checkstyle.xml` | `validate` | Formatting, naming, whitespace, line length (max 150) |
| PMD | `src/main/resources/pmd/ruleset.xml` | `process-test-classes` | Best practices, code style, design, error-prone patterns |
| SpotBugs | `src/main/resources/spotbugs/excludes.xml` | `process-test-classes` | Bytecode-level bug patterns |

All three fail the build on violations. To suppress a specific violation inline:

```java
@SuppressWarnings("checkstyle:MagicNumber")   // Checkstyle
@SuppressWarnings("PMD.CyclomaticComplexity") // PMD
@SuppressFBWarnings("NP_NULL_ON_SOME_PATH")   // SpotBugs (requires spotbugs-annotations dep)
```

---

## 3. Architecture

### Request Flow

```
Client
  ├── REST (HTTP)  → API Gateway → game-api Lambda (gameApi dispatcher)
  │                                  ├── join/leave/start-game, state, leaderboard
  │                                  ├── Profile API (/profile)
  │                                  ├── Admin cleanup (/admin/doomsday; Cognito game-admin only)
  │                                  ├── Admin users (GET /admin/users, POST /admin/users/{id}/block|unblock; game-admin only)
  │                                  └── Lobby browser (GET /games; any signed-in user)
  │                → API Gateway → glue Lambda (create-game, Go)
  └── WebSocket    → API Gateway → authorizer Lambda (Go, $connect only)
                   → API Gateway → glue Lambda ($connect, $disconnect, $default; Go)
                   → API Gateway → game-api Lambda (play, setup, pickup)
                                         ↓
                                   GameSession (state machine)
                                         ↓
                                   DynamoDB (save entity)
                                        ↓
                                   broadcastState() → WebSocket clients
```

### Lambda Layout

| Function | Runtime | Built from | Serves |
|---|---|---|---|
| `${project}-game-api` | java17, 1024 MB, SnapStart, alias `LIVE` | `backend/` fat JAR, `SPRING_CLOUD_FUNCTION_DEFINITION=gameApi` | Every game/profile/admin REST route and the `play`, `setup`, `pickup` WebSocket routes |
| `${project}-glue` | provided.al2023, arm64 | `glue-go/` (`bootstrap` in `glue-go/build/glue.zip`) | REST `create-game`, WS `$connect`, `$disconnect`, `$default`, Cognito post-confirmation/post-authentication (init user) |
| `${project}-authorizer` | provided.al2023, arm64 | same zip as glue | WebSocket REQUEST authorizer (Cognito ID token, denies `blocked` users) |

The glue zip is deployed twice on purpose: the Cognito user pool references the init-user trigger, and the authorizer needs the pool id, so a single function would create a Terraform dependency cycle. The glue function gets no pool id.

### One Game API Lambda (`gameApi`)

`config/GameApiFunctionConfig` receives the raw event as `Map<String,Object>` and decides by shape:

- WebSocket event (`requestContext.routeKey` or `eventType` present): the route key, or the body `action` when the route key is missing, selects the handler.
- REST proxy event (`httpMethod` present): `resource`/`path` plus the method select the handler. Path parameters from API Gateway are kept; for events without them they are read from the template.
- Anything else, and any route not in the table, returns `404` JSON with CORS headers.

The event is converted with `ObjectMapper` to `APIGatewayProxyRequestEvent` or `APIGatewayV2WebSocketEvent` and passed to the existing handler `Function` beans. Handler logic is not duplicated in the dispatcher.

### Adding a Route to the Game API

1. Open `backend/src/main/java/com/tamaspinter/backend/config/ApiRoutes.java`.
2. Add one line to the constructor:
   - REST: `rest("GET", "/games/{gameId}", game.someHandler());` (the handler is a `Function` bean on a `*FunctionConfig`).
   - WebSocket: `websocket("chat", game.playCardWS());` (the route key is the `action` the client sends; `chat` is routed inside `playCardWS` by action).
3. Add a test case to `GameApiFunctionConfigTest` if the route has its own behaviour.
4. Terraform needs no change for routes that stay within the existing REST resources. A new REST resource or WebSocket route also needs its API Gateway resource, method, integration and `aws_lambda_permission` in `infra/terraform/api_gateway/`, pointed at `game_api_alias_arn` / `game_api_function_name` from `infra/terraform/main.tf`.

### Glue Lambda (Go, `glue-go/`)

One binary, dispatched by event shape in `dispatch.go`:

- Cognito trigger (`triggerSource`): `init-user` seeds `username`, `leaderboard_pk`, `elo_score` and `created_at` with `if_not_exists`, so a user-edited name survives later logins. The event is returned unchanged.
- WebSocket REQUEST authorizer (`type` = `REQUEST`, `methodArn`): verifies the RS256 Cognito ID token from `?token=` or a bearer header (issuer, audience = app client id, `token_use` = `id`, expiry). The JWKS is cached and refetched when a `kid` is unknown, with a one-minute cooldown. A user whose users-table item has `blocked` = true gets a `Deny` policy.
- REST `create-game`: requires the Cognito `sub`; validates `config` strictly (`decksCount` 1 or 2, `burnCount` derived, face/hand counts integers 0..10, `cardRules` values from `DEFAULT|JOKER|SMALLER|TRANSPARENT|REVERSE|BURNER`, boolean options must be booleans, unknown keys ignored). Invalid config returns 400 and writes nothing. It also hands over or deletes the caller's unstarted lobbies first.
- WebSocket `$connect` / `$disconnect` record and remove connections. `$default` is a deliberate no-op returning 200, so clients cannot broadcast to other players.

Tests use an in-memory fake of the DynamoDB interface (`fake_dynamo_test.go`) and an httptest JWKS server with a generated RSA key (`auth_test.go`).

User nicknames are stored in the users DynamoDB table. `UserProfileService` creates profiles and reserves unique default nicknames; `UsernameReservationRepository` normalizes nickname comparisons case-insensitively, checks legacy profile rows, and transactionally reserves a nickname with a hidden same-table claim record. The game API Lambda needs Scan, UpdateItem, DeleteItem, and TransactWriteItems permissions on that table. Starting a game is a two-phase flow: the owner persists `starting=true`, then sends a `setup` WebSocket action to broadcast that state immediately (one-second lobby polling remains a fallback), and finalizes the deal after a short transition. Once dealt, the game enters a persisted card-swap/readiness phase; play is blocked until every player is ready.

### Game State Machine (`GameSession`)

`GameSession.playCards(List<Card>)` returns one of three outcomes:

| Result | Meaning |
|---|---|
| `SUCCESS` | Cards played; after-effects applied; turn advances unless the card or a pile burn grants a replay |
| `PICKUP` | Player picks up the pile (explicit or blind flip failure) |
| `INVALID` | Move rejected — wrong turn, illegal card, or game finished |

Card source priority: **hand → faceUp → faceDown** (blind flip). The game client sends an explicit source and index for a selected card; face-down cards stay hidden from the client and are revealed by the server after the blind flip. `allowMixedHandAndFaceUpWhenDeckEmpty` is stored per game, and permits a same-value hand/face-up combination only when that game's draw pile is empty. `allowFailedFaceUpPlay` (default `false`, missing attribute reads as `false`) applies when a player with an empty hand plays an illegal face-up selection: the selected card(s) go onto the pile and the player immediately picks up the whole pile, including them (`PlayResult.PICKUP`, same as a failed blind flip). When off, that play is `INVALID`. The option is exposed to clients as `allowFailedFaceUpPlay` on `GameStateView`; the `/config` screen groups it with the mixed hand/face-up toggle under "Face-up cards".

After dealing, `GameSession` sorts each player's hand and face-up cards by rank and suit, but preserves face-down order. Players may swap one hand card with one face-up card before marking themselves ready; a ready player cannot alter cards. `setupComplete` gates every play action until all players are ready. Four/six-card burns also grant the player another turn, even when the played rank reverses player order.

The WebSocket `playSelections` path must run `finishSuccessfulPlay` after a successful hand, face-up, face-down, or mixed selection so after-effects execute and turn ownership advances. `setup` actions use the same WebSocket Lambda route for readiness and card swaps. Failed blind flips include a transient revealed card in the broadcast; the browser hides that notice after about one second. Keep these behaviors in sync if adding another selection source.

**Activity feed and session chat.** `GameSession` keeps the 30 most recent `GameEvent`s (`seq`, `type`, `playerId`, `username`, `cards`, `count`, `ts`) and persists them as the optional `events` list on the game item (`GameEventEntity`). Items written before the feed existed read as an empty list. The list is returned as `events` on `GameStateView`, so REST `/state` and WebSocket broadcasts both carry it. Events are recorded inside `GameSession` through `recordEvent`/`commitPlay`/`pickUpPile`; new move paths must go through those helpers. Session chat is the `chat` WebSocket action on the `play_card` integration, so it needs no extra Lambda. The handler checks membership, validates text with `ChatMessageValidator` (1–300 characters after trimming; empty is dropped silently), and relays `{type:"chat", userId, username, text, ts}` to every connection of the game. Chat is never written to DynamoDB or logged. Rate limiting relies on the stage's API Gateway throttling, because an in-memory counter is not reliable across Lambda instances.

**Nudge (fart sound).** The `nudge` WebSocket action, also routed to `playCardWS` (route in `websocket.tf`, permission in `permissions.tf`), checks membership and relays `{type:"nudge", userId, username, ts}` to every connection of the game via `NudgeMessage`. Nothing is stored or logged. The frontend plays `frontend/public/sounds/fart.mp3` (`lib/fartSound.ts`) for a nudge received from anyone (the sender hears its own echo); the button (`components/NudgeButton.tsx`) has a 3-second client cooldown. After login, the auth callback sets the sessionStorage flag `shithead_login_sound` and the lobby plays the sound once; if autoplay is blocked it plays on the first click or key press (`unlockAudio`).

The frontend keeps `/lobby`, `/config`, `/profile`, and leaderboard routes inside a shared `MenuLayout` with persistent desktop sidebar and mobile top navigation. The `/config` screen saves next-game preferences in browser `localStorage` (`shithead_game_config`). It also has a "How to play" popup (`components/RulesModal.tsx`, styles in `styles/rules.css`) whose special-card list is generated from the current, unsaved selections. Lobby game creation sends those settings to the Go `create-game` handler in the glue Lambda (`glue-go/games.go`). Each game stores its own config in DynamoDB. Deck count is fixed to the selected 1 or 2 decks; the burn threshold follows it (4 or 6 cards). Selected card rules use the existing `CardRule` strategies and are stored on the game/cards.

### Card Rule Engine

`RuleEngine` is a static dispatcher. Each `CardRule` maps to a `RuleStrategy`:

| Card Value | Rule | Behaviour |
|---|---|---|
| 2 | `JOKER` | Playable on anything; next card can be any value |
| 6 | `SMALLER` | Next card must be ≤ 6 |
| 8 | `TRANSPARENT` | See-through; delegates to rule below |
| 9 | `REVERSE` | After-effect: reverses player order |
| 10 | `BURNER` | After-effect: clears pile; player plays again |
| other | `DEFAULT` | Standard ≥ rule |

Cards with `alwaysPlayable = true` (values 2 and 8) bypass all `canPlay` checks.

---

## 4. Code Patterns

### Lombok — Full Usage

Use the full set of Lombok annotations consistently. Do not write manual getters, setters, constructors, or `equals`/`hashCode`.

```java
// Mutable class with all boilerplate
@Data
@Builder
@Slf4j
public class Player {
    private final String playerId;
    private final String username;

    @Builder.Default
    private List<Card> hand = new ArrayList<>();

    @Builder.Default
    private List<Card> faceUp = new ArrayList<>();

    @Builder.Default
    private List<Card> faceDown = new ArrayList<>();

    private boolean out;
}
```

| Annotation | When to use |
|---|---|
| `@Data` | Mutable classes — generates `@Getter`, `@Setter`, `@EqualsAndHashCode`, `@ToString`, `@RequiredArgsConstructor` |
| `@Builder` | Any class where callers benefit from named, optional parameters |
| `@Builder.Default` | Collection fields and fields with non-null defaults inside `@Builder` classes |
| `@RequiredArgsConstructor` | Spring beans injected via constructor (use with `final` fields) |
| `@Getter` / `@Setter` | When `@Data` is too broad (e.g., entities, immutable-ish classes) |
| `@Slf4j` | All service and handler classes |
| `@Value` (Lombok) | Truly immutable classes — all fields `final`, no setters |

### Builder Pattern

Use `@Builder` everywhere that constructors or factory methods would take more than 2 arguments, or where callers need to set only a subset of fields.

```java
// Defining a builder class
@Builder
@Getter
public class GameConfig {
    private final int faceDownCount;
    private final int faceUpCount;
    private final int handCount;
    private final int burnCount;

    @Builder.Default
    private final Map<Integer, CardRule> cardRuleMap = new HashMap<>();
}

// Calling the builder
GameConfig config = GameConfig.builder()
        .faceDownCount(3)
        .faceUpCount(3)
        .handCount(3)
        .burnCount(4)
        .build();
```

Static factory methods can wrap the builder for named presets:

```java
public static GameConfig defaultGameConfig() {
    return GameConfig.builder()
            .faceDownCount(3)
            .faceUpCount(3)
            .handCount(3)
            .burnCount(4)
            .build();
}
```

### Records for Immutable DTOs

Use Java `record` for immutable message types and response DTOs. Combine with `@Builder` when callers need optional fields.

```java
@Builder
public record PlayMessage(String sessionId, List<Card> cards) { }

@Builder
public record ErrorResponse(String type, String message) { }
```

### Constructor Injection (Preferred)

Always inject dependencies via constructor, not field injection. Use `@RequiredArgsConstructor` with `final` fields — do not write the constructor manually.

```java
// Good — constructor injection via Lombok
@Slf4j
@Configuration
@RequiredArgsConstructor
public class GameFunctionConfig {
    private final GameSessionRepository sessionRepo;
    private final UserProfileRepository userRepo;
    private final ObjectMapper mapper;
}

// Bad — field injection
@Autowired
private GameSessionRepository sessionRepo;
```

### Naming Conventions

| Element | Convention | Example |
|---|---|---|
| Services | `XxxService` | `EloService` |
| Repositories | `XxxRepository` | `GameSessionRepository` |
| Controllers | `XxxController` | `HealthController` |
| Mappers | `XxxMapper` | `SessionMapper` |
| Entities | `XxxEntity` | `GameSessionEntity`, `CardEntity` |
| Request DTOs | `XxxRequest` | `JoinGameRequest` |
| Response DTOs | `XxxResponse` | `GameStateResponse` |
| Lambda config | `XxxFunctionConfig` | `GameFunctionConfig` |
| Exceptions | `XxxException` | `GameNotFoundException` |
| Classes | PascalCase nouns | `GameSession`, `RuleEngine` |
| Methods | camelCase verbs | `playCards()`, `shouldBurn()` |
| Constants / enums | UPPER_SNAKE | `CardRule.BURNER`, `PlayResult.INVALID` |
| Boolean methods | `is`/`can`/`should` prefix | `isStarted()`, `canPlay()`, `shouldBurn()` |
| Packages | lowercase | `com.tamaspinter.backend.rules` |

---

## 5. Common Tasks

### Adding a New Card Rule

1. Add a value to `CardRule` enum.
2. Create `XxxRuleStrategy implements RuleStrategy` (optionally `implements AfterEffect`).
3. Register it in `RuleEngine.strategies` map.
4. Register card value → rule in `GameConfig.defaultGameConfig()`.
5. Add tests in `com.tamaspinter.backend.rules`.

The existing card-rule picker can assign the existing `CardRule` values to any rank per game. `JOKER` and `TRANSPARENT` ranks are marked always-playable, while `BURNER` ranks also receive the play-again effect. Adding new rule types still requires implementing their backend strategy/effects.

### Adding a New Handler

1. Add a `@Bean` method returning `Function<InputEvent, OutputEvent>` in `GameFunctionConfig` (or `AccountManagementFunctionConfig`). Its name is only the bean name; it is not a deployed function.
2. Register it in `ApiRoutes` (see "Adding a Route to the Game API" above). Do not add a Lambda resource per handler.
3. JWT claims are extracted from: `req.getRequestContext().getAuthorizer().get("claims")`.

### Profiles and Administrative Cleanup

- `UserProfileRepository` persists display names and Elo ratings in the users table.
- `GET /profile` and `PUT /profile` read/update the authenticated user's display name. A name change is also copied to that user's active game entries.
- `POST /admin/doomsday` deletes active game sessions and closes WebSocket connections. It does not delete user profiles or Elo ratings.
- The route checks the Cognito `game-admin` group in JWT claims. Terraform creates the group but does not assign members; membership must be granted deliberately.
- The game API Lambda uses one IAM role (`game_api_exec`) that holds the union of the permissions the former per-handler roles had: profiles, game cleanup, connection cleanup, and API Gateway connection management.
- The profile screen renders the Game Maintenance card only when `/profile` reports `canClearGames` for a `game-admin` member.
- User blocking: the users table item has an optional `blocked` boolean (missing = not blocked). Only `UserProfileRepository.setBlocked` writes it (UpdateItem SET/REMOVE); profile saves use UpdateItem with `withoutBlockedFlag()` and `ignoreNulls` so they never reset it. `GET /admin/users` scans the table and skips `__username__#` claim rows. `POST /admin/users/{userId}/block|unblock` refuses self-block, then best-effort closes the user's WebSocket connections (`UserConnectionService`, filtered scan) and removes them from unstarted lobbies (`LobbyMembershipService`).
- `BlockedUserGuard.isBlocked(userId)` is checked at the top of the account management dispatch and in each authenticated game REST and WebSocket handler (play, pickup, setup); blocked users get 403 `{"message":"Your account has been blocked."}`. Leaderboard reads do not identify the user and are not guarded.
- `GET /games` lists unfinished games (newest first, max 50) with `status` `waiting`/`in_progress`, `playerCount`, and `maxPlayers` (`min(6, decksCount*52 / cardsPerPlayer)`). Served by `GameBrowseService`/`GameBrowseHandler`.
- The frontend `/games` (Browse games) and `/admin` screens sit inside `MenuLayout`; `/admin` is linked only when `/profile` reports `canClearGames`. A 403 with the blocked message sets a shared flag (`auth/accountBlocked.ts`) that shows a full-screen notice.

### Adding a New Repository

1. Create an entity POJO in `entity/` with DynamoDB annotations.
2. Create `XxxRepository` in `repository/` using `DynamoDbEnhancedClient`.
3. Inject via `final` field + `@RequiredArgsConstructor` in the consumer class.

---

## 6. Repository Conventions

### Commit Format (Conventional Commits)

```
<type>(<scope>): <short description>

[optional body]
```

| Type | When to use |
|---|---|
| `feat` | New feature |
| `fix` | Bug fix |
| `refactor` | Code change without behaviour change |
| `test` | Adding or changing tests |
| `chore` | Build, deps, CI |
| `docs` | Documentation only |

### Branch Naming

```
<type>/<short-description>
feat/elo-rating
fix/session-mapper-suit
```

---

## 7. Clean Code Guidelines

### SOLID

- **Single Responsibility**: `GameSession` manages state transitions only; `RuleEngine` evaluates rules only; `SessionMapper` handles serialization only.
- **Open/Closed**: Adding new card rules requires only a new strategy class + registration — no changes to existing logic.
- **Dependency Inversion**: Inject abstractions (interfaces, repositories) not concrete implementations.

### Method Design

- Prefer methods **< 20 lines**.
- Each method should operate at a **single level of abstraction** — do not mix high-level orchestration with low-level bitwise/string manipulation in the same method.
- **Return early** for guard clauses instead of nesting:

```java
// Good
public PlayResult playCards(List<Card> cards) {
    if (finished) return PlayResult.INVALID;
    // ... main logic
}

// Bad
public PlayResult playCards(List<Card> cards) {
    if (!finished) {
        // ... main logic nested here
    }
    return PlayResult.INVALID;
}
```

- **Avoid boolean parameters** — they hide intent. Use enums or split into two methods:

```java
// Bad
session.advance(true);

// Good
session.nextPlayer();
session.skipPlayer();
```

### Code Organization Within a Class

Follow this ordering:

1. Static fields and constants
2. Instance fields
3. Constructors
4. Public methods
5. Private/protected methods
6. Inner classes / enums

### Other Rules

- **Fail fast at boundaries**: validate at Lambda handler entry; trust internal invariants.
- **No over-engineering**: three similar lines is better than a premature abstraction. Only abstract when a pattern recurs three or more times.
- **No backwards-compatibility shims**: if something is unused, delete it.
- **Static for stateless utilities**: `RuleEngine`, `SessionMapper`, `EloService` are stateless — expose only `static` methods.

---

## 8. Design Patterns in Use

| Pattern | Where |
|---|---|
| Builder | All multi-field domain/DTO classes via `@Builder` |
| Strategy | `RuleStrategy` + per-rule implementations |
| Command / AfterEffect | `AfterEffect` interface for post-play side effects |
| Repository | `GameSessionRepository`, `UserProfileRepository` |
| Static Factory | `GameConfig.defaultGameConfig()`, `GameConfig.fromEntity()` |
| State Machine | `GameSession.playCards()` returning `PlayResult` |
| Null Object | `Optional<Card>` for deck draws; `@Builder.Default` for empty collections |

---

## 9. Error Handling Best Practices

### Exception Hierarchy

Define a custom exception hierarchy under `exception/`:

```java
// Base
public abstract class GameException extends RuntimeException {
    protected GameException(String message) { super(message); }
    protected GameException(String message, Throwable cause) { super(message, cause); }
}

// Subtypes
public class GameNotFoundException extends GameException {
    public static GameNotFoundException forSession(String sessionId) {
        return new GameNotFoundException("Game session not found: " + sessionId);
    }
}

public class InvalidMoveException extends GameException {
    public static InvalidMoveException notYourTurn(String playerId) {
        return new InvalidMoveException("Not " + playerId + "'s turn");
    }
}
```

- Use **static factory methods** on exception classes for named, self-documenting error cases.
- Lambda handlers translate exceptions to HTTP status codes at the boundary.
- Log with `log.error("...", e)` — always include the exception object.

### Error Response Shape

Use a `record` for structured error responses:

```java
public record ErrorResponse(String type, String message) {
    public static ErrorResponse of(String type, String message) {
        return new ErrorResponse(type, message);
    }
}
```

---

## 10. Testing Guidelines

### Package Structure

Tests mirror source packages exactly:

```
src/test/java/com/tamaspinter/backend/
  game/       → GameSessionTest, GameConfigTest
  mapper/     → SessionMapperTest
  model/      → DeckTest
  rules/      → RuleEngineTest, DefaultRuleStrategyTest, …
  service/    → EloServiceTest
```

### Test Method Naming

Use `methodUnderTest_scenario_expectedBehavior`:

```java
void playCards_withInvalidCard_returnsInvalid()
void start_withTwoPlayers_dealsSixCardsEach()
void shouldBurn_withFourMatchingCards_returnsTrue()
void updateRatings_withEqualRatings_winnerGainsSixteen()
```

### Structure: Given / When / Then

Always use `// Given`, `// When`, `// Then` (or `// When/Then`) section comments.

```java
@Test
void canPlay_onEmptyPile_returnsTrue() {
    // Given
    Card card = Card.builder().suit(Suit.HEARTS).value(7).rule(CardRule.DEFAULT).build();
    Deque<Card> pile = new ArrayDeque<>();

    // When/Then
    assertTrue(RuleEngine.canPlay(card, pile));
}
```

### Test Data Builders

Use static factory methods that return a pre-filled builder so tests can override only the field they care about:

```java
// In test helpers or inner class
static Card.CardBuilder aCard() {
    return Card.builder()
            .suit(Suit.HEARTS)
            .value(7)
            .rule(CardRule.DEFAULT)
            .alwaysPlayable(false);
}

// In test
Card highCard = aCard().value(10).rule(CardRule.BURNER).build();
Card lowCard  = aCard().value(3).build();
```

### Mockito Style

Use `mockito-core` only (no `mockito-junit-jupiter`). Call `Mockito.mock()` directly:

```java
Deck mockDeck = Mockito.mock(Deck.class);
when(mockDeck.draw()).thenReturn(Optional.of(card1), Optional.of(card2), Optional.empty());
session.setDeck(mockDeck);
```

### Deterministic Game Setup

Use `Deck(List<Card>)` (no-shuffle constructor) for controlled decks:

```java
session.setDeck(new Deck(List.of()));           // empty deck
session.setDeck(new Deck(List.of(someCard)));   // exactly one card
```

### Coverage Targets

- **80%+ line coverage** overall.
- **100% branch coverage** for critical paths: `GameSession.playCards()`, all `RuleStrategy` implementations, `SessionMapper` round-trips.
- Every new class must have a corresponding test class.

### Floating-Point Assertions

```java
assertEquals(1016.0, updated.get("winner"), 0.001);
```

---

## 11. Logging

Use `@Slf4j` (Lombok) on all service and handler classes. Do not use `System.out.println`.

| Level | When |
|---|---|
| `log.error(msg, e)` | Unrecoverable failures — always include the exception |
| `log.warn(msg)` | Recoverable issues (e.g. stale WebSocket connection removed) |
| `log.info(msg)` | Significant business events (game started, game ended, Elo updated) |
| `log.debug(msg)` | Internal flow detail — disabled in production |

Include structured context:

```java
log.error("Elo update failed for session {}", session.getSessionId(), e);
log.info("Removing stale connection: {}", connectionId);
log.info("Game {} ended — shithead: {}", sessionId, shitheadId);
```

---

## 12. Security Best Practices

- JWT claims (`sub`, `username`) come from the API Gateway authorizer context — **never** trust user-supplied player IDs in the request body.
- Use least-privilege IAM roles per Lambda (defined in Terraform).
- Keep global game deletion behind Cognito `game-admin`; do not expose it to ordinary authenticated players.
- Validate all external inputs at the Lambda handler boundary.
- Never log sensitive data (tokens, full request bodies).

---

## 13. AI Model-Specific Guidelines

### Before Making Changes

1. Read the relevant source files before modifying them.
2. Follow existing patterns — `@Builder`, `@RequiredArgsConstructor`, Records for DTOs, constructor injection.
3. Every new class needs a test class in the mirrored package.
4. Run `./mvnw test -pl backend` before committing.

### Key Files Quick Reference

| File | Why it matters |
|---|---|
| `game/GameSession.java` | Core state machine — understand before touching game logic |
| `config/ApiRoutes.java` | Route table of the game API Lambda; add new routes here |
| `config/GameApiFunctionConfig.java` | `gameApi` dispatcher bean (the game API Lambda entry point) |
| `config/GameFunctionConfig.java` | Game handler `@Bean`s (join, leave, start, state, leaderboard, play, pickup) |
| `glue-go/dispatch.go` | Go glue entry point and event-shape dispatch |
| `rules/RuleEngine.java` | Static dispatcher for `canPlay` + `afterEffect` |
| `game/GameConfig.java` | Card value → rule mapping; source of truth for special cards |
| `mapper/SessionMapper.java` | Domain ↔ DynamoDB; has a known bug (see below) |
| `backend/pom.xml` | Dependency versions |

### SessionMapper Card Serialization

`SessionMapper` persists all card fields, including `Suit`, and restores them when loading a session.

### GameSession Invariants

- `playCards()` returns `INVALID` immediately if `finished == true` — all guards come before state mutation.
- `nextPlayer()` skips `isOut() == true` players — there must always be at least one active player before calling it.
- `postPlayCleanup()` is always called after a successful play: burn check → refill hand → out check.

---

## 14. Documentation Update Protocol

Update this file when:
- A new package, significant class, or architectural pattern is added.
- A known bug is fixed or a new one is discovered.
- A dependency version changes significantly.
- A coding convention is adopted or changed.

Include a **change log entry** at the top of this section with date and model name.

### Change Log

| Date | Change | Model |
|---|---|---|
| 2026-02-20 | Initial file created | Claude Sonnet 4.6 |
| 2026-02-20 | Added Builder pattern, Records, constructor injection, test naming, exception hierarchy | Claude Sonnet 4.6 |
| 2026-02-20 | Added PMD, Checkstyle, SpotBugs setup and codeQuality profile docs | Claude Sonnet 4.6 |
| 2026-10-09 | Added game activity feed (`events` attribute) and session chat (`chat` WebSocket route) | Claude Sonnet 5.5 |
| 2026-10-09 | Added nudge (`nudge` WebSocket route, fart sound) and login sound | Claude Sonnet 5.5 |
