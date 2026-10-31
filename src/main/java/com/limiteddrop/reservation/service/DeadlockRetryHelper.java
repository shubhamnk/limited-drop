package com.limiteddrop.reservation.service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Component
public class DeadlockRetryHelper {

    private static final Logger log = LoggerFactory.getLogger(DeadlockRetryHelper.class);
    private static final int MAX_ATTEMPTS = 3;

    public <T> T execute(Supplier<T> action) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return action.get();
            } catch (RuntimeException ex) {
                if (!isRetryable(ex) || attempt >= MAX_ATTEMPTS) {
                    throw ex;
                }
                long backoffMs = (1L << (attempt - 1)) * 25L
                        + ThreadLocalRandom.current().nextLong(0, 40);
                log.warn("Retryable persistence conflict on attempt {}/{}; sleeping {}ms",
                        attempt, MAX_ATTEMPTS, backoffMs, ex);
                sleep(backoffMs);
            }
        }
    }

    public void execute(Runnable action) {
        execute(() -> {
            action.run();
            return null;
        });
    }

    private static boolean isRetryable(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof DeadlockLoserDataAccessException
                    || current instanceof CannotAcquireLockException
                    || current instanceof PessimisticLockingFailureException
                    || current instanceof ObjectOptimisticLockingFailureException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("deadlock") || lower.contains("lock wait timeout")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during deadlock retry backoff", ie);
        }
    }
}
