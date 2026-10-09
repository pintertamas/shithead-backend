# AI-CONTEXT.md — shithead-backend

> **SINGLE SOURCE OF TRUTH** for AI models working with this codebase.
> Update this file when patterns change, new components are added, or architectural decisions are made.
>
> Last Updated: 2026-10-08

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
| Compute | AWS Lambda (via `spring-cloud-function-adapter-aws`) |
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
| `config` | `GameFunctionConfig` — game, profile, and admin Lambda `@Bean` definitions |
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
  ├── REST (HTTP)  → API Gateway → Lambda (@Bean Function<Req, Res>)
  │                                  ├── Profile API (/profile)
  │                                  └── Admin cleanup (/admin/doomsday; Cognito game-admin only)
  └── WebSocket    → API Gateway → Lambda (@Bean Function<WSEvent, Res>)
                                         ↓
                                   GameSession (state machine)
                                         ↓
                                   DynamoDB (save entity)
                                        ↓
                                   broadcastState() → WebSocket clients
```

User nicknames are stored in the users DynamoDB table. `UserProfileService` creates profiles and reserves unique default nicknames; `UsernameReservationRepository` normalizes nickname comparisons case-insensitively, checks legacy profile rows, and transactionally reserves a nickname with a hidden same-table claim record. The account-management Lambda needs Scan, UpdateItem, DeleteItem, and TransactWriteItems permissions on that table. Starting a game is a two-phase flow: the owner persists `starting=true`, then sends a `setup` WebSocket action to broadcast that state immediately (one-second lobby polling remains a fallback), and finalizes the deal after a short transition. Once dealt, the game enters a persisted card-swap/readiness phase; play is blocked until every player is ready.

### Game State Machine (`GameSession`)

`GameSession.playCards(List<Card>)` returns one of three outcomes:

| Result | Meaning |
|---|---|
| `SUCCESS` | Cards played; after-effects applied; turn advances unless the card or a pile burn grants a replay |
| `PICKUP` | Player picks up the pile (explicit or blind flip failure) |
| `INVALID` | Move rejected — wrong turn, illegal card, or game finished |

Card source priority: **hand → faceUp → faceDown** (blind flip). The game client sends an explicit source and index for a selected card; face-down cards stay hidden from the client and are revealed by the server after the blind flip. `allowMixedHandAndFaceUpWhenDeckEmpty` is stored per game, and permits a same-value hand/face-up combination only when that game's draw pile is empty.

After dealing, `GameSession` sorts each player's hand and face-up cards by rank and suit, but preserves face-down order. Players may swap one hand card with one face-up card before marking themselves ready; a ready player cannot alter cards. `setupComplete` gates every play action until all players are ready. Four/six-card burns also grant the player another turn, even when the played rank reverses player order.

The WebSocket `playSelections` path must run `finishSuccessfulPlay` after a successful hand, face-up, face-down, or mixed selection so after-effects execute and turn ownership advances. `setup` actions use the same WebSocket Lambda route for readiness and card swaps. Failed blind flips include a transient revealed card in the broadcast; the browser hides that notice after about one second. Keep these behaviors in sync if adding another selection source.

The frontend keeps `/lobby`, `/config`, `/profile`, and leaderboard routes inside a shared `MenuLayout` with persistent desktop sidebar and mobile top navigation. The `/config` screen saves next-game preferences in browser `localStorage` (`shithead_game_config`). Lobby game creation sends those settings to the Python `create_game` Lambda. Each game stores its own config in DynamoDB. Deck count is fixed to the selected 1 or 2 decks; the burn threshold follows it (4 or 6 cards). Selected card rules use the existing `CardRule` strategies and are stored on the game/cards.

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

### Adding a New Lambda Function

1. Add a `@Bean` method returning `Function<InputEvent, OutputEvent>` in `GameFunctionConfig`.
2. The method name is the Spring Cloud Function route name.
3. JWT claims are extracted from: `req.getRequestContext().getAuthorizer().get("claims")`.
4. Wire the API Gateway route in Terraform under `infra/`.

### Profiles and Administrative Cleanup

- `UserProfileRepository` persists display names and Elo ratings in the users table.
- `GET /profile` and `PUT /profile` read/update the authenticated user's display name. A name change is also copied to that user's active game entries.
- `POST /admin/doomsday` deletes active game sessions and closes WebSocket connections. It does not delete user profiles or Elo ratings.
- The route checks the Cognito `game-admin` group in JWT claims. Terraform creates the group but does not assign members; membership must be granted deliberately.
- The account management Lambda uses a dedicated IAM role scoped to profiles, game cleanup, connection cleanup, and API Gateway connection management.
- The profile screen renders the Game Maintenance card only when `/profile` reports `canClearGames` for a `game-admin` member.

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
| `config/GameFunctionConfig.java` | All Lambda entry points |
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
| 2026-10-09 | Added the side-by-side Go backend (`backend-go/`, `infra/terraform/go_api/`), see section 15 | Claude Haiku 5.5 |

---

## 15. Go Backend (Side-by-Side)

A second backend, written in Go, lives in `backend-go/`. It implements the whole game API (REST, WebSocket and the WebSocket authorizer) in **one Lambda** and is deployed next to the existing Java/Python stack. The old backend is untouched; the Go one is switched on per browser for testing.

### Layout

| Path | Contents |
|---|---|
| `backend-go/cmd/lambda/main.go` | Lambda entry point (`lambda.Start(h.Handle)`), wiring from env vars |
| `backend-go/internal/rules` | Card/Player types and the stateless rule engine (`CanPlay`, `ShouldBurn`, after-effects) |
| `backend-go/internal/game` | `Session` state machine, `GameConfig` (+ `ParseConfig` validation), `Deck` |
| `backend-go/internal/elo` | K=32 rating update |
| `backend-go/internal/store` | DynamoDB records (same attribute names as Java/Python), games/users/connections repositories, username reservation transaction |
| `backend-go/internal/auth` | Cognito ID-token verification (RS256, iss/aud/token_use/exp, JWKS cached, refetch on unknown kid) and claim helpers |
| `backend-go/internal/handler` | Event dispatch (REST proxy / WebSocket / REQUEST authorizer), routes, views, broadcast, AWS notifier |

The handler depends only on interfaces (`GameRepo`, `UserRepo`, `ConnectionRepo`, `Notifier`, `TokenVerifier`), so `go test ./...` needs no AWS.

### Build and test

```bash
cd backend-go
go vet ./...
go test ./...
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -tags lambda.norpc -trimpath -o build/stage/bootstrap ./cmd/lambda
cd build/stage && zip -q -j ../shithead-api.zip bootstrap   # -> backend-go/build/shithead-api.zip
```

The module pins `aws-lambda-go` v1.47.0 and `golang.org/x/text` v0.22.0 because newer releases require Go 1.26; the toolchain is Go 1.24 (`go 1.24` in `go.mod`). CI (`deploy-backend`) runs the same build before `terraform apply`.

### Routes

- REST (`{proxy+}`, Cognito authorizer, `OPTIONS` unauthenticated): `POST /create-game`, `POST /join-game`, `POST /leave-game`, `POST /start-game` (`phase: "prepare"` marks starting), `GET /state/{sessionId}`, `GET /leaderboard/top?limit=`, `GET /leaderboard/session/{sessionId}`, `GET|PUT /profile`, `POST /admin/doomsday` (Cognito group `game-admin`).
- WebSocket (`$request.body.action` selects the route): `$connect` (REQUEST authorizer, token in `?token=`), `$disconnect`, `$default` (no-op, 200), `play`, `setup`, `pickup`. Message and error shapes are unchanged.

Behaviour matches the Java/Python backend, with these deliberate differences:
- `$default` does not broadcast client data (the Python `default.py` did).
- Every save keeps `starting` and `eloUpdated`; the Java code dropped them on play.
- Profile and Elo writes are `UpdateItem`s, so attributes such as `created_at` are no longer dropped by a full put.
- `/leaderboard/top` returns the top N (the Java query paginated through the whole index).
- Create-game validates the configuration (decks 1|2, zone counts 0..10 that fit the deck, known card rules, boolean flags; unknown keys ignored).
- `allowFailedFaceUpPlay` (default false): an illegal face-up play picks up the whole pile instead of being rejected, and reveals the card.

### Side-by-side infrastructure

`infra/terraform/go_api/` is a self-contained module, called as `module "go_api"` in `infra/terraform/main.tf`. It creates the Lambda `${project}-go-api` (arm64, `provided.al2023`, 256 MB, 30 s, no SnapStart), its IAM role (`${project}-go-*`), the REST API `${project}-go-api` (stage `prod`) and the WebSocket API `${project}-go-ws` (stage `$default`, throttling 100/50, own access log group). It reads the existing tables and the Cognito pool through outputs and changes none of them. Outputs: `go_api_base_url` and `go_websocket_url`.

### Switching the frontend to the Go backend

- Add repo variables `VITE_API_BASE_URL_GO` (= `go_api_base_url`) and `VITE_WS_BASE_URL_GO` (= `go_websocket_url`). CI passes them to both frontend builds.
- Open the app with `?backend=go`. `activeBackend()` in `frontend/src/app/api/client.ts` stores `shithead_backend=go` in localStorage. `?backend=default` clears it. The Go backend is used only when the preference is set and both `_GO` variables are present.

### Status

Side-by-side and additive: nothing in the Java/Python stack is modified or redeployed by this module. Switching the default frontend to Go is a separate, later decision.
