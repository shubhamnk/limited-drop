# Limited Drop Reservation Service

## Technical Design Document (TDD)

**Version:** 1.0\
**Language:** Java 17\
**Framework:** Spring Boot 3.2+\
**Database:** MySQL 8\
**Cache:** Redis\
**Messaging:** RabbitMQ\
**Architecture:** Layered Monolith with Event-Driven Integration

------------------------------------------------------------------------

# 1. Introduction

## 1.1 Purpose

The Limited Drop Reservation Service manages scarce inventory that
becomes available for a limited period.

Examples include: - Concert tickets. - Limited-edition sneaker
releases. - Restaurant reservations. - Appointment slots. - Limited-run
products.

Customers can temporarily reserve inventory through a hold, confirm the
reservation, or cancel it before expiration.

The primary objective is to guarantee that the system never allocates
more units than the available inventory, even when thousands of
customers attempt to reserve the same drop simultaneously.

## 1.2 Functional Requirements

  -----------------------------------------------------------------------
  Requirement                         Description
  ----------------------------------- -----------------------------------
  View drops                          Customers can view available drops
                                      and inventory.

  Create hold                         Customers can temporarily reserve
                                      one or more units.

  Confirm hold                        Customers can convert an active
                                      hold into a confirmed reservation.

  Cancel hold                         Customers can release an active
                                      hold.

  Expire hold                         Unconfirmed holds automatically
                                      expire.

  Inventory management                Inventory must remain consistent
                                      across all operations.

  Idempotency                         Retried requests must not create
                                      duplicate reservations.
  -----------------------------------------------------------------------

## 1.3 Non-Functional Requirements

1.  Prevent inventory overselling under concurrent requests.
2.  Maintain transactional consistency.
3.  Support high request concurrency during drop openings.
4.  Recover gracefully from Redis and RabbitMQ failures.
5.  Provide predictable API responses and HTTP status codes.
6.  Support horizontal application scaling.
7.  Provide observability through structured logs and metrics.

------------------------------------------------------------------------

# 2. Architecture Overview

## 2.1 High-Level Architecture

The service follows a layered architecture. MySQL acts as the
authoritative data store, Redis handles caching, and RabbitMQ supports
asynchronous event processing.

``` mermaid
flowchart TB
    C[Client Applications]
    API[Spring Boot REST API]

    subgraph Application["Application Layer"]
        HS[Hold Service]
        CS[Confirmation Service]
        XS[Cancellation Service]
        ES[Expiration Scheduler]
    end

    DB[(MySQL)]
    R[(Redis)]
    O[Transactional Outbox]
    P[Outbox Publisher]
    MQ[RabbitMQ]
    CON[Event Consumers]

    C --> API
    API --> HS
    API --> CS
    API --> XS

    HS --> DB
    CS --> DB
    XS --> DB
    ES --> DB

    API -. Cache reads .-> R
    DB --> O
    O --> P
    P --> MQ
    MQ --> CON
```

## 2.2 Component Responsibilities

### REST API Layer

-   Exposes reservation APIs.
-   Validates incoming requests.
-   Handles authentication and authorization.
-   Maps application exceptions to HTTP responses.

### Service Layer

Contains the core business logic: - Inventory allocation. - Hold
lifecycle management. - Confirmation and cancellation. - Idempotency
handling. - Transaction management.

### Repository Layer

Responsible for: - Database operations. - Atomic inventory updates. -
Conditional state transitions. - Transactional queries.

### MySQL

Stores: - Drop information. - Inventory counters. - Customer holds. -
Idempotency records. - Transactional outbox events.

### Redis

Used for: - Drop metadata caching. - Frequently accessed drop
information. - Optional API rate limiting.

Redis is not responsible for inventory correctness.

### RabbitMQ

Used for asynchronous events: - Hold creation. - Hold confirmation. -
Hold cancellation. - Hold expiration.

------------------------------------------------------------------------

# 3. Core Design Decisions

## 3.1 MySQL as the Source of Truth

The most important requirement is preventing overselling.

Redis counters and application-level locks are not sufficient to
guarantee inventory correctness across multiple application instances.

Therefore, all inventory allocation and release operations are performed
against MySQL.

Inventory is represented using three counters:

-   `total_units`
-   `available_units`
-   `reserved_units`
-   `sold_units`

The following invariant must always hold:

``` text
total_units = available_units + reserved_units + sold_units
```

All inventory changes must happen inside database transactions.

## 3.2 Atomic Inventory Allocation

Consider a drop containing 100 units.

If 1,000 customers simultaneously request the last available unit, only
one request should succeed.

A normal read-modify-write operation can cause a race condition.

Instead, use an atomic conditional SQL update:

``` sql
UPDATE drops
SET available_units = available_units - :quantity,
    reserved_units = reserved_units + :quantity
WHERE id = :dropId
  AND status = 'LIVE'
  AND available_units >= :quantity
  AND starts_at <= CURRENT_TIMESTAMP(6)
  AND ends_at > CURRENT_TIMESTAMP(6);
```

Check the affected row count.

  Rows affected   Result
  --------------- ---------------------------------------------
  1               Inventory successfully reserved.
  0               Drop unavailable or insufficient inventory.

MySQL serializes conflicting updates on the same row. This makes the
database responsible for coordinating concurrent inventory allocation.

The inventory update and hold insertion are committed in the same
transaction.

## 3.3 Hold Lifecycle

A hold follows the following state transitions:

``` mermaid
stateDiagram-v2
    [*] --> ACTIVE: Create Hold
    ACTIVE --> CONFIRMED: Confirm Before Expiry
    ACTIVE --> CANCELLED: Cancel
    ACTIVE --> EXPIRED: Expiration
    CONFIRMED --> [*]
    CANCELLED --> [*]
    EXPIRED --> [*]
```

Terminal states cannot transition back to ACTIVE.

A hold can be confirmed only when: - Its status is ACTIVE. - Its
expiration timestamp is in the future. - The associated drop and
reservation are valid.

A hold cannot be confirmed after expiration, even if the expiration
scheduler has not yet processed it.

## 3.4 Hold Expiration

Each hold has an expiration timestamp.

Example:

``` text
Hold Created:  10:00:00
Hold Expires:  10:05:00
Current Time:  10:05:01
```

The hold is no longer eligible for confirmation.

A background scheduler periodically identifies expired holds and
releases their inventory.

However, the expiration scheduler is not the correctness mechanism.

Confirmation itself checks the expiration timestamp against database
time. This prevents a delayed scheduler from allowing an expired hold to
be confirmed.

------------------------------------------------------------------------

# 4. Database Design

## 4.1 Entity Relationship Overview

``` mermaid
erDiagram
    DROPS ||--o{ HOLDS : contains
    IDEMPOTENCY_KEYS }o--|| HOLDS : references
    OUTBOX_EVENTS }o--|| HOLDS : references

    DROPS {
        bigint id PK
        varchar name
        int total_units
        int available_units
        int reserved_units
        int sold_units
        varchar status
        datetime starts_at
        datetime ends_at
    }

    HOLDS {
        varchar id PK
        bigint drop_id FK
        varchar customer_id
        int quantity
        varchar status
        datetime expires_at
        datetime created_at
        datetime updated_at
    }

    IDEMPOTENCY_KEYS {
        bigint id PK
        varchar customer_id
        varchar operation
        varchar idempotency_key
        varchar request_hash
        json response_body
    }

    OUTBOX_EVENTS {
        bigint id PK
        varchar event_id
        varchar aggregate_id
        varchar event_type
        json payload
        varchar status
        datetime created_at
        datetime published_at
    }
```

## 4.2 Drops Table

Stores drop details and inventory.

  Column            Type           Description
  ----------------- -------------- -----------------------------
  id                BIGINT         Primary key
  name              VARCHAR(255)   Drop name
  description       TEXT           Drop description
  total_units       INT            Initial inventory
  available_units   INT            Available inventory
  reserved_units    INT            Inventory currently on hold
  sold_units        INT            Confirmed inventory
  status            VARCHAR(20)    SCHEDULED / LIVE / CLOSED
  starts_at         DATETIME(6)    Drop opening time
  ends_at           DATETIME(6)    Drop closing time
  created_at        DATETIME(6)    Creation timestamp
  updated_at        DATETIME(6)    Last update

Database constraints:

``` sql
CHECK (total_units >= 0),
CHECK (available_units >= 0),
CHECK (reserved_units >= 0),
CHECK (sold_units >= 0),
CHECK (total_units = available_units + reserved_units + sold_units)
```

## 4.3 Holds Table

Stores temporary reservations.

  Column        Type           Description
  ------------- -------------- ------------------------------------------
  id            VARCHAR(36)    UUID
  drop_id       BIGINT         Associated drop
  customer_id   VARCHAR(100)   Customer identifier
  quantity      INT            Reserved quantity
  status        VARCHAR(20)    ACTIVE / CONFIRMED / CANCELLED / EXPIRED
  expires_at    DATETIME(6)    Expiration timestamp
  created_at    DATETIME(6)    Creation timestamp
  updated_at    DATETIME(6)    Last update

Indexes:

``` sql
CREATE INDEX idx_hold_expiry
ON holds(status, expires_at);

CREATE INDEX idx_hold_customer
ON holds(customer_id, created_at);

CREATE INDEX idx_hold_drop_status
ON holds(drop_id, status);
```

## 4.4 Idempotency Table

Prevents duplicate processing when clients retry requests.

Unique constraint:

``` sql
UNIQUE(customer_id, operation, idempotency_key)
```

The request hash ensures that the same idempotency key cannot be reused
with a different request payload.

Store the original response so retries can return the same result.

## 4.5 Transactional Outbox Table

Stores events that must eventually be published to RabbitMQ.

Important fields: - Event ID. - Aggregate ID. - Event type. - Event
payload. - Publishing status. - Retry count. - Creation timestamp. -
Published timestamp.

The outbox record is inserted in the same transaction as the business
operation.

------------------------------------------------------------------------

# 5. API Design

Base path:

``` text
/api/v1
```

## 5.1 List Drops

**GET** `/api/v1/drops`

Returns available drops.

Example response:

``` json
{
  "drops": [
    {
      "id": 101,
      "name": "Limited Edition Sneakers",
      "availableUnits": 50,
      "status": "LIVE"
    }
  ]
}
```

## 5.2 Get Drop Details

**GET** `/api/v1/drops/{dropId}`

Returns drop information and available inventory.

## 5.3 Create Hold

**POST** `/api/v1/drops/{dropId}/holds`

Request:

``` json
{
  "customerId": "CUST-101",
  "quantity": 2
}
```

Headers:

``` text
Idempotency-Key: unique-client-request-id
```

Response: `201 Created`

``` json
{
  "holdId": "550e8400-e29b-41d4-a716-446655440000",
  "dropId": 101,
  "customerId": "CUST-101",
  "quantity": 2,
  "status": "ACTIVE",
  "expiresAt": "2026-10-31T07:00:00Z"
}
```

Business flow:

1.  Validate request.
2.  Validate idempotency key.
3.  Execute atomic inventory allocation.
4.  Create hold record.
5.  Insert HOLD_CREATED outbox event.
6.  Commit transaction.
7.  Return hold details.

If inventory is unavailable, return `409 Conflict`.

## 5.4 Confirm Hold

**POST** `/api/v1/holds/{holdId}/confirm`

Business flow:

1.  Retrieve and lock the hold.
2.  Verify the hold is ACTIVE.
3.  Verify it has not expired using database time.
4.  Transition the hold to CONFIRMED.
5.  Move inventory from reserved to sold.
6.  Insert HOLD_CONFIRMED outbox event.
7.  Commit transaction.

Inventory update:

``` sql
UPDATE drops
SET reserved_units = reserved_units - :quantity,
    sold_units = sold_units + :quantity
WHERE id = :dropId
  AND reserved_units >= :quantity;
```

If the hold is expired, transition it to EXPIRED and release inventory
in the same transaction. Return a conflict response after committing
that state change.

A repeated confirmation returns the original successful result for the
same idempotent operation.

## 5.5 Cancel Hold

**POST** `/api/v1/holds/{holdId}/cancel`

Business flow:

1.  Retrieve the hold.
2.  Verify its current state is ACTIVE.
3.  Transition it to CANCELLED.
4.  Release reserved inventory.
5.  Insert HOLD_CANCELLED outbox event.
6.  Commit transaction.

Inventory update:

``` sql
UPDATE drops
SET available_units = available_units + :quantity,
    reserved_units = reserved_units - :quantity
WHERE id = :dropId
  AND reserved_units >= :quantity;
```

Cancellation of a confirmed hold is not supported in the initial scope.

## 5.6 Get Hold Details

**GET** `/api/v1/holds/{holdId}`

Returns hold information and its current state.

------------------------------------------------------------------------

# 6. Concurrency and Transaction Management

This is the most critical part of the implementation.

## 6.1 Concurrent Hold Requests

Suppose:

``` text
Total Inventory: 100
Available:       1
Reserved:        0
Sold:            99
```

Two customers request one unit simultaneously.

  Customer     Request   Result
  ------------ --------- ----------
  Customer A   1 unit    SUCCESS
  Customer B   1 unit    REJECTED

After both transactions:

``` text
Available: 0
Reserved:  1
Sold:      99
```

The inventory constraint ensures that the second transaction cannot
decrement available inventory below zero.

## 6.2 Confirmation and Cancellation Race

Suppose a customer triggers confirmation and cancellation
simultaneously.

Both operations attempt to transition the same hold.

Use a conditional state transition:

``` sql
UPDATE holds
SET status = 'CONFIRMED',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE id = :holdId
  AND status = 'ACTIVE'
  AND expires_at > CURRENT_TIMESTAMP(6);
```

Only one operation can successfully transition the hold from ACTIVE.

The losing operation receives a conflict response.

The hold transition and inventory counter update must be in the same
transaction.

## 6.3 Transaction Boundaries

  -----------------------------------------------------------------------
  Operation                           Transactional operations
  ----------------------------------- -----------------------------------
  Create Hold                         Allocate inventory + insert hold +
                                      insert outbox event

  Confirm Hold                        Transition hold + update
                                      inventory + insert outbox event

  Cancel Hold                         Transition hold + update
                                      inventory + insert outbox event

  Expire Hold                         Transition hold + release
                                      inventory + insert outbox event
  -----------------------------------------------------------------------

No RabbitMQ network call should be made inside these business
transactions.

## 6.4 Deadlock Handling

Concurrent operations can occasionally cause database deadlocks.

Mitigation: - Keep transactions short. - Access resources in a
consistent order where possible. - Add bounded retries for transient
deadlock and lock-timeout errors. - Use exponential backoff with
jitter. - Never retry a business conflict such as insufficient
inventory.

------------------------------------------------------------------------

# 7. Redis Design

Redis is an optimization layer, not a consistency layer.

## 7.1 What to Cache

  Data                      Redis Usage
  ------------------------- -----------------------
  Drop metadata             Cache
  Drop descriptions         Cache
  Static drop information   Cache
  Inventory counters        Not authoritative
  Hold status               MySQL source of truth
  Confirmed reservations    MySQL source of truth

## 7.2 Cache Strategy

Use cache-aside.

``` text
Client Request
      |
      v
Check Redis
      |
      +---- Cache Hit ----> Return Data
      |
      +---- Cache Miss
                |
                v
             MySQL
                |
                v
           Update Redis
                |
                v
           Return Data
```

Suggested TTL:

``` text
Drop Metadata: 60 seconds
Drop Description: 5 minutes
```

Invalidate or refresh relevant cached data after successful inventory
updates.

Cache failures should not affect the reservation transaction.

------------------------------------------------------------------------

# 8. RabbitMQ Design

RabbitMQ handles asynchronous business events and downstream
integrations.

## 8.1 Event Types

  Event            Trigger
  ---------------- -----------------------------
  HOLD_CREATED     Hold successfully created
  HOLD_CONFIRMED   Hold successfully confirmed
  HOLD_CANCELLED   Hold successfully cancelled
  HOLD_EXPIRED     Hold automatically expired

## 8.2 Exchange and Queue Design

Exchange:

``` text
drop.events
```

Type:

``` text
topic
```

Routing keys:

``` text
drop.hold.created
drop.hold.confirmed
drop.hold.cancelled
drop.hold.expired
```

Queues:

``` text
drop.notification.queue
drop.analytics.queue
drop.audit.queue
```

Failed messages should be routed to appropriate dead-letter queues for
inspection and controlled redelivery.

## 8.3 Transactional Outbox Pattern

Directly publishing events after committing a database transaction can
result in lost events if the application crashes between the database
commit and RabbitMQ publish.

To avoid this:

1.  Save business changes in MySQL.
2.  Save the corresponding event in the outbox table.
3.  Commit both together.
4.  Background publisher reads pending outbox records.
5.  Publish to RabbitMQ using publisher confirms.
6.  Mark the event as published after broker confirmation.

The publisher must tolerate duplicate delivery.

For example, if RabbitMQ acknowledges an event but the application
crashes before marking the outbox record as published, the same event
may be published again.

Therefore, every event must have a unique event ID, and consumers must
implement idempotent processing.

Delivery guarantee: **At least once**, not exactly once.

------------------------------------------------------------------------

# 9. Expiration Scheduler

A scheduled worker processes expired holds.

Suggested configuration:

``` text
Scheduler Interval: 5 seconds
Batch Size: 100
```

Query:

``` sql
SELECT id
FROM holds
WHERE status = 'ACTIVE'
  AND expires_at <= CURRENT_TIMESTAMP(6)
ORDER BY expires_at
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

For each selected hold, within the same transaction:

1.  Transition ACTIVE to EXPIRED.
2.  Release the reserved inventory.
3.  Insert HOLD_EXPIRED outbox event.

`SKIP LOCKED` allows multiple scheduler instances to process separate
batches without unnecessarily waiting for each other.

The conditional status transition protects against duplicate expiration
processing.

The scheduler should process bounded batches so it does not hold
database locks for unnecessarily long periods.

------------------------------------------------------------------------

# 10. Error Handling and HTTP Semantics

  -----------------------------------------------------------------------
  HTTP Status                         Scenario
  ----------------------------------- -----------------------------------
  200 OK                              Successful read or repeated
                                      successful operation

  201 Created                         Hold created

  400 Bad Request                     Invalid request or quantity

  401 Unauthorized                    Missing or invalid authentication

  403 Forbidden                       Customer does not own the hold

  404 Not Found                       Drop or hold does not exist

  409 Conflict                        Inventory unavailable, expired
                                      hold, or invalid state transition

  422 Unprocessable Entity            Valid request that violates a
                                      business rule

  429 Too Many Requests               Rate limit exceeded

  500 Internal Server Error           Unexpected application failure

  503 Service Unavailable             Temporary dependency or service
                                      failure
  -----------------------------------------------------------------------

Use a consistent error response:

``` json
{
  "timestamp": "2026-10-31T06:45:00Z",
  "status": 409,
  "error": "CONFLICT",
  "message": "Insufficient inventory available",
  "path": "/api/v1/drops/101/holds"
}
```

Do not expose internal SQL errors, stack traces, or infrastructure
details to clients.

------------------------------------------------------------------------

# 11. Idempotency Strategy

Network failures can occur even after a transaction commits
successfully.

For example:

1.  Customer submits a hold request.
2.  Server creates the hold successfully.
3.  Response is lost due to a network failure.
4.  Customer retries the same request.

Without idempotency, duplicate holds may be created.

Require an `Idempotency-Key` for state-changing operations.

Store: - Customer ID. - Operation name. - Idempotency key. - Request
hash. - Original HTTP response. - Processing status.

Behavior:

  Scenario                        Expected Behavior
  ------------------------------- --------------------------------------
  First request                   Execute operation
  Same key, same payload          Return stored result
  Same key, different payload     Reject with 409
  Concurrent identical requests   Only one business operation executes

Idempotency records should have a documented retention period. Expired
keys must not be silently reused while the original operation is still
within its supported retry window.

------------------------------------------------------------------------

# 12. Security Considerations

1.  Customer identity must come from authenticated identity, not an
    untrusted request parameter.
2.  Customers must only access and modify their own holds.
3.  Validate quantity and maximum allowed quantity.
4.  Apply request rate limiting to reduce automated abuse.
5.  Configure CORS according to deployment requirements.
6.  Store database and RabbitMQ credentials through environment
    variables or secrets.
7.  Use HTTPS in production.
8.  Do not expose internal infrastructure information in error
    responses.

For the initial assignment, authentication can be implemented using
Spring Security with a simple development identity mechanism, clearly
separated from production authentication.

------------------------------------------------------------------------

# 13. Failure Handling

  -----------------------------------------------------------------------
  Failure                             Expected Behavior
  ----------------------------------- -----------------------------------
  Redis unavailable                   Continue using MySQL

  RabbitMQ unavailable                Persist outbox events and retry
                                      publishing

  Application crashes during hold     MySQL transaction rolls back unless
                                      already committed

  Application crashes after commit    Hold remains valid; outbox
                                      publisher eventually publishes
                                      event

  Scheduler crashes                   Another scheduler instance can
                                      process expired holds

  Duplicate scheduler execution       Conditional state transition
                                      prevents double release

  Client retries API                  Idempotency prevents duplicate
                                      operations

  MySQL unavailable                   Fail reservation requests; never
                                      allocate inventory from cache

  Deadlock                            Retry transaction within a bounded
                                      policy
  -----------------------------------------------------------------------

The application must fail safely when MySQL is unavailable.

It must never use cached inventory as a fallback mechanism for creating
reservations.

------------------------------------------------------------------------

# 14. Testing Strategy

Testing is focused on the highest-risk areas: concurrency, inventory
integrity, state transitions, and failure recovery.

## 14.1 Unit Tests

Use: - JUnit 5. - Mockito. - AssertJ.

Test cases:

  Test Case                                 Expected Result
  ----------------------------------------- --------------------------
  Create hold with sufficient inventory     Hold created
  Create hold with insufficient inventory   Conflict
  Confirm active hold                       Confirmed
  Confirm expired hold                      Rejected and expired
  Cancel active hold                        Cancelled
  Cancel already cancelled hold             No duplicate release
  Confirm cancelled hold                    Rejected
  Confirm already confirmed hold            Idempotent result
  Duplicate idempotency key                 Original result returned
  Different payload with same key           Rejected
  Outbox publishing failure                 Event remains pending

The default test suite must run without live MySQL, Redis, or RabbitMQ.

## 14.2 Concurrency Tests

### Scenario 1: Last Unit Reservation

``` text
Available Inventory = 1
Concurrent Requests = 100

Expected:
Successful Holds = 1
Failed Holds = 99
Available Inventory = 0
Reserved Inventory = 1
```

### Scenario 2: Multiple Units

``` text
Available Inventory = 100
Concurrent Requests = 1000
Quantity Per Request = 1

Expected:
Total Successful Holds <= 100
Available Inventory >= 0
```

### Scenario 3: Confirm vs Cancel

Execute confirmation and cancellation concurrently against the same
hold.

Expected: - Exactly one state transition succeeds. - Inventory is
updated exactly once. - Inventory conservation remains valid.

For database concurrency verification, add an optional integration-test
profile using Testcontainers MySQL. This profile is separate from the
infrastructure-free unit test suite.

## 14.3 Inventory Integrity Assertion

After every inventory operation, verify:

``` text
total_units =
available_units +
reserved_units +
sold_units
```

Also verify that all counters remain non-negative.

------------------------------------------------------------------------

# 15. Docker and Deployment

The application should support one-command local startup.

Command:

``` bash
docker-compose up --build
```

Services:

  Service       Purpose
  ------------- -------------------------
  application   Spring Boot application
  mysql         Persistent database
  redis         Cache
  rabbitmq      Messaging broker

Configuration should be provided through environment variables.

Example:

``` yaml
SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/drop_db
SPRING_DATASOURCE_USERNAME: ${MYSQL_USER}
SPRING_DATASOURCE_PASSWORD: ${MYSQL_PASSWORD}

SPRING_DATA_REDIS_HOST: redis
SPRING_DATA_REDIS_PORT: 6379

SPRING_RABBITMQ_HOST: rabbitmq
SPRING_RABBITMQ_PORT: 5672
SPRING_RABBITMQ_USERNAME: ${RABBITMQ_USER}
SPRING_RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD}
```

Use Docker health checks and service dependencies so the application
starts only after its required infrastructure is ready.

Flyway should manage database schema migrations.

Seed a few sample drops during initial database setup so the application
is immediately usable.

------------------------------------------------------------------------

# 16. Observability

Important metrics:

  Metric                        Purpose
  ----------------------------- ---------------------------
  hold_creation_total           Total hold requests
  hold_creation_success_total   Successful holds
  hold_creation_failure_total   Failed holds
  hold_confirmation_total       Confirmation attempts
  hold_expiration_total         Expired holds
  inventory_available           Available inventory
  inventory_reserved            Reserved inventory
  outbox_pending_events         Pending outbox records
  outbox_publish_failures       Event publishing failures
  reservation_latency           API response latency
  database_deadlocks_total      Database contention

Use Spring Boot Actuator and Micrometer.

Logs should include: - Request correlation ID. - Hold ID. - Drop ID. -
Customer ID where appropriate. - Event ID. - Operation status.

Avoid logging sensitive customer information.

------------------------------------------------------------------------

# 17. Scalability Considerations

## 17.1 Horizontal Scaling

The Spring Boot application is stateless and can run multiple instances
behind a load balancer.

MySQL provides concurrency control for inventory allocation.

Redis and RabbitMQ can be shared infrastructure services.

## 17.2 Database Bottleneck

A highly popular drop can cause contention on a single inventory row.

The initial atomic-update approach prioritizes correctness and
simplicity.

Potential future optimizations: - Partition inventory into independent
allocation buckets. - Use inventory allocation tokens. - Introduce
controlled admission queues. - Apply per-drop traffic shaping. -
Separate hot-drop traffic from normal traffic.

These approaches should only be introduced after measuring actual
database contention.

## 17.3 Read Scaling

Drop metadata can be served from Redis.

Frequently accessed public drop information can be cached independently
from reservation operations.

Reservation writes must always use MySQL.

------------------------------------------------------------------------

# 18. Trade-offs and Alternatives

  -------------------------------------------------------------------------
  Decision          Chosen Approach   Alternative       Reason
  ----------------- ----------------- ----------------- -------------------
  Inventory         MySQL atomic      Redis atomic      MySQL provides
  consistency       updates           counters          durable
                                                        transactional
                                                        consistency

  Application       Layered monolith  Microservices     Smaller operational
  architecture                                          footprint and
                                                        simpler
                                                        transactions

  Event publishing  Transactional     Direct RabbitMQ   Avoids losing
                    outbox            publishing        events after
                                                        database commit

  Expiration        Database-backed   Redis TTL         Durable state
                    scheduler                           transitions and
                                                        recovery

  Caching           Cache-aside       Cache-aside with  Simpler
                                      write-through     invalidation and
                                                        failure behavior

  Idempotency       MySQL-backed keys Redis-backed keys Durable
                                                        duplicate-request
                                                        protection

  Concurrency       Conditional SQL   Distributed locks No additional
                    updates                             distributed lock
                                                        dependency
  -------------------------------------------------------------------------

------------------------------------------------------------------------

# 19. Future Enhancements

With more development time, the following enhancements would be
considered:

1.  **Virtual Waiting Room:** Control incoming traffic during highly
    popular drop openings.
2.  **Payment Integration:** Introduce payment authorization and capture
    workflows with payment idempotency and compensation.
3.  **Advanced Inventory Partitioning:** Reduce contention on
    exceptionally popular drops.
4.  **Customer Purchase Limits:** Enforce per-customer allocation
    limits.
5.  **Dynamic Hold Duration:** Support configurable reservation expiry
    based on drop configuration.
6.  **Advanced Monitoring:** Add distributed tracing and per-drop
    contention dashboards.
7.  **Fraud Prevention:** Detect automated reservation abuse and
    suspicious customer behavior.
8.  **Multi-region Support:** Explore regional inventory allocation with
    explicitly defined consistency guarantees.

------------------------------------------------------------------------

# 20. Conclusion

The Limited Drop Reservation Service is designed around one fundamental
principle:

**Inventory correctness must never depend on cache availability,
scheduler execution, or message delivery.**

MySQL provides transactional inventory allocation and state management.
Redis improves read performance, while RabbitMQ enables asynchronous
integrations through a transactional outbox.

The design prioritizes: - No inventory overselling. - Atomic reservation
lifecycle transitions. - Idempotent API operations. - Reliable
asynchronous event publishing. - Recovery from infrastructure
failures. - Clear separation of business logic and infrastructure.

The initial implementation deliberately favors correctness and
operational simplicity over premature distributed-system complexity.
Further optimizations can be introduced as actual traffic patterns and
performance requirements become clear.
