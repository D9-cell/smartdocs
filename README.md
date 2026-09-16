# SmartDocs — Stage 2

Two browser tabs viewing the same document now stay in sync over a
WebSocket, last write wins. Java 21, Spring Boot 3, PostgreSQL 16 via
Liquibase, vanilla-JS frontend, server-side sessions. Full design in the
Stage 2 design doc ("Stage 2: Real-time single-document sync") and the
Stage 1 design doc it builds on ("Stage 1: Document Backend with Identity
and Ownership"); this file is the "how do I actually run it" complement.

**Stage 3 is in progress.** The build is now two Maven modules: `crdt-core`
(the YATA sequence CRDT — pure Java, no Spring, no IO, no framework on its
classpath) and `smartdocs-app` (everything else). The split is enforced by
the build rather than by convention, because the merge algorithm has to be
testable without a server and has to match the JavaScript port character for
character. `crdt-core` is scaffolded but not yet implemented; text sync is
still Stage 2's last-write-wins until Stage 3 phase 3.3 lands.

## Real-time sync (Stage 2)

Every open tab holds one WebSocket connection (`/ws`), authenticated with a
single-use ticket (`POST /api/v1/ws-tickets`) rather than a header the
browser can't set on a handshake. Content changes broadcast the full
document, last write wins — the loser is still recorded in
`document_revision.base_version` for measuring how often it actually
happens. See the design doc sections 1–2 for the full rationale (why full
state instead of operations, why a raw handler instead of STOMP, why a
ticket instead of a cookie or a query-string JWT).

**Single instance only.** The room registry (`RoomRegistry`) is an
in-memory map on one node. Running a second instance doesn't fail loudly —
it silently splits rooms in half, and two tabs on the same document stop
syncing with no error anywhere. The app logs a startup warning if
`smartdocs.instance.count` (env `SMARTDOCS_INSTANCE_COUNT`) is set above 1;
it does not enforce the limit, since nothing on one instance can see
another. Stage 9 moves broadcast fanout onto a shared log (Kafka or Redis)
and removes this limit — until then, scale this app vertically, not
horizontally.

## Prerequisites

- JDK 21
- Maven (or use nothing extra — no wrapper is checked in; install Maven 3.9+)
- Docker, for both local Postgres and the Testcontainers-backed test suite

## Run it locally

```bash
docker compose up -d          # starts Postgres 16 on localhost:5433
DB_PORT=5433 SMARTDOCS_COOKIE_SECURE=false mvn -pl smartdocs-app -am spring-boot:run   # boots the app on :8080, migrating the schema at startup
```

Run it from the repository root, not from inside `smartdocs-app`. The `-pl`
picks the runnable module and `-am` ("also make") rebuilds `crdt-core` from
source in the same reactor — so a change there is always picked up, instead
of silently resolving a stale `crdt-core` jar out of `~/.m2`. The parent pom
skips `spring-boot-maven-plugin` for itself and for `crdt-core`, since a
command-line goal otherwise runs against every module in the reactor and
neither of those has a main class.

The compose file maps the container's Postgres to host port **5433**, not
5432 — many dev machines already run a native/system Postgres on 5432 (set
`DB_HOST_PORT` to override if 5433 is also taken). `DB_PORT=5433` tells the
app to match; `application.yaml`'s default (`DB_PORT:5432`) is for
environments where nothing else already owns 5432.

`SMARTDOCS_COOKIE_SECURE=false` is needed for local **plain http** only — a
`Secure` cookie is silently dropped by real browsers over http, so without
this the session cookie the login flow sets never actually sticks. Leave it
unset (defaults to `true`) anywhere the app is served over https.

Open `http://localhost:8080` — you'll land on `/login.html` first; register
an account, then the editor at `/` works as before, now scoped to your own
documents. The API lives under `/api/v1/documents` and `/api/v1/auth` (see
the design doc, sections 6–7, for the full contract). Static assets and
`index.html` are served from the same origin — no CORS to configure.

To stop and wipe local data: `docker compose down -v`.

## Run the tests

```bash
mvn test
```

Every test above the pure-unit layer (`ContentValidatorTest`,
`ContentHasherTest`, `EtagSupportTest`) talks to a **real PostgreSQL 16** via
Testcontainers — never H2 (see design doc section 9 for why). Docker must be
running. The whole suite, including the two 100-iteration-class tests
(concurrency race, and the two-instances/one-database check), the migration
rollback proof, and the full-restart persistence proof, takes about a
minute.

Test layers, matching the design docs' testing-strategy sections:

| Class | Layer | What it proves |
| --- | --- | --- |
| `ContentValidatorTest`, `ContentHasherTest`, `EtagSupportTest`, `CursorCodecTest`, `UserValidatorTest`, `PasswordHasherTest`, `LoginRateLimiterTest` | Unit | Edge-case tables; hash determinism; header parsing; pagination cursors; auth field validation |
| `DocumentRepositoryTest` | `@DataJpaTest` + real Postgres | Conditional-update row counts, unique constraint, soft-delete exclusion, owner scoping, keyset pagination |
| `DocumentServiceTest`, `RevisionServiceTest` | Service, real DB | Every method and error branch, including cross-owner 404s |
| `AuthServiceTest`, `SessionServiceTest` | Service, real DB | Register/login/lockout/self-heal/logout/password-change; session create/resolve/renew/revoke/rotate/cap |
| `DocumentControllerWebMvcTest`, `AuthControllerWebMvcTest` | `@WebMvcTest`, mocked service | Every HTTP status code in the error catalogue, cookie headers |
| `IntegrationSaveReloadTest` | `@SpringBootTest`, real DB | Full save + restart persistence with identical SHA-256; two stateless instances against one database |
| `ConcurrencySaveTest` | `@SpringBootTest`, real DB, threads | 100 repetitions of the two-thread race: exactly one 200, one 412 |
| `MigrationRollbackTest` | Liquibase directly, real DB | `update` then a full rollback leaves an empty schema; Stage 2 rollback to `stage-1` and re-`update` restores it |
| `ArchitectureTest` | ArchUnit | No unscoped `findById` on `DocumentRepository`; services don't import `jakarta.servlet` or WebSocket types; controllers don't touch repositories directly |
| `WsTicketServiceTest`, `WsTicketControllerWebMvcTest` | Service / `@WebMvcTest`, real DB | Single-use redeem, expiry, per-user rate limit |
| `WsMessageCodecTest`, `OriginMatcherTest` | Unit | Envelope encode/decode round-trips; exact-origin allow-list matching |
| `DocumentWebSocketHandlerTest` | `@SpringBootTest`, random port, real DB | Handshake auth, subscribe/unsubscribe, update/ack/broadcast, idempotent resend, no-op writes, rename/delete broadcasts |
| `DocumentWebSocketHandlerLimitsTest` | `@SpringBootTest`, random port, real DB | Per-user connection cap eviction, sustained rate-limit close |
| `DocumentWebSocketConvergenceTest` | `@SpringBootTest`, random port, real DB | 4 clients writing concurrently converge on one surviving content within 2s |

## Project layout

```
pom.xml           parent/aggregator: modules, dependencyManagement, jacoco
crdt-core/        Stage 3: YATA sequence CRDT — pure Java, junit+assertj only, no Spring/IO
smartdocs-app/
  src/main/java/com/deepon/smartdocs/
    common/       Actor, ActorArgumentResolver, IdGenerator (UUIDv7), Sha256, shared exceptions
    config/       Clock, Jackson, request-size-limiting filter, request-id filter, WebConfig
    security/     OriginGuardFilter, OriginMatcher, SessionAuthFilter, PasswordHasher, IpHasher, CookieSupport
    document/     DocumentController, DocumentService(+impl), DocumentRepository, CursorCodec — owner-scoped;
                  applyLastWriteWins is the WS write path, rename/softDelete publish DocumentChangedEvent
    revision/     RevisionController, RevisionService(+impl) — owner-scoped; revisions carry base_version/source/session_id
    user/         AuthController, AuthService/SessionService/UserService(+impl), LoginRateLimiter,
                  UserValidator, entities (AppUser, UserSession, LoginAttempt), repositories
    websocket/    DocumentWebSocketHandler, WsHandshakeInterceptor, WebSocketConfig, WsMessageCodec,
                  envelope/payload records, WsTicketController/Service(+impl)/Repository, WsTicketCleanupJob
    realtime/     SessionHandle/Registry, RoomRegistry, OutboundSender, DocumentBroadcaster, SessionReaper,
                  TokenBucket, RealtimeMetricsSampler, InstanceCountGuard — holds no document business rules
  src/main/resources/
    db/changelog/ Liquibase changelogs (one file per changeset)
  frontend/       index.html, login.html, styles.css, js/{api,app,draft,editor,login,syncClient}.js
                  — packaged into the jar as static/ at build time (see smartdocs-app/pom.xml)
```

## Runbook

**Liquibase lock stuck after a crash.** If the app died mid-migration, the
next boot hangs waiting on `databasechangeloglock`. Fix:

```sql
UPDATE databasechangeloglock SET locked = false, lockgranted = null, lockedby = null WHERE id = 1;
```

Then restart the app. This is safe as long as no other instance is actually
mid-migration — check `docker compose logs` / your orchestrator for a
genuinely stuck process before running it.

**Two instances at once.** Liquibase's changelog lock serializes migrations
at boot: the second instance waits for the first to finish, then starts
normally with nothing left to apply. No manual intervention needed; this is
covered by `IntegrationSaveReloadTest`.

**Rolling back a stage boundary.** Every changeset is tagged; roll back to
the end of Stage 0 (drops accounts, sessions, and document ownership
entirely) with:

```bash
mvn -pl smartdocs-app liquibase:rollback -Dliquibase.rollbackTag=stage-0
```

Or to the end of Stage 1 (drops `ws_ticket` and the Stage 2 `document_revision`
columns, leaves accounts and ownership intact): `-Dliquibase.rollbackTag=stage-1`.
