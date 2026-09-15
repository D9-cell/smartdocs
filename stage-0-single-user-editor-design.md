# Stage 0: Single-User Text Editor. Full Design

Target stack: Java 21, Spring Boot 3.x, PostgreSQL 16, Liquibase, vanilla JS frontend.
Purpose: prove durable persistence and reload of one document. Every later stage builds on the contracts fixed here.

---

## 1. Scope

### In scope
- One plain-text document body per document row.
- Create, read, update, list, soft-delete.
- Save to PostgreSQL. Reload after full server restart.
- Browser editor with autosave and manual save.
- Version counter on every document.
- Append-only revision history.

### Out of scope for Stage 0
- Accounts, login, permissions.
- Two users editing at once.
- Rich text, formatting, attachments.
- WebSockets, real-time push.
- CRDT, OT, merge logic.
- AI agents.

### Non-goals with a reason
| Excluded | Reason |
|---|---|
| Rich text | Byte-level identity of content matters for future operation offsets. Markup adds ambiguity with zero learning value now. |
| Real-time transport | HTTP forces you to solve durability and conflict detection before transport hides them. |
| Merge | You need conflict detection working before merge has meaning. |

---

## 2. Why Stage 0 decisions matter later

Stage 0 looks trivial. Four decisions here determine how painful Stage 3 onward becomes.

| Stage 0 decision | Later payoff |
|---|---|
| Monotonic `version` per document, checked on write | Becomes the causal anchor for operation ordering and conflict detection. |
| Append-only `document_revision` table | Becomes provenance and time-travel. Agent edits later carry `actor_id` in the same column. |
| `actor_id` column present from day one, value `anonymous` | No schema surgery when humans and agents both write. |
| Server never rewrites submitted bytes | Content hash stays stable across client and server. Operation offsets stay valid later. |

Write these four into the code now even though nothing uses them yet.

---

## 3. System architecture

### 3.1 Component view

```
┌──────────────────────────────────────────────────────────┐
│ Browser                                                  │
│  ┌────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │ textarea   │→ │ EditorState  │→ │ SaveScheduler    │  │
│  │            │  │ (FSM)        │  │ (debounce+queue) │  │
│  └────────────┘  └──────────────┘  └────────┬─────────┘  │
│         ↑                ↑                  │            │
│         │         ┌──────┴───────┐          │            │
│         └─────────│ DraftStore   │          │            │
│                   │ localStorage │          │            │
│                   └──────────────┘          │            │
└─────────────────────────────────────────────┼────────────┘
                                              │ HTTP/JSON
                                              ▼
┌──────────────────────────────────────────────────────────┐
│ Spring Boot application                                  │
│  ┌───────────────┐                                       │
│  │ Web layer     │  DocumentController, DTOs,            │
│  │               │  ProblemDetail handler, filters       │
│  └───────┬───────┘                                       │
│  ┌───────▼───────┐                                       │
│  │ Service layer │  DocumentService, ContentValidator,   │
│  │               │  ContentHasher, Clock                 │
│  └───────┬───────┘                                       │
│  ┌───────▼───────┐                                       │
│  │ Persistence   │  DocumentRepository,                  │
│  │               │  RevisionRepository                   │
│  └───────┬───────┘                                       │
└──────────┼───────────────────────────────────────────────┘
           │ JDBC (HikariCP)
           ▼
┌──────────────────────────────────────────────────────────┐
│ PostgreSQL 16                                            │
│   document, document_revision, databasechangelog         │
└──────────────────────────────────────────────────────────┘
           ▲
           │ startup
   ┌───────┴────────┐
   │ Liquibase      │  master changelog, versioned changesets
   └────────────────┘
```

### 3.2 Deployment view

- One JVM process. Stateless. No session affinity needed.
- One PostgreSQL instance, local via Docker Compose.
- Static frontend lives in the top-level `frontend/` directory and is packaged into the jar as `static/` at build time, then served by the same Spring Boot app. One origin. No CORS problem in Stage 0.
- Liquibase runs inside the application at boot, before Hibernate validation.

Running two app instances behind a load balancer must work with zero code change. Test this in Stage 0 by starting two instances on different ports against one database. Stateless design gets verified early, cheaply.

### 3.3 Layering rules

- Controllers never touch repositories.
- Services never see HTTP types. No `HttpServletRequest`, no `ResponseEntity` below the web layer.
- Entities never leave the service layer. DTOs cross the web boundary.
- Domain errors are typed exceptions. The web layer maps them to status codes. One mapping table, one place.

Reason: Stage 4 replaces HTTP with WebSocket for some flows. A service layer free of HTTP types survives the swap.

---

## 4. High-level design

### 4.1 Core flows

**Flow A: open document**

```
Browser                 Server                  Postgres
   │ GET /api/v1/documents/{id}
   ├──────────────────────────►│
   │                           │ SELECT ... WHERE id=? AND deleted_at IS NULL
   │                           ├────────────────────►│
   │                           │◄────────────────────┤
   │◄──────────────────────────┤ 200 + ETag: "7"
   │ compare with localStorage draft
   │ if draft.baseVersion == 7 and draft.content != server.content
   │    → show recovery prompt
   │ else → render server content
```

**Flow B: save**

```
Browser                      Server                        Postgres
   │ PUT /api/v1/documents/{id}/content
   │ If-Match: "7"
   │ body: {content, clientHash}
   ├────────────────────────────►│
   │                             │ validate size, charset
   │                             │ hash(content)
   │                             │ if hash == stored hash → return 200, version 7, no write
   │                             │ else BEGIN
   │                             │  UPDATE document SET content=?, version=version+1
   │                             │   WHERE id=? AND version=7 AND deleted_at IS NULL
   │                             ├──────────────────────────►│
   │                             │◄─── rowsAffected ─────────┤
   │                             │ if 0 → ROLLBACK, 412
   │                             │ INSERT INTO document_revision (version 8)
   │                             │ COMMIT
   │◄────────────────────────────┤ 200 + ETag: "8"
   │ clear draft, state = SAVED
```

**Flow C: stale save from a second tab**

```
Tab A saves → version 7 → 8
Tab B still holds version 7
Tab B sends If-Match: "7"
UPDATE ... WHERE version = 7 → rowsAffected 0
Server returns 412 + currentVersion 8 + current content
Tab B enters CONFLICT state, shows both texts, user picks
```

Stage 0 resolves conflict by user choice. Stage 5 replaces the choice with automatic merge. The detection mechanism stays identical. That is the point of building it now.

### 4.2 Consistency model

- Single-row atomic write. PostgreSQL default isolation `READ COMMITTED` suffices.
- The conditional `UPDATE ... WHERE version = ?` is the entire concurrency control. It runs as one atomic statement. No read-then-write gap exists.
- Never implement this as `SELECT version` then `UPDATE`. Two concurrent requests both read 7, both write 8, one edit disappears silently. This is the classic lost update. The conditional update removes it by construction.

### 4.3 Save policy

- Debounce 800 ms after the last keystroke.
- Force flush on `blur`, on `visibilitychange` to hidden, and on explicit Ctrl+S.
- Maximum one in-flight save request. Further edits during flight set a `pendingDirty` flag. On response, if `pendingDirty` is true, schedule immediately.
- Hard flush interval: save at least every 30 s while dirty, regardless of typing.
- On `beforeunload` with unsaved changes, write the draft to localStorage synchronously. Do not rely on an async fetch during unload.

---

## 5. Low-level design

### 5.1 Package layout

```
com.deepon.smartdocs
├── SmartdocsApplication.java
├── config
│   ├── JacksonConfig.java          strict deserialization, fail on unknown fields
│   ├── ClockConfig.java            Clock bean, UTC
│   └── WebConfig.java              request size limits, static resource mapping
├── controller
│   ├── DocumentController.java     routes declared on @RequestMapping
│   └── EtagSupport.java            parse and format If-Match and ETag
├── dto
│   ├── CreateDocumentRequest.java
│   ├── UpdateContentRequest.java
│   ├── DocumentResponse.java
│   ├── DocumentSummaryResponse.java
│   └── RevisionSummaryResponse.java
├── service
│   ├── DocumentService.java        interface — the business contract
│   ├── ContentHasher.java
│   └── impl
│       └── DocumentServiceImpl.java
├── validator
│   └── ContentValidator.java
├── repository
│   ├── DocumentRepository.java
│   ├── DocumentRevisionRepository.java
│   ├── DocumentSummaryProjection.java
│   └── RevisionSummaryProjection.java
├── entity
│   ├── Document.java               entity
│   └── DocumentRevision.java       entity
└── exception
    ├── DocumentNotFoundException.java
    ├── VersionMismatchException.java
    ├── PreconditionRequiredException.java
    ├── ContentTooLargeException.java
    ├── InvalidContentException.java
    └── GlobalExceptionHandler.java maps domain errors to ProblemDetail
```

The browser client lives outside this tree, in the top-level `frontend/`
directory; Maven copies it into the jar as `static/` at build time.

### 5.2 Responsibility of each unit

**ContentValidator**
- Rejects null content. Empty string is valid.
- Rejects UTF-8 byte length above 1,048,576 (1 MiB).
- Rejects any `U+0000` code point. PostgreSQL `text` refuses NUL bytes. Catching it here produces a clean 422 instead of a driver exception.
- Rejects `\r`. The browser sends `\n` only from a textarea value. A `\r` signals a non-browser client or a corrupted payload. Rejecting keeps server bytes identical to client bytes.
- Rejects unpaired UTF-16 surrogates. Java strings hold them, UTF-8 encoding produces replacement characters, and the round trip breaks the hash.
- Returns nothing on success. Throws `InvalidContentException` with a field-level reason on failure.

**ContentHasher**
- SHA-256 over `content.getBytes(UTF_8)`, hex lowercase, 64 chars.
- Two uses: no-op detection and retry idempotency.

**DocumentService**
- `create(title, initialContent, actorId)` returns the stored document at version 1 with a revision row.
- `get(id)` throws `DocumentNotFoundException` when missing or soft-deleted.
- `updateContent(id, expectedVersion, content, actorId)`:
  1. Validate content.
  2. Compute hash.
  3. Load current row. Missing → 404.
  4. Current version differs from expected → throw `VersionMismatchException` carrying the current version and current content.
  5. Stored hash equals new hash → return current state untouched. No version bump, no revision row.
  6. Execute the conditional update. Zero rows → throw `VersionMismatchException`. Step 4 catches most cases early for a better error body. Step 6 catches the true race.
  7. Insert the revision row at the new version.
  8. Return the updated document.
- `list(limit, offset)` selects summary columns only. Never selects `content`.
- `softDelete(id, expectedVersion)` sets `deleted_at` under the same version guard.

Steps 4 and 6 look redundant. Keep both. Step 4 gives the client the current content in the error body without a second round trip. Step 6 is the correctness guarantee.

**DocumentController**
- Parses `If-Match`. Absent on a mutating request → 428. Present but malformed → 400.
- Maps service results to responses. Sets `ETag` on every 200 and 201.
- Sets `Location` on 201.
- Never contains business rules.

**GlobalExceptionHandler**
- One `@ExceptionHandler` per domain exception type.
- Produces RFC 9457 `application/problem+json`.
- Includes a stable machine-readable `code` field. Clients branch on `code`, never on `detail` text.

### 5.3 Entity mapping notes

- `Document.version` maps to the `version` column as a plain `long`, not JPA `@Version`. Reason: you control the increment inside a native conditional update. JPA optimistic locking hides the SQL and fights the explicit query.
- Set `spring.jpa.hibernate.ddl-auto=validate`. Liquibase owns schema. Hibernate only verifies agreement. A mismatch fails at boot, not at the first request.
- `content` maps to `@Column(columnDefinition = "text")`. Do not annotate with `@Lob` on PostgreSQL. `@Lob` on a String maps to `oid` large objects on some driver configurations and breaks reads.

### 5.4 Frontend state machine

```
        ┌──────────────────────────────────┐
        ▼                                  │
     ┌──────┐  edit   ┌───────┐  flush  ┌────────┐
     │ IDLE │────────►│ DIRTY │────────►│ SAVING │
     └──────┘         └───────┘         └────────┘
        ▲                 ▲                │ │ │
        │ 200             │ edit during    │ │ │
        │                 └────flight──────┘ │ │
        │                                    │ │
        │              412 ┌──────────┐      │ │
        │              ◄───│ CONFLICT │◄─────┘ │
        │                  └──────────┘        │
        │                        │ resolve     │
        │                        ▼             │
        │                   back to SAVING     │
        │                                      │
        │            5xx / network ┌───────┐   │
        └──────────────────────────│ ERROR │◄──┘
                  retry backoff    └───────┘
```

Rules:
- The textarea stays editable in every state, including CONFLICT. Blocking input loses keystrokes and frustrates the user.
- `baseVersion` updates only on a successful save or an explicit conflict resolution.
- ERROR retries with exponential backoff: 1s, 2s, 4s, 8s, capped at 30s. Reset the backoff on any success.

### 5.5 Draft recovery

localStorage key: `draft:{documentId}`.
Value: `{ content, baseVersion, updatedAt }`.

- Write on every state transition into DIRTY, throttled to once per second.
- Delete on successful save.
- On page load after `GET`:
  - No draft → render server content.
  - Draft exists and `draft.content === server.content` → delete draft, render server content.
  - Draft exists, `draft.baseVersion === server.version`, content differs → offer recovery.
  - Draft exists, `draft.baseVersion < server.version` → the server moved on. Show a conflict view with both versions.

localStorage holds roughly 5 MB per origin. A 1 MiB document plus overhead fits. Wrap every write in try/catch and degrade to no draft on `QuotaExceededError`.

---

## 6. API design

Base path `/api/v1`. Media type `application/json`. Errors use `application/problem+json`.

### 6.1 Endpoint table

| Method | Path | Purpose | Success | Required headers |
|---|---|---|---|---|
| POST | `/documents` | Create | 201 | none |
| GET | `/documents` | List summaries | 200 | none |
| GET | `/documents/{id}` | Read full document | 200 | none |
| PUT | `/documents/{id}/content` | Replace content | 200 | `If-Match` |
| PATCH | `/documents/{id}` | Rename | 200 | `If-Match` |
| DELETE | `/documents/{id}` | Soft delete | 204 | `If-Match` |
| GET | `/documents/{id}/revisions` | Revision list | 200 | none |
| GET | `/documents/{id}/revisions/{version}` | One revision body | 200 | none |

Content lives under a sub-resource `/content` rather than on the document itself. Reason: renaming and editing become independent operations with independent conflict behaviour. Later stages add `/presence`, `/operations`, `/locks` under the same document path. The shape stays consistent.

### 6.2 Create

```
POST /api/v1/documents
Content-Type: application/json

{ "title": "Notes", "content": "" }
```

```
201 Created
Location: /api/v1/documents/0f8d3b2a-6c4e-4a91-9d7f-2b5c8e1a3d40
ETag: "1"

{
  "id": "0f8d3b2a-6c4e-4a91-9d7f-2b5c8e1a3d40",
  "title": "Notes",
  "content": "",
  "version": 1,
  "contentHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "contentSizeBytes": 0,
  "createdAt": "2026-09-14T09:12:04.118Z",
  "updatedAt": "2026-09-14T09:12:04.118Z"
}
```

`title` optional, defaults to `Untitled`, max 255 chars after trim. `content` optional, defaults to empty string.

### 6.3 Read

```
GET /api/v1/documents/{id}
```

```
200 OK
ETag: "7"
Cache-Control: no-store

{ "id": "...", "title": "Notes", "content": "hello\nworld",
  "version": 7, "contentHash": "...", "contentSizeBytes": 11,
  "createdAt": "...", "updatedAt": "..." }
```

`Cache-Control: no-store` prevents a stale body reload after browser back navigation.

Optional refinement: honour `If-None-Match` and return 304 when the version matches. Cheap to add, saves bandwidth on reload of a large document.

### 6.4 Update content

```
PUT /api/v1/documents/{id}/content
If-Match: "7"
Content-Type: application/json

{ "content": "hello\nworld\n", "clientHash": "9f86d0818..." }
```

Success:
```
200 OK
ETag: "8"

{ "id": "...", "version": 8, "contentHash": "9f86d0818...",
  "contentSizeBytes": 12, "updatedAt": "2026-09-14T09:15:30.402Z" }
```

`clientHash` is optional. When present and different from the server-computed hash, return 400 with code `CONTENT_HASH_MISMATCH`. This catches truncated or corrupted bodies before they reach storage.

The response omits `content`. The client already holds it. Echoing a 1 MiB body on every autosave wastes bandwidth.

No-op save: same hash, response is 200 with the unchanged version. The client treats this as success and clears the draft.

### 6.5 Version conflict

```
412 Precondition Failed
Content-Type: application/problem+json
ETag: "8"

{
  "type": "https://smartdocs.dev/problems/version-mismatch",
  "title": "Version mismatch",
  "status": 412,
  "detail": "Document was modified after the version you loaded.",
  "instance": "/api/v1/documents/0f8d3b2a-.../content",
  "code": "VERSION_MISMATCH",
  "expectedVersion": 7,
  "currentVersion": 8,
  "currentContent": "hello\nworld\nedited elsewhere\n",
  "currentContentHash": "b1946ac9249..."
}
```

Including `currentContent` saves a round trip during conflict resolution. Guard it: omit the field when the current content exceeds 256 KiB and set `currentContentTruncated: true`. The client then issues a plain `GET`.

### 6.6 Missing precondition

```
428 Precondition Required
{ "code": "PRECONDITION_REQUIRED",
  "detail": "If-Match header is required on this operation." }
```

Never treat a missing `If-Match` as "force overwrite". A client with no version has no basis to overwrite anything.

### 6.7 Error code catalogue

| HTTP | code | Trigger |
|---|---|---|
| 400 | `MALFORMED_JSON` | Body fails to parse |
| 400 | `MALFORMED_IF_MATCH` | Header present, not a quoted integer |
| 400 | `INVALID_DOCUMENT_ID` | Path segment is not a UUID |
| 400 | `CONTENT_HASH_MISMATCH` | `clientHash` disagrees with server hash |
| 404 | `DOCUMENT_NOT_FOUND` | No row, or `deleted_at` set |
| 405 | `METHOD_NOT_ALLOWED` | Wrong verb on a known path |
| 412 | `VERSION_MISMATCH` | `If-Match` does not equal current version |
| 413 | `CONTENT_TOO_LARGE` | Body above the configured limit |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Content-Type is not JSON |
| 422 | `INVALID_CONTENT_NUL` | NUL code point present |
| 422 | `INVALID_CONTENT_CR` | Carriage return present |
| 422 | `INVALID_CONTENT_SURROGATE` | Unpaired surrogate present |
| 422 | `TITLE_TOO_LONG` | Title above 255 chars |
| 429 | `RATE_LIMITED` | Optional. Save flood guard |
| 500 | `INTERNAL_ERROR` | Everything unmapped |
| 503 | `DATABASE_UNAVAILABLE` | Connection pool exhausted or DB down |

Never echo stack traces or SQL text in a response body. Log the correlation id, return the id in the `detail`.

### 6.8 Cross-cutting HTTP rules

- Every response carries `X-Request-Id`. Generate one when absent from the request.
- Reject bodies above the limit at the container level as well as the application level. Tomcat setting `server.tomcat.max-http-form-post-size` does not cover JSON bodies. Enforce with a filter reading `Content-Length` plus a counting wrapper on the input stream, because `Content-Length` is absent on chunked requests.
- Timestamps serialize as ISO-8601 UTC with `Z`. Configure Jackson with `WRITE_DATES_AS_TIMESTAMPS` disabled.
- Jackson strict mode: `FAIL_ON_UNKNOWN_PROPERTIES` enabled. A typo in a client field name surfaces as a 400 instead of silent data loss.

---

## 7. Database design

### 7.1 Tables

**document**

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK |
| title | varchar(255) | NOT NULL, DEFAULT 'Untitled' |
| content | text | NOT NULL, DEFAULT '' |
| content_hash | char(64) | NOT NULL |
| content_size_bytes | integer | NOT NULL, CHECK 0..1048576 |
| version | bigint | NOT NULL, DEFAULT 1, CHECK >= 1 |
| created_by | varchar(64) | NOT NULL, DEFAULT 'anonymous' |
| updated_by | varchar(64) | NOT NULL, DEFAULT 'anonymous' |
| created_at | timestamptz | NOT NULL, DEFAULT now() |
| updated_at | timestamptz | NOT NULL, DEFAULT now() |
| deleted_at | timestamptz | NULL |

**document_revision**

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK |
| document_id | uuid | NOT NULL, FK → document(id) ON DELETE CASCADE |
| version | bigint | NOT NULL, CHECK >= 1 |
| content | text | NOT NULL |
| content_hash | char(64) | NOT NULL |
| content_size_bytes | integer | NOT NULL |
| actor_id | varchar(64) | NOT NULL, DEFAULT 'anonymous' |
| actor_type | varchar(16) | NOT NULL, DEFAULT 'HUMAN' |
| created_at | timestamptz | NOT NULL, DEFAULT now() |

Unique constraint `uk_revision_document_version (document_id, version)`. This makes a duplicate revision insert fail loudly instead of corrupting history.

### 7.2 Indexes

```
idx_document_active_updated  ON document (updated_at DESC) WHERE deleted_at IS NULL
idx_revision_doc_version     ON document_revision (document_id, version DESC)
```

The partial index keeps the list query off soft-deleted rows without a filter scan.

### 7.3 Storage notes

- PostgreSQL TOASTs `text` above roughly 2 KB, storing it out of line and compressed. A large `content` column does not slow a `SELECT id, title, version` query as long as you never write `SELECT *`. Keeping content in the same table is correct at this scale. Splitting into `document_content` becomes worthwhile only when row-level locking contention appears, which needs concurrent writers. Revisit at Stage 3.
- Full content per revision is wasteful at scale. Accept it at Stage 0. Note the migration path: Stage 5 replaces revision bodies with operation logs plus periodic snapshots. The `document_revision` table keeps its shape, gaining `parent_version` and `operations` columns.
- `char(64)` for the hash, not `varchar`. Fixed width, no length header, and the value is always exactly 64 hex chars.

### 7.4 Why version starts at 1

A created document has content, even empty content. Version 0 would mean "no state exists", which is false after the insert. Starting at 1 keeps `document.version` equal to `count(document_revision)` at Stage 0, which makes a useful invariant to assert in tests.

### 7.5 The critical SQL

```sql
UPDATE document
   SET content = :content,
       content_hash = :hash,
       content_size_bytes = :size,
       version = version + 1,
       updated_at = now(),
       updated_by = :actorId
 WHERE id = :id
   AND version = :expectedVersion
   AND deleted_at IS NULL
RETURNING version;
```

Zero rows returned means one of three things: the document is gone, soft-deleted, or someone else wrote first. Distinguish by a follow-up `SELECT`. Do not guess.

### 7.6 Liquibase structure

```
src/main/resources/db/changelog/
├── db.changelog-master.yaml
└── changes/
    ├── 001-create-document.yaml
    ├── 002-create-document-revision.yaml
    ├── 003-create-indexes.yaml
    └── 004-tag-stage-0.yaml
```

Master file includes each change file with `relativeToChangelogFile: true`.

Rules to enforce from day one:
- One logical change per changeset. A changeset with five `createTable` blocks rolls back as a unit and fails as a unit.
- Every changeset carries an explicit `rollback` block. Liquibase auto-generates rollback for `createTable` and `addColumn`, and generates nothing useful for `sql` changesets. Write it yourself for those.
- Never edit an applied changeset. Liquibase stores an MD5 checksum in `databasechangelog`. Editing breaks startup on every environment where the changeset already ran. Add a new changeset instead.
- Set `logicalFilePath` on every change file. Moving or renaming files later then leaves checksums intact.
- Tag after each stage: `tagDatabase` with tag `stage-0`. Rollback to a stage boundary becomes one command.
- Use `preConditions` with `onFail: MARK_RAN` when a changeset must tolerate a pre-existing object.
- Keep seed data in a separate changelog gated by `context: dev`. Production runs without the context and skips it.
- Run `liquibase.contexts` through Spring config, not hardcoded.

Verification step: point Liquibase at an empty database, run `update`, then run `rollback stage-0` and confirm a clean empty schema. A rollback path proven once is worth more than a rollback path assumed forever.

### 7.7 Connection pool baseline

```
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.connection-timeout=3000
spring.datasource.hikari.validation-timeout=1000
spring.jpa.properties.hibernate.jdbc.batch_size=20
```

A 3-second connection timeout turns a database outage into a fast 503 instead of a thread pool pile-up. Default 30 s exhausts Tomcat threads under load and takes the whole app down.

---

## 8. Edge cases

### 8.1 Content

| Case | Behaviour |
|---|---|
| Empty string | Valid. Hash of empty input. Version still increments if the previous content differed. |
| Whitespace only | Valid. No trimming. Trimming loses user intent. |
| 1 MiB exactly | Accept. Boundary test both 1048576 and 1048577. |
| Multi-byte emoji | Size measured in UTF-8 bytes, not `String.length()`. A 4-byte emoji counts as 4. |
| Combining characters | Stored as submitted. No NFC normalization. Normalizing shifts offsets and breaks future operation mapping. |
| NUL code point | 422. PostgreSQL `text` refuses it outright. |
| Unpaired surrogate | 422. Round-trip through UTF-8 loses it. |
| `\r\n` line endings | 422 with `INVALID_CONTENT_CR`. Client normalizes before sending. |
| Extremely long single line | Valid. No line-length rule. |
| Content identical to stored | 200, no version bump, no revision row. |
| Right-to-left or bidi control chars | Stored as submitted. Escape at render time in the browser. |

### 8.2 Concurrency

| Case | Behaviour |
|---|---|
| Two tabs, both save | First wins, second gets 412 with current state. |
| Two requests in the same millisecond | The conditional `UPDATE` serializes them at the row lock. One affects 1 row, the other 0. |
| Save arrives while a delete commits | `deleted_at IS NULL` in the WHERE clause fails the update. Follow-up SELECT reveals deletion. Return 404, not 412. |
| Client retries after a timeout, original succeeded | Retry carries the old `If-Match`. Returns 412 with `currentContentHash` equal to the client's hash. Client detects the match and treats it as success. |
| Revision insert fails after the document update | Same transaction. Both roll back. The unique constraint guarantees no partial history. |

### 8.3 Network and client

| Case | Behaviour |
|---|---|
| Offline during save | Fetch rejects. State → ERROR. Draft persists in localStorage. Backoff retry. |
| Tab closed with unsaved edits | `beforeunload` writes the draft synchronously. Recovery on next open. |
| localStorage full | Catch `QuotaExceededError`. Warn once, continue without drafts. |
| localStorage disabled (private mode) | Feature-detect at boot. Disable draft recovery, keep autosave. |
| Browser back after save | `Cache-Control: no-store` prevents a stale body. |
| Slow 3G, 1 MiB body | Show a saving indicator after 500 ms. Do not block typing. |
| Duplicate rapid saves | Single in-flight rule plus `pendingDirty` coalescing. |
| Clock skew on the client | Server timestamps are authoritative. Client timestamps are display only. |

### 8.4 Server and infrastructure

| Case | Behaviour |
|---|---|
| Database down at boot | Liquibase fails, app refuses to start. Correct. A running app with an unmigrated schema is worse. |
| Database down at runtime | 503 with `DATABASE_UNAVAILABLE`. Actuator health reports DOWN. |
| Pool exhausted | 3 s connection timeout → 503. No thread pile-up. |
| Liquibase lock stuck after a crash | `databasechangeloglock` holds a stale row. Documented recovery: `UPDATE databasechangeloglock SET locked = false`. Put this in the runbook now. |
| Two app instances boot together | Liquibase lock serializes migrations. The second instance waits, then starts. Verify explicitly. |
| Restart mid-request | Transaction rolls back. No partial write. |
| Malformed UUID in path | 400, not 500. Register a handler for `MethodArgumentTypeMismatchException`. |
| Request without Content-Type | 415. |
| HEAD request on a document | Return headers with no body. Spring handles this by default. Confirm the ETag still appears. |

### 8.5 Data lifecycle

| Case | Behaviour |
|---|---|
| Read a soft-deleted document | 404. Do not leak existence. |
| Update a soft-deleted document | 404. |
| Delete an already deleted document | 404. Delete is not idempotent here by design, because the version guard needs a live row. |
| Revision list on a deleted document | 404. |
| Revision count grows unbounded | Accepted at Stage 0. Note a retention policy as a Stage 2 item. |

---

## 9. Testing strategy

| Layer | Tool | Covers |
|---|---|---|
| Unit | JUnit 5, AssertJ | ContentValidator boundaries, ContentHasher determinism, ETag parsing |
| Persistence | `@DataJpaTest` plus Testcontainers PostgreSQL | Conditional update row counts, unique constraint, partial index usage |
| Web slice | `@WebMvcTest` with a mocked service | Status code mapping, ProblemDetail shape, header handling |
| Integration | `@SpringBootTest` plus Testcontainers | Full save and reload, restart persistence, Liquibase migration |
| Concurrency | Two threads, `CountDownLatch`, real database | Exactly one of two same-version saves succeeds |
| Migration | Testcontainers, empty DB | `update` then `rollback stage-0` leaves a clean schema |

Never use H2. H2 accepts SQL PostgreSQL rejects and rejects SQL PostgreSQL accepts. A green H2 suite plus a red production database is the worst outcome available.

Concrete concurrency test shape:
1. Create a document, capture version 1.
2. Two threads await one latch.
3. Both send `If-Match: "1"` with different content.
4. Release the latch.
5. Assert exactly one 200 and one 412.
6. Assert `document.version` equals 2 and revision count equals 2.

Run it 100 times in a loop. A race passing once proves nothing.

---

## 10. Observability

- Structured JSON logs with `requestId`, `documentId`, `version`, `durationMs`.
- Counters: `document_save_total`, `document_save_conflict_total`, `document_save_noop_total`, `document_validation_rejected_total{code}`.
- Timer: `document_save_duration` with percentiles.
- Actuator endpoints `health`, `info`, `metrics` exposed in dev only.

The conflict counter matters. At Stage 0 with one user it should sit near zero. A rising number reveals a bug in the client version tracking before users report data loss.

---

## 11. Build order

Ten steps. Each step ends with a verifiable check. Do not begin a step before the previous check passes.

### Step 1: Project skeleton
Files: `build.gradle.kts` or `pom.xml`, `SmartdocsApplication.java`, `application.yaml`, `docker-compose.yml`.
Dependencies: web, data-jpa, validation, liquibase, postgresql driver, actuator, testcontainers, junit.
Check: app boots with a real PostgreSQL container, `/actuator/health` returns UP.

### Step 2: Liquibase wiring
Files: master changelog, `001-create-document.yaml`.
Configure `spring.liquibase.change-log`, set `ddl-auto: validate`.
Check: table exists after boot. Drop the database, restart, table returns.

### Step 3: Revision table and indexes
Files: `002`, `003`, `004` change files.
Check: `rollback stage-0` against a fresh database leaves zero application tables.

### Step 4: Entities and repositories
Files: `Document`, `DocumentRevision`, both repositories. Add the native conditional update as a `@Modifying @Query` returning `int`.
Check: `@DataJpaTest` proves the conditional update returns 1 on a version match and 0 on a mismatch.

### Step 5: Validation and hashing
Files: `ContentValidator`, `ContentHasher`, the exception classes.
Check: unit tests cover every row in section 8.1.

### Step 6: Service layer
Files: `DocumentService`.
Implement create, get, updateContent, list, softDelete. Wrap updateContent in `@Transactional`.
Check: integration test saves, restarts the context, reloads, asserts identical bytes and hash.

### Step 7: Web layer
Files: controller, DTOs, `EtagSupport`, `GlobalExceptionHandler`, `JacksonConfig`.
Check: `@WebMvcTest` asserts every status code in section 6.7.

### Step 8: Concurrency proof
Files: one integration test class.
Check: the 100-iteration two-thread test passes with no flake.

### Step 9: Frontend
Files: `index.html`, `editor.js`, `api.js`, `draft.js`, `styles.css`.
Build in order: render loaded content, then manual save, then the state machine, then debounced autosave, then draft recovery, then the conflict view.
Check: open two tabs, edit both, confirm the conflict view appears in the second with both texts visible.

### Step 10: Hardening and documentation
Add the size filter, request id filter, metrics, and the runbook covering the stuck Liquibase lock.
Check: a 2 MiB body returns 413 without an OutOfMemoryError in the log.

---

## 12. Definition of done for Stage 0

- Content survives a full application restart and a database container restart with an identical SHA-256 hash.
- Two browser tabs produce a detected conflict, never a silent overwrite.
- Every error path returns `application/problem+json` with a stable `code`.
- Liquibase migrates from empty and rolls back to empty.
- The concurrency test passes 100 consecutive runs.
- Two application instances against one database behave identically to one.
- Zero `SELECT *` in the codebase.
- Test coverage of `DocumentService` and `ContentValidator` above 90 percent by line.

---

## 13. What Stage 1 inherits

- `version` becomes the causal clock for operation ordering.
- `actor_id` and `actor_type` start carrying real values.
- `document_revision` gains `parent_version` and turns into a DAG rather than a line.
- The 412 conflict path gains an automatic merge attempt before falling back to user choice.
- `PUT /content` gains a sibling `POST /operations` for incremental edits.

None of these require a schema rewrite. That is the return on the discipline applied at Stage 0.
