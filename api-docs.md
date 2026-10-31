# Limited Drop Reservation Service — API Docs

Base URL: `http://localhost:8080`  
API prefix: `/api/v1`

All timestamps are ISO-8601 instants (UTC), for example `2026-10-31T07:00:00Z`.

---

## Authentication (development)

Mutating hold APIs and `GET /holds/{holdId}` require a customer identity header:

| Header | Required | Description |
|--------|----------|-------------|
| `X-Customer-Id` | Yes (authenticated routes) | Development customer identity. Used as the authenticated principal. |
| `Idempotency-Key` | Yes (create / confirm / cancel) | Client-generated unique key for safe retries. |
| `X-Request-Id` | No | Correlation ID propagated into logs (MDC `requestId`). Generated if omitted. |
| `Content-Type` | Yes (JSON bodies) | `application/json` |

Public (no auth):

- `GET /api/v1/drops`
- `GET /api/v1/drops/{dropId}`
- Actuator: `/actuator/health`, `/actuator/prometheus`, `/actuator/metrics`

Customer identity always comes from `X-Customer-Id` / the security context. Do not send `customerId` in the request body.

---

## Endpoints

### 1. List drops

```http
GET /api/v1/drops
```

**Auth:** none

**Response:** `200 OK` — JSON array of drops

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

**Drop status values:** `SCHEDULED` | `LIVE` | `CLOSED`

**Example:**

```bash
curl -s http://localhost:8080/api/v1/drops
```

---

### 2. Get drop details

```http
GET /api/v1/drops/{dropId}
```

**Auth:** none  
**Path params:** `dropId` (number)

**Responses:**

| Status | Meaning |
|--------|---------|
| `200 OK` | Drop found (same shape as one list item) |
| `404 Not Found` | Unknown drop |

Drop reads use Redis cache-aside when Redis is available; cache misses fall back to MySQL. Redis failure does not fail the request.

**Example:**

```bash
curl -s http://localhost:8080/api/v1/drops/1
```

---

### 3. Create hold

```http
POST /api/v1/drops/{dropId}/holds
```

**Auth:** `X-Customer-Id` required  
**Headers:** `Idempotency-Key` required  
**Path params:** `dropId` (number)

**Request body:**

```json
{
  "quantity": 2
}
```

| Field | Type | Rules |
|-------|------|-------|
| `quantity` | integer | Required, minimum `1`, maximum configured `app.hold.max-quantity` (default `10`) |

**Responses:**

| Status | Meaning |
|--------|---------|
| `201 Created` | Hold created; inventory moved available → reserved |
| `400 Bad Request` | Invalid quantity or missing `Idempotency-Key` |
| `401 Unauthorized` | Missing / invalid `X-Customer-Id` |
| `404 Not Found` | Drop does not exist |
| `409 Conflict` | Insufficient inventory, drop not LIVE / outside window, or idempotency key reused with a different payload |

**Success body:**

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "dropId": 1,
  "customerId": "cust-1",
  "quantity": 2,
  "status": "ACTIVE",
  "expiresAt": "2026-10-31T07:05:00Z",
  "createdAt": "2026-10-31T07:00:00Z",
  "updatedAt": "2026-10-31T07:00:00Z"
}
```

Default hold TTL: `app.hold.duration-seconds` (default `300` seconds).

**Hold status values:** `ACTIVE` | `CONFIRMED` | `CANCELLED` | `EXPIRED`

**Example:**

```bash
curl -s -X POST http://localhost:8080/api/v1/drops/1/holds \
  -H 'Content-Type: application/json' \
  -H 'X-Customer-Id: cust-1' \
  -H 'Idempotency-Key: create-hold-001' \
  -d '{"quantity":1}'
```

**Idempotency:**

- Same customer + operation + key + same payload → returns the original stored result (HTTP status from first success, typically replayed as the current handler status / stored body).
- Same key with a different payload → `409 Conflict`.

---

### 4. Get hold details

```http
GET /api/v1/holds/{holdId}
```

**Auth:** `X-Customer-Id` required  
**Path params:** `holdId` (UUID string)

**Responses:**

| Status | Meaning |
|--------|---------|
| `200 OK` | Hold owned by the authenticated customer |
| `401 Unauthorized` | Missing customer header |
| `403 Forbidden` | Hold belongs to another customer |
| `404 Not Found` | Unknown hold |

**Example:**

```bash
curl -s http://localhost:8080/api/v1/holds/550e8400-e29b-41d4-a716-446655440000 \
  -H 'X-Customer-Id: cust-1'
```

---

### 5. Confirm hold

```http
POST /api/v1/holds/{holdId}/confirm
```

**Auth:** `X-Customer-Id` required  
**Headers:** `Idempotency-Key` required  
**Body:** none

Converts an `ACTIVE`, non-expired hold to `CONFIRMED` and moves inventory reserved → sold.

**Responses:**

| Status | Meaning |
|--------|---------|
| `200 OK` | Confirmed (or already confirmed for this owner) |
| `400 Bad Request` | Missing `Idempotency-Key` |
| `401 Unauthorized` | Missing customer header |
| `403 Forbidden` | Hold belongs to another customer |
| `404 Not Found` | Unknown hold |
| `409 Conflict` | Cancelled, expired, or no longer active. If the hold had expired while still `ACTIVE`, the service commits `EXPIRED` + inventory release, then returns `409`. |

**Example:**

```bash
curl -s -X POST http://localhost:8080/api/v1/holds/550e8400-e29b-41d4-a716-446655440000/confirm \
  -H 'X-Customer-Id: cust-1' \
  -H 'Idempotency-Key: confirm-hold-001'
```

---

### 6. Cancel hold

```http
POST /api/v1/holds/{holdId}/cancel
```

**Auth:** `X-Customer-Id` required  
**Headers:** `Idempotency-Key` required  
**Body:** none

Converts an `ACTIVE` hold to `CANCELLED` and moves inventory reserved → available.

Cancellation of a `CONFIRMED` hold is not supported.

**Responses:**

| Status | Meaning |
|--------|---------|
| `200 OK` | Cancelled (or already cancelled — no double inventory release) |
| `400 Bad Request` | Missing `Idempotency-Key` |
| `401 Unauthorized` | Missing customer header |
| `403 Forbidden` | Hold belongs to another customer |
| `404 Not Found` | Unknown hold |
| `409 Conflict` | Confirmed, expired, or no longer active. Expired-while-active follows the same commit-then-`409` rule as confirm. |

**Example:**

```bash
curl -s -X POST http://localhost:8080/api/v1/holds/550e8400-e29b-41d4-a716-446655440000/cancel \
  -H 'X-Customer-Id: cust-1' \
  -H 'Idempotency-Key: cancel-hold-001'
```

---

## Error response shape

All domain / validation errors use a consistent JSON body:

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

| Field | Description |
|-------|-------------|
| `timestamp` | Error time (UTC) |
| `status` | HTTP status code |
| `error` | Reason phrase |
| `message` | Human-readable detail (no SQL / stack traces) |
| `path` | Request path |
| `code` | Machine-readable application code |

### Common `code` values

| Code | Typical HTTP | When |
|------|--------------|------|
| `VALIDATION_ERROR` | 400 | Bean validation failed |
| `INVALID_QUANTITY` | 400 | Quantity out of allowed range |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | Missing `Idempotency-Key` |
| `UNAUTHORIZED` | 401 | Missing / invalid auth |
| `FORBIDDEN` / `HOLD_FORBIDDEN` | 403 | Not the hold owner |
| `DROP_NOT_FOUND` / `HOLD_NOT_FOUND` | 404 | Unknown resource |
| `INSUFFICIENT_INVENTORY` | 409 | Allocate failed |
| `HOLD_EXPIRED` | 409 | Hold expired (may have just been transitioned) |
| `HOLD_CANCELLED` | 409 | Confirm attempted on cancelled hold |
| `HOLD_CONFIRMED` | 409 | Cancel attempted on confirmed hold |
| `HOLD_NOT_ACTIVE` | 409 | Invalid state transition lost the race |
| `INTERNAL_ERROR` | 500 | Unexpected failure |

---

## Hold lifecycle

```text
[*] --> ACTIVE       (create hold)
ACTIVE --> CONFIRMED (confirm before expiry)
ACTIVE --> CANCELLED (cancel)
ACTIVE --> EXPIRED   (scheduler or confirm/cancel of an expired hold)
```

Terminal states: `CONFIRMED`, `CANCELLED`, `EXPIRED`.

---

## Observability endpoints

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| GET | `/actuator/health` | none | Liveness / readiness |
| GET | `/actuator/prometheus` | none | Prometheus scrape |
| GET | `/actuator/metrics` | none | Micrometer metrics |

Useful metrics include hold creation success/failure, confirmations, expirations, and outbox pending gauges.

---

## Seeded sample drops (local Docker)

After `docker-compose up --build`, Flyway seeds example drops such as:

1. **Midnight Sneaker Drop** — `LIVE`, 100 available  
2. **Autumn Capsule Jacket** — `SCHEDULED`  
3. **Last-Chance Tee** — `LIVE`, low remaining inventory  

Exact `id` values depend on insert order (typically `1`, `2`, `3`).

---

## Quick end-to-end script

```bash
BASE=http://localhost:8080
CUST=cust-1

# list
curl -s "$BASE/api/v1/drops"

# create
HOLD=$(curl -s -X POST "$BASE/api/v1/drops/1/holds" \
  -H 'Content-Type: application/json' \
  -H "X-Customer-Id: $CUST" \
  -H 'Idempotency-Key: demo-create-1' \
  -d '{"quantity":1}')
echo "$HOLD"
HOLD_ID=$(echo "$HOLD" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

# get
curl -s "$BASE/api/v1/holds/$HOLD_ID" -H "X-Customer-Id: $CUST"

# confirm (or use cancel instead)
curl -s -X POST "$BASE/api/v1/holds/$HOLD_ID/confirm" \
  -H "X-Customer-Id: $CUST" \
  -H 'Idempotency-Key: demo-confirm-1'
```
