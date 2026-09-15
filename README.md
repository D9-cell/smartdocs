# SmartDocs — Stage 0

A single-user, durably-persisted plain-text editor. Java 21, Spring Boot 3,
PostgreSQL 16 via Liquibase, vanilla-JS frontend. Full design in
[`stage-0-single-user-editor-design.md`](./stage-0-single-user-editor-design.md);
this file is the "how do I actually run it" complement.

## Prerequisites

- JDK 21
- Maven (or use nothing extra — no wrapper is checked in; install Maven 3.9+)
- Docker, for both local Postgres and the Testcontainers-backed test suite

## Run it locally

```bash
docker compose up -d          # starts Postgres 16 on localhost:5433
DB_PORT=5433 mvn spring-boot:run   # boots the app on :8080, migrating the schema at startup
```

The compose file maps the container's Postgres to host port **5433**, not
5432 — many dev machines already run a native/system Postgres on 5432 (set
`DB_HOST_PORT` to override if 5433 is also taken). `DB_PORT=5433` tells the
app to match; `application.yaml`'s default (`DB_PORT:5432`) is for
environments where nothing else already owns 5432.

Open `http://localhost:8080`. The API lives under `/api/v1/documents`
(see the design doc, section 6, for the full contract). Static assets and
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

Test layers, matching design doc section 9:

| Class | Layer | What it proves |
|---|---|---|
| `ContentValidatorTest`, `ContentHasherTest`, `EtagSupportTest` | Unit | Every row of the section 8.1 edge-case table; hash determinism; header parsing |
| `DocumentRepositoryTest` | `@DataJpaTest` + real Postgres | Conditional-update row counts, unique constraint, soft-delete exclusion |
| `DocumentServiceTest` | Service, real DB | Every `DocumentService` method and error branch |
| `DocumentControllerWebMvcTest` | `@WebMvcTest`, mocked service | Every HTTP status code in the section 6.7 catalogue |
| `IntegrationSaveReloadTest` | `@SpringBootTest`, real DB | Full save + restart persistence with identical SHA-256; two stateless instances against one database |
| `ConcurrencySaveTest` | `@SpringBootTest`, real DB, threads | 100 repetitions of the two-thread race in section 9: exactly one 200, one 412 |
| `MigrationRollbackTest` | Liquibase directly, real DB | `update` then a full rollback leaves an empty schema |

Coverage on the two classes the design doc calls out by name (section 12):
`DocumentService` 97.5% lines, `ContentValidator` 100% lines (see
`target/site/jacoco/index.html` after `mvn test`).

## Project layout

```
src/main/java/com/deepon/smartdocs/
  config/       Clock, Jackson, request-size-limiting filter, request-id filter
  controller/   DocumentController (routing lives on its @RequestMapping), EtagSupport
  dto/          Request and response records — the wire contract
  service/      DocumentService (interface), ContentHasher
  service/impl/ DocumentServiceImpl — the business rules
  validator/    ContentValidator
  repository/   Spring Data repositories and query projections
  entity/       JPA entities
  exception/    Typed domain exceptions + GlobalExceptionHandler (RFC 9457 mapping)
src/main/resources/
  db/changelog/   Liquibase changelogs (one file per changeset, see section 7.6)
frontend/         index.html, styles.css, js/{api,draft,editor}.js
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
the end of Stage 0 with:

```bash
mvn liquibase:rollback -Dliquibase.rollbackTag=stage-0
```
