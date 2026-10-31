# Limited Drop Reservation Service — Technical Design Document

**Version:** 1.0  
**Language:** Java 17  
**Framework:** Spring Boot 3.2+ (implemented on 3.3.5)  
**Database:** MySQL 8  
**Cache:** Redis  
**Messaging:** RabbitMQ  
**Architecture:** Layered monolith with transactional outbox / event-driven integration

This document is the product TDD. The implementation in this repository follows it.

## Purpose

Manage scarce inventory available for a limited period (tickets, drops, reservations).
Customers create temporary holds, then confirm or cancel before expiry. The system must
never allocate more units than available under high concurrency.

## Functional requirements

- View drops and inventory
- Create / confirm / cancel holds
- Expire unconfirmed holds
- Keep inventory consistent
- Idempotent mutating APIs

## Non-functional requirements

- No overselling under concurrency
- Transactional consistency (MySQL is source of truth)
- Graceful Redis / RabbitMQ failure handling
- Horizontal app scaling
- Structured logs + metrics

## Core decisions

1. **MySQL owns inventory.** Redis is cache-aside for drop metadata only. Never allocate from cache.
2. **Atomic conditional SQL** for allocate / confirm / release, checking affected row count.
3. **Invariant:** `total_units = available_units + reserved_units + sold_units`
4. **Hold lifecycle:** `ACTIVE → CONFIRMED | CANCELLED | EXPIRED` (terminal)
5. **Expiration correctness** is enforced on confirm/cancel via DB timestamps, not only the scheduler.
6. **Transactional outbox** for `HOLD_*` events; publisher is outside business transactions.
7. **Idempotency-Key** for create/confirm/cancel with request-hash conflict detection.
8. **Customer identity** from authenticated context (`X-Customer-Id` in development), not request body.

## API (`/api/v1`)

| Method | Path | Notes |
|--------|------|-------|
| GET | `/drops` | List drops |
| GET | `/drops/{dropId}` | Drop detail (Redis cache-aside) |
| POST | `/drops/{dropId}/holds` | Create hold; `Idempotency-Key`; 201 / 409 |
| GET | `/holds/{holdId}` | Hold detail (owner only) |
| POST | `/holds/{holdId}/confirm` | Confirm; expire+release then 409 if expired |
| POST | `/holds/{holdId}/cancel` | Cancel active hold |

## Messaging

- Exchange: `drop.events` (topic)
- Routing keys: `drop.hold.created|confirmed|cancelled|expired`
- Queues: `drop.notification.queue`, `drop.analytics.queue`, `drop.audit.queue` (+ DLQ)

## Local run

```bash
docker-compose up --build
mvn test   # no live MySQL/Redis/RabbitMQ required
```

## Implementation map

| Concern | Package / path |
|---------|----------------|
| REST | `api/` |
| Domain enums | `domain/` |
| JPA + Flyway | `entity/`, `db/migration/` |
| Inventory SQL | `repository/DropRepository` |
| Hold lifecycle | `service/HoldService`, `ConfirmationService`, `CancellationService`, `ExpirationService` |
| Outbox | `service/OutboxService`, `OutboxPublisher` |
| Security | `security/` |
| Docker | `docker-compose.yml`, `Dockerfile` |
