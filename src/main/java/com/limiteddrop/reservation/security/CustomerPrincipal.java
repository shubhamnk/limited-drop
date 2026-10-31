package com.limiteddrop.reservation.security;

import java.util.Objects;

public final class CustomerPrincipal {

    private final String customerId;

    public CustomerPrincipal(String customerId) {
        this.customerId = Objects.requireNonNull(customerId, "customerId");
    }

    public String getCustomerId() {
        return customerId;
    }

    @Override
    public String toString() {
        return customerId;
    }
}
