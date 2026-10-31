CREATE TABLE drops (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    name              VARCHAR(255) NOT NULL,
    description       TEXT,
    total_units       INT          NOT NULL,
    available_units   INT          NOT NULL,
    reserved_units    INT          NOT NULL,
    sold_units        INT          NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    starts_at         DATETIME(6)  NOT NULL,
    ends_at           DATETIME(6)  NOT NULL,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_drops_total_units_nonneg CHECK (total_units >= 0),
    CONSTRAINT chk_drops_available_units_nonneg CHECK (available_units >= 0),
    CONSTRAINT chk_drops_reserved_units_nonneg CHECK (reserved_units >= 0),
    CONSTRAINT chk_drops_sold_units_nonneg CHECK (sold_units >= 0),
    CONSTRAINT chk_drops_units_balance CHECK (total_units = available_units + reserved_units + sold_units)
);

CREATE TABLE holds (
    id           VARCHAR(36)  NOT NULL,
    drop_id      BIGINT       NOT NULL,
    customer_id  VARCHAR(100) NOT NULL,
    quantity     INT          NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    expires_at   DATETIME(6)  NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_holds_drop FOREIGN KEY (drop_id) REFERENCES drops (id),
    CONSTRAINT chk_holds_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX idx_hold_expiry ON holds (status, expires_at);
CREATE INDEX idx_hold_customer ON holds (customer_id, created_at);
CREATE INDEX idx_hold_drop_status ON holds (drop_id, status);

CREATE TABLE idempotency_keys (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    customer_id      VARCHAR(100) NOT NULL,
    operation        VARCHAR(100) NOT NULL,
    idempotency_key  VARCHAR(100) NOT NULL,
    request_hash     VARCHAR(64)  NOT NULL,
    response_body    JSON,
    http_status      INT          NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_customer_op_key UNIQUE (customer_id, operation, idempotency_key)
);

CREATE TABLE outbox_events (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    event_id      VARCHAR(36)  NOT NULL,
    aggregate_id  VARCHAR(100) NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSON         NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    retry_count   INT          NOT NULL DEFAULT 0,
    created_at    DATETIME(6)  NOT NULL,
    published_at  DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_outbox_event_id UNIQUE (event_id)
);
