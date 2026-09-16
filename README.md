# SmartDocs — Stage 1

Every document now belongs to a signed-in user. Java 21, Spring Boot 3,
PostgreSQL 16 via Liquibase, vanilla-JS frontend, server-side sessions.
Full design in the Stage 1 design doc ("Stage 1: Document Backend with
Identity and Ownership"); this file is the "how do I actually run it"
complement. Stage 0's single-user editor is still the base this builds on.

## Prerequisites

- JDK 21
- Maven (or use nothing extra — no wrapper is checked in; install Maven 3.9+)
- Docker, for both local Postgres and the Testcontainers-backed test suite

## Run it locally

```bash
docker compose up -d          # starts Postgres 16 on localhost:5433
DB_PORT=5433 SMARTDOCS_COOKIE_SECURE=false mvn spring-boot:run   # boots the app on :8080, migrating the schema at startup
```

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
| `MigrationRollbackTest` | Liquibase directly, real DB | `update` then a full rollback leaves an empty schema |
| `ArchitectureTest` | ArchUnit | No unscoped `findById` on `DocumentRepository`; services don't import `jakarta.servlet`; controllers don't touch repositories directly |

## Project layout

```
src/main/java/com/deepon/smartdocs/
  common/       Actor, ActorArgumentResolver, IdGenerator (UUIDv7), Sha256, shared exceptions
  config/       Clock, Jackson, request-size-limiting filter, request-id filter, WebConfig
  security/     OriginGuardFilter, SessionAuthFilter, PasswordHasher, IpHasher, CookieSupport
  document/     DocumentController, DocumentService(+impl), DocumentRepository, CursorCodec — now owner-scoped
  revision/     RevisionController, RevisionService(+impl) — now owner-scoped
  user/         AuthController, AuthService/SessionService/UserService(+impl), LoginRateLimiter,
                UserValidator, entities (AppUser, UserSession, LoginAttempt), repositories
src/main/resources/
  db/changelog/   Liquibase changelogs (one file per changeset)
frontend/         index.html, login.html, styles.css, js/{api,draft,editor}.js
                  — packaged into the jar as static/ at build time (see pom.xml)
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
mvn liquibase:rollback -Dliquibase.rollbackTag=stage-0
```

Or to the end of Stage 1 once a Stage 2 exists: `-Dliquibase.rollbackTag=stage-1`.
