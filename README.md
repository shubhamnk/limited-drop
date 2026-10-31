# Limited Drop Reservation Service

Spring Boot service for limited-inventory drop holds: allocate → hold → confirm/cancel/expire, with idempotency, transactional outbox → RabbitMQ, and Redis cache-aside for drop reads.

## Stack

- Java 17 / Spring Boot 3.3
- MySQL 8 (Flyway migrations)
- Redis (drop cache, fail-open)
- RabbitMQ (`drop.events` topic exchange)
- Micrometer / Prometheus actuator metrics

## Run with Docker

```bash
docker-compose up --build
```

App: `http://localhost:8080`  
RabbitMQ management: `http://localhost:15672` (guest/guest)

Flyway runs automatically against MySQL (`V1__init_schema.sql`, `V2__seed_drops.sql`).

## Local configuration

Defaults in `src/main/resources/application.yml`. Docker overrides via profile `docker` (`application-docker.yml`).

| Setting | Default |
|---------|---------|
| Hold TTL | 300s (`app.hold.duration-seconds`) |
| Max quantity | 10 (`app.hold.max-quantity`) |
| Drop cache TTL | 60s |

## Auth (dev)

Mutating APIs and `GET /holds/{id}` require header:

```http
X-Customer-Id: cust-123
```

Customer identity is taken from the security context only (never from the request body).

Optional correlation:

```http
X-Request-Id: <uuid>
```

## API examples

List drops:

```bash
curl -s http://localhost:8080/api/v1/drops | jq
```

Create hold:

```bash
curl -s -X POST http://localhost:8080/api/v1/drops/1/holds \
  -H 'Content-Type: application/json' \
  -H 'X-Customer-Id: cust-1' \
  -H 'Idempotency-Key: create-1' \
  -d '{"quantity":1}' | jq
```

Confirm hold:

```bash
curl -s -X POST http://localhost:8080/api/v1/holds/<holdId>/confirm \
  -H 'X-Customer-Id: cust-1' \
  -H 'Idempotency-Key: confirm-1' | jq
```

Cancel hold:

```bash
curl -s -X POST http://localhost:8080/api/v1/holds/<holdId>/cancel \
  -H 'X-Customer-Id: cust-1' \
  -H 'Idempotency-Key: cancel-1' | jq
```

Health:

```bash
curl -s http://localhost:8080/actuator/health
```

## Messaging

Topic exchange `drop.events` with routing keys:

- `drop.hold.created`
- `drop.hold.confirmed`
- `drop.hold.cancelled`
- `drop.hold.expired`

Bound queues: `drop.notification.queue`, `drop.analytics.queue`, `drop.audit.queue` (+ DLQ basics).

## Tests

Default tests do **not** need MySQL, Redis, or RabbitMQ:

```bash
mvn test
```

Strategy:

- Mockito unit tests for hold / confirm / cancel / idempotency services
- `@WebMvcTest` for controllers + security headers
- `application-test.yml` disables Flyway / Redis / Rabbit autoconfig; uses H2 `ddl-auto=create-drop` if a Spring Data test is added

Optional MySQL concurrency profile (Testcontainers): `-Pconcurrency` (requires Docker).

## Package layout

```
com.limiteddrop.reservation
  api/          REST controllers + DTOs
  config/       App + Rabbit beans
  domain/       Enums / event types
  entity/       JPA entities matching Flyway
  exception/    Domain errors + @ControllerAdvice
  metrics/      Micrometer counters / outbox gauge
  repository/   Spring Data + native inventory SQL
  security/     X-Customer-Id filter
  service/      Hold, confirm, cancel, expire, outbox, cache
  web/          Request-id MDC filter
```
