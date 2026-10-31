package com.limiteddrop.reservation.domain;

/**
 * Canonical idempotency operation names stored in {@code idempotency_keys.operation}.
 */
public final class IdempotencyOperation {

    public static final String CREATE_HOLD = "CREATE_HOLD";
    public static final String CONFIRM_HOLD = "CONFIRM_HOLD";
    public static final String CANCEL_HOLD = "CANCEL_HOLD";

    private IdempotencyOperation() {
    }
}
