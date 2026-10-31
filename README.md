# Limited Drop Reservation Service

Spring Boot service for scarce inventory drops: create a temporary hold, then confirm or cancel before it expires. MySQL is the source of truth for inventory; Redis caches drop reads; RabbitMQ receives hold lifecycle events through a transactional outbox.

| | |
|---|---|
| **Stack** | Java 17, Spring Boot 3.3, MySQL 8, Redis, RabbitMQ |
| **Design** | Full TDD: [`DESIGN.md`](DESIGN.md) |
| **API (detailed)** | Also mirrored in [`api-docs.md`](api-docs.md) |
| **Postman** | [`postman/Limited-Drop-Reservation-Service.postman_collection.json`](postman/Limited-Drop-Reservation-Service.postman_collection.json) |
| **AI usage** | How AI was steered on this project: [`AI-USAGE.md`](AI-USAGE.md) |

---

## How to run

### Prerequisites

- Docker and Docker Compose
- (Optional, for local tests) JDK 17+ and Maven 3.8+

### One-command startup

```bash
docker-compose up --build
```

This starts:

| Service | URL / port |
|---------|------------|
| Application | http://localhost:8080 |
| MySQL | `localhost:3306` (`drop_db` / `drop_user` / `drop_pass`) |
| Redis | `localhost:6379` |
| RabbitMQ AMQP | `localhost:5672` |
| RabbitMQ management UI | http://localhost:15672 (`guest` / `guest`) |

Flyway applies schema + seed drops on startup. The app waits until MySQL, Redis, and RabbitMQ are healthy.

### Health check

```bash
curl -s http://localhost:8080/actuator/health
```

### Stop

```bash
docker-compose down
```

### Run tests (no Docker required)

```bash
mvn test
```

Default tests use Mockito / `@WebMvcTest` and do not need live MySQL, Redis, or RabbitMQ.

### Useful configuration

Defaults live in `src/main/resources/application.yml`. Docker overrides use profile `docker`.

| Property | Default | Meaning |
|----------|---------|---------|
| `app.hold.duration-seconds` | `300` | Hold TTL |
| `app.hold.max-quantity` | `10` | Max units per hold |
| `app.expiration.fixed-delay-ms` | `5000` | Expire-scheduler interval |
| `app.cache.drop-ttl-seconds` | `60` | Drop metadata Redis TTL |

---

## Authentication (development)

| Header | When | Purpose |
|--------|------|---------|
| `X-Customer-Id` | Required for hold APIs | Dev customer identity (security principal) |
| `Idempotency-Key` | Required for create / confirm / cancel | Safe client retries |
| `X-Request-Id` | Optional | Correlation ID in logs |
| `Content-Type` | JSON bodies | `application/json` |

Public (no auth): `GET /api/v1/drops`, `GET /api/v1/drops/{dropId}`, and actuator health/metrics/prometheus.

Customer identity always comes from `X-Customer-Id`. Do **not** send `customerId` in the request body.

---

## API documentation

Base URL: `http://localhost:8080`  
Prefix: `/api/v1`  
Timestamps: ISO-8601 UTC (e.g. `2026-10-31T07:00:00Z`)

### Overview

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/api/v1/drops` | No | List drops |
| `GET` | `/api/v1/drops/{dropId}` | No | Drop details + inventory |
| `POST` | `/api/v1/drops/{dropId}/holds` | Yes | Create hold |
| `GET` | `/api/v1/holds/{holdId}` | Yes | Get hold (owner only) |
| `POST` | `/api/v1/holds/{holdId}/confirm` | Yes | Confirm hold |
| `POST` | `/api/v1/holds/{holdId}/cancel` | Yes | Cancel hold |

**Drop status:** `SCHEDULED` \| `LIVE` \| `CLOSED`  
**Hold status:** `ACTIVE` \| `CONFIRMED` \| `CANCELLED` \| `EXPIRED`

### List drops

```http
GET /api/v1/drops
```

```bash
curl -s http://localhost:8080/api/v1/drops
```

Example response:

```json
[
  {
    "id": 1,
    "name": "Midnight Sneaker Drop",
    "description": "Limited collab sneakers — LIVE with full inventory.",
    "totalUnits": 100,
    "availableUnits": 100,
    "reservedUnits": 0,
    "soldUnits": 0,
    "status": "LIVE",
    "startsAt": "2026-01-01T00:00:00Z",
    "endsAt": "2026-12-31T23:59:59Z",
    "createdAt": "2026-10-31T06:00:00Z",
    "updatedAt": "2026-10-31T06:00:00Z"
  }
]
```

### Get drop

```http
GET /api/v1/drops/{dropId}
```

```bash
curl -s http://localhost:8080/api/v1/drops/1
```

| Status | Meaning |
|--------|---------|
| `200` | Drop found |
| `404` | Unknown drop |

### Create hold

```http
POST /api/v1/drops/{dropId}/holds
```

Request body:

```json
{
  "quantity": 1
}
```

`quantity` is required, minimum `1`, maximum `app.hold.max-quantity` (default `10`).

```bash
curl -s -X POST http://localhost:8080/api/v1/drops/1/holds \
  -H 'Content-Type: application/json' \
  -H 'X-Customer-Id: cust-1' \
  -H 'Idempotency-Key: create-hold-001' \
  -d '{"quantity":1}'
```

Example `201 Created` response:

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "dropId": 1,
  "customerId": "cust-1",
  "quantity": 1,
  "status": "ACTIVE",
  "expiresAt": "2026-10-31T07:05:00Z",
  "createdAt": "2026-10-31T07:00:00Z",
  "updatedAt": "2026-10-31T07:00:00Z"
}
```

| Status | Meaning |
|--------|---------|
| `201` | Hold created (available → reserved) |
| `400` | Invalid quantity or missing `Idempotency-Key` |
| `401` | Missing `X-Customer-Id` |
| `404` | Drop not found |
| `409` | Insufficient inventory / drop not allocatable, or idempotency key reused with a different payload |

Same `Idempotency-Key` + same payload returns the original result. Same key + different payload → `409`.

### Get hold

```http
GET /api/v1/holds/{holdId}
```

```bash
curl -s http://localhost:8080/api/v1/holds/550e8400-e29b-41d4-a716-446655440000 \
  -H 'X-Customer-Id: cust-1'
```

| Status | Meaning |
|--------|---------|
| `200` | Hold owned by caller |
| `403` | Hold belongs to another customer |
| `404` | Unknown hold |

### Confirm hold

```http
POST /api/v1/holds/{holdId}/confirm
```

Moves an `ACTIVE`, non-expired hold to `CONFIRMED` (reserved → sold).

```bash
curl -s -X POST http://localhost:8080/api/v1/holds/<holdId>/confirm \
  -H 'X-Customer-Id: cust-1' \
  -H 'Idempotency-Key: confirm-hold-001'
```

| Status | Meaning |
|--------|---------|
| `200` | Confirmed |
| `409` | Cancelled, expired, or not active. If still `ACTIVE` but past expiry, the service commits `EXPIRED` + inventory release, then returns `409`. |

### Cancel hold

```http
POST /api/v1/holds/{holdId}/cancel
```

Moves an `ACTIVE` hold to `CANCELLED` (reserved → available). Confirmed holds cannot be cancelled.

```bash
curl -s -X POST http://localhost:8080/api/v1/holds/<holdId>/cancel \
  -H 'X-Customer-Id: cust-1' \
  -H 'Idempotency-Key: cancel-hold-001'
```

| Status | Meaning |
|--------|---------|
| `200` | Cancelled (already-cancelled is a no-op for inventory) |
| `409` | Confirmed, expired, or not active |

### Error response shape

```json
{
  "timestamp": "2026-10-31T06:45:00Z",
  "status": 409,
  "error": "Conflict",
  "message": "Unable to allocate inventory for drop 1",
  "path": "/api/v1/drops/1/holds",
  "code": "INSUFFICIENT_INVENTORY"
}
```

Common codes: `VALIDATION_ERROR`, `INVALID_QUANTITY`, `IDEMPOTENCY_KEY_REQUIRED`, `DROP_NOT_FOUND`, `HOLD_NOT_FOUND`, `HOLD_FORBIDDEN`, `INSUFFICIENT_INVENTORY`, `HOLD_EXPIRED`, `HOLD_CANCELLED`, `HOLD_CONFIRMED`, `HOLD_NOT_ACTIVE`.

### Hold lifecycle

```text
[*] --> ACTIVE       create hold
ACTIVE --> CONFIRMED confirm before expiry
ACTIVE --> CANCELLED cancel
ACTIVE --> EXPIRED   scheduler, or confirm/cancel after expiry
```

### Quick end-to-end script

```bash
BASE=http://localhost:8080
CUST=cust-1

curl -s "$BASE/api/v1/drops"

HOLD=$(curl -s -X POST "$BASE/api/v1/drops/1/holds" \
  -H 'Content-Type: application/json' \
  -H "X-Customer-Id: $CUST" \
  -H 'Idempotency-Key: demo-create-1' \
  -d '{"quantity":1}')
echo "$HOLD"
HOLD_ID=$(echo "$HOLD" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

curl -s "$BASE/api/v1/holds/$HOLD_ID" -H "X-Customer-Id: $CUST"

curl -s -X POST "$BASE/api/v1/holds/$HOLD_ID/confirm" \
  -H "X-Customer-Id: $CUST" \
  -H 'Idempotency-Key: demo-confirm-1'
```

---

## Seeded sample drops

After Docker startup, Flyway seeds example drops (IDs typically `1`, `2`, `3`):

1. **Midnight Sneaker Drop** — `LIVE`, full inventory  
2. **Autumn Capsule Jacket** — `SCHEDULED`  
3. **Last-Chance Tee** — `LIVE`, low remaining inventory  

---

## Messaging (async)

Topic exchange `drop.events` with routing keys:

- `drop.hold.created`
- `drop.hold.confirmed`
- `drop.hold.cancelled`
- `drop.hold.expired`

Bound queues: `drop.notification.queue`, `drop.analytics.queue`, `drop.audit.queue` (with DLQ basics). Events are written to the outbox in the same DB transaction as the business change, then published asynchronously.

---

## Project layout

```text
src/main/java/com/limiteddrop/reservation/
  api/          REST controllers + DTOs
  config/       App + RabbitMQ beans
  domain/       Enums / event types
  entity/       JPA entities (Flyway-aligned)
  exception/    Domain errors + @ControllerAdvice
  metrics/      Micrometer counters / gauges
  repository/   Spring Data + native inventory SQL
  security/     X-Customer-Id filter
  service/      Hold, confirm, cancel, expire, outbox, cache
  web/          Request-id MDC filter
src/main/resources/db/migration/   Flyway V1 schema + V2 seeds
docker-compose.yml
Dockerfile
DESIGN.md       Technical design document
api-docs.md     Extended API reference (same contract as above)
```
