package com.limiteddrop.reservation.security;

import com.limiteddrop.reservation.exception.ForbiddenException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CustomerContext {

    private CustomerContext() {
    }

    /**
     * Customer identity ALWAYS comes from the SecurityContext (design §12).
     * Never trust a body {@code customerId}.
     */
    public static String requireCustomerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ForbiddenException("UNAUTHORIZED", "Customer identity is required");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof CustomerPrincipal customerPrincipal) {
            return customerPrincipal.getCustomerId();
        }
        String name = authentication.getName();
        if (name == null || name.isBlank() || "anonymousUser".equals(name)) {
            throw new ForbiddenException("UNAUTHORIZED", "Customer identity is required");
        }
        return name;
    }
}
