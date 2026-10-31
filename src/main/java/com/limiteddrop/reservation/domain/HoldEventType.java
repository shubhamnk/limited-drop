package com.limiteddrop.reservation.domain;

public enum HoldEventType {
    HOLD_CREATED("drop.hold.created"),
    HOLD_CONFIRMED("drop.hold.confirmed"),
    HOLD_CANCELLED("drop.hold.cancelled"),
    HOLD_EXPIRED("drop.hold.expired");

    private final String routingKey;

    HoldEventType(String routingKey) {
        this.routingKey = routingKey;
    }

    public String routingKey() {
        return routingKey;
    }
}
