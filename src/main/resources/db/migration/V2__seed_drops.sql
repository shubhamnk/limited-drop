INSERT INTO drops (
    name,
    description,
    total_units,
    available_units,
    reserved_units,
    sold_units,
    status,
    starts_at,
    ends_at,
    created_at,
    updated_at
) VALUES
(
    'Midnight Sneaker Drop',
    'Limited collab sneakers — LIVE with full inventory.',
    100,
    100,
    0,
    0,
    'LIVE',
    TIMESTAMP '2026-01-01 00:00:00',
    TIMESTAMP '2026-12-31 23:59:59',
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6)
),
(
    'Autumn Capsule Jacket',
    'Seasonal jacket release — SCHEDULED for a future window.',
    50,
    50,
    0,
    0,
    'SCHEDULED',
    TIMESTAMP '2027-03-01 12:00:00',
    TIMESTAMP '2027-03-02 12:00:00',
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6)
),
(
    'Last-Chance Tee',
    'Nearly sold out tee — LIVE with low remaining inventory.',
    20,
    2,
    5,
    13,
    'LIVE',
    TIMESTAMP '2026-01-01 00:00:00',
    TIMESTAMP '2026-12-31 23:59:59',
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6)
);
