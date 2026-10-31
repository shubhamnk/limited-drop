# AI Usage

This project was built with **Cursor Cloud Agents** (Composer) against the technical design in `DESIGN.md` / the pasted TDD. AI was treated as an implementer under human direction: the design document was the contract; AI filled in the Spring Boot / Flyway / Docker / API surface and tests.

## Tools used and how context was given

| Tool | Role | Context provided |
|------|------|------------------|
| **Cursor Cloud Agent (Composer)** | Primary coding agent in the GitHub `limited-drop` workspace | Full TDD paste; stack constraints (Java 17, Spring Boot 3.2+, MySQL, Redis, RabbitMQ); API shapes; concurrency / outbox / idempotency rules |
| **Parallel Cursor Task subagents** | Split scaffolding vs domain/API to speed greenfield work | Explicit file boundaries (one agent: `pom.xml`, Flyway, Docker; other: entities, services, controllers, tests); “do not push / do not open PR” for workers |
| **Maven (`mvn test` / `package`)** | Verify default test suite without live MySQL/Redis/RabbitMQ | Agent ran tests after each meaningful change; H2 is *not* used as proof of MySQL JSON binding |
| **GitHub (`gh`, ManagePullRequest)** | Branch / PR / merge workflow | Conventional commits; README left alone until explicitly asked to rewrite; design content moved to `DESIGN.md` |
| **Postman collection (authored via AI)** | Manual API exercise after Docker bring-up | Controllers + `api-docs.md` as source of truth for paths, headers, status codes |

**How context was steered (not “generate an app”):**

1. Paste / point at the **TDD** as the source of truth (inventory SQL, hold lifecycle, outbox, HTTP semantics).
2. Call out **non-negotiables**: MySQL owns inventory (not Redis DECR counters — Redis down ⇒ unsafe); Redis fail-open for reads only; no Rabbit publish inside business TX; customer id from auth header, not body.
3. Separate docs from code: when ports conflicted, ask for a **file only** without committing; when README was wrong, move TDD into `DESIGN.md` and rewrite run/API docs.
4. Use **runtime evidence** (MySQL error 3144, RabbitMQ connect log) to drive a targeted entity fix rather than a redesign.

---

## Accepted vs rejected suggestions (≥ 3)

### 1. Inventory counters in Redis vs MySQL (core architecture)

| | |
|---|---|
| **Initial idea (considered / suggested)** | Keep drop inventory as **Redis counters** (`available`, `reserved`, `sold`). Each create-hold would `DECR` available / `INCR` reserved; confirm/cancel/expire would adjust the same keys. Fast under load, easy to sketch. |
| **Decision** | **Rejected** Redis as the inventory authority. **Accepted** MySQL as source of truth with atomic conditional `UPDATE`s on `drops`, plus Redis only as **cache-aside** for drop metadata reads. |
| **Technical reason** | If Redis goes down, restarts, or loses data, counters and the durable hold rows diverge — you can oversell, undersell, or refuse valid holds. Reservation correctness cannot depend on cache availability. MySQL transactions + `available_units >= :qty` (and the units balance check) keep allocate / confirm / cancel / expire consistent across app instances. Redis remaining role: speed up `GET /drops` style reads; on Redis failure, fall through to MySQL and still allow holds. |

```text
Rejected path:  hold → Redis DECR available  (what if Redis is down?)
Chosen path:    hold → MySQL UPDATE … WHERE available_units >= qty  (+ hold row + outbox in one TX)
                Redis ← optional cache of drop metadata only
```

### 2. Spring Boot version / project bootstrap

| | |
|---|---|
| **AI suggestion** | Generate the project from `start.spring.io` with Spring Boot **4.x** (what the public Initializr currently defaults to). |
| **Decision** | **Rejected** Boot 4 for this assignment. **Accepted** a hand-written Maven project on **Spring Boot 3.3.5** (satisfies “3.2+”). |
| **Technical reason** | The TDD specifies Spring Boot 3.2+. Boot 4 changes the baseline and would muddy review against the design. Manual `pom.xml` + Java 17 `release` keeps the stack aligned with the document. |

### 3. Confirm/cancel of an already-expired hold

| | |
|---|---|
| **AI suggestion (first cut)** | Inside `TransactionTemplate`, expire the hold, release inventory, then `throw new ConflictException(...)`. |
| **Decision** | **Rejected** throw-inside-TX as the conflict path. **Accepted** a result object (`ConfirmResult` / `CancelResult`): commit expire+release, *then* surface `409`. |
| **Technical reason** | Spring rolls back on unchecked exceptions leaving the callback. TDD §5.4 requires the expire+release to be **durable** before the client sees conflict. Throwing inside the TX would undo the correctness work and leave inventory reserved. |

### 4. Mapping MySQL `JSON` columns in JPA

| | |
|---|---|
| **AI suggestion (initial entities)** | Annotate `payload` / `response_body` with `@Lob` + `columnDefinition = "JSON"`. |
| **Decision** | **Rejected** `@Lob` after production MySQL failure. **Accepted** plain `String` + `columnDefinition = "JSON"`. |
| **Technical reason** | Hibernate binds `@Lob` `String` as a **binary** LOB. MySQL error **3144** / SQLState **22001**: JSON columns reject binary charset. Outbox insert failed first → hold TX rolled back → API `500`. H2 unit tests never exercised MySQL JSON binding, so they stayed green. |

### 5. README content (related steering)

| | |
|---|---|
| **AI suggestion** | Expand `README.md` with clone paths, long runbooks, or keep the full TDD pasted into README. |
| **Decision** | **Rejected** treating README as the TDD. **Accepted**: TDD in `DESIGN.md`; README = how to run + API docs; later add Postman path only. |
| **Technical reason** | Reviewers need a short operational entry point. The 1k+ line TDD belongs in `DESIGN.md`. Explicit human steer: “don’t change readme” then later “proper readme with how to run and API documentation” after the design paste. |

---

## How correctness was convinced (not assumed)

1. **Design conformance checklist** — Atomic allocate/confirm/release SQL, hold state machine, outbox in the same TX as business writes, idempotency key + request hash, `X-Customer-Id` as principal, Redis cache-aside fail-open.
2. **Automated tests** — `mvn test` (25 tests): Mockito service tests for create/confirm/cancel/idempotency paths; `@WebMvcTest` for HTTP status / headers. Used as a **regression net**, not as proof of MySQL-specific types.
3. **Bug-driven fix with dialect evidence** — Live MySQL 3144 + stack pointing at outbox/idempotency JSON writes → removed `@Lob` → re-ran `mvn test` → instructed image rebuild (`docker compose up --build`) so the running container picks up the entity change.
4. **API contract artifacts** — `api-docs.md`, README API section, and `postman/Limited-Drop-Reservation-Service.postman_collection.json` match controller mappings so manual verification is repeatable (list → create → get → confirm/cancel).
5. **What was *not* trusted** — Green H2/Mockito tests alone for MySQL JSON, `SKIP LOCKED`, or real Redis/Rabbit failure modes. Those need Docker / Testcontainers or a live stack.

---

## Practical import / verify after merge

```bash
# rebuild app after the JSON @Lob fix
docker compose up --build

# import postman/Limited-Drop-Reservation-Service.postman_collection.json
# set baseUrl if needed (8080 default compose, or remapped host port e.g. 8081)
```
