package com.limiteddrop.reservation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Hold hold = new Hold();
    private final Expiration expiration = new Expiration();
    private final Outbox outbox = new Outbox();
    private final Cache cache = new Cache();

    public Hold getHold() {
        return hold;
    }

    public Expiration getExpiration() {
        return expiration;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public Cache getCache() {
        return cache;
    }

    public static class Hold {
        private long durationSeconds = 300;
        private int maxQuantity = 10;

        public long getDurationSeconds() {
            return durationSeconds;
        }

        public void setDurationSeconds(long durationSeconds) {
            this.durationSeconds = durationSeconds;
        }

        public int getMaxQuantity() {
            return maxQuantity;
        }

        public void setMaxQuantity(int maxQuantity) {
            this.maxQuantity = maxQuantity;
        }
    }

    public static class Expiration {
        private int batchSize = 100;
        private long fixedDelayMs = 5000;

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getFixedDelayMs() {
            return fixedDelayMs;
        }

        public void setFixedDelayMs(long fixedDelayMs) {
            this.fixedDelayMs = fixedDelayMs;
        }
    }

    public static class Outbox {
        private int batchSize = 50;
        private long fixedDelayMs = 2000;

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getFixedDelayMs() {
            return fixedDelayMs;
        }

        public void setFixedDelayMs(long fixedDelayMs) {
            this.fixedDelayMs = fixedDelayMs;
        }
    }

    public static class Cache {
        private long dropTtlSeconds = 60;

        public long getDropTtlSeconds() {
            return dropTtlSeconds;
        }

        public void setDropTtlSeconds(long dropTtlSeconds) {
            this.dropTtlSeconds = dropTtlSeconds;
        }
    }
}
