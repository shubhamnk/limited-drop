package com.limiteddrop.reservation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limiteddrop.reservation.api.dto.DropResponse;
import com.limiteddrop.reservation.config.AppProperties;
import com.limiteddrop.reservation.entity.DropEntity;
import com.limiteddrop.reservation.exception.NotFoundException;
import com.limiteddrop.reservation.repository.DropRepository;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DropQueryService {

    private static final Logger log = LoggerFactory.getLogger(DropQueryService.class);
    private static final String CACHE_KEY_PREFIX = "drop:";

    private final DropRepository dropRepository;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final StringRedisTemplate redisTemplate;

    public DropQueryService(
            DropRepository dropRepository,
            ObjectMapper objectMapper,
            AppProperties appProperties,
            @Autowired(required = false) StringRedisTemplate redisTemplate
    ) {
        this.dropRepository = dropRepository;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.redisTemplate = redisTemplate;
    }

    @Transactional(readOnly = true)
    public List<DropResponse> listDrops() {
        return dropRepository.findAll().stream().map(DropResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public DropResponse getDrop(Long dropId) {
        DropResponse cached = readCache(dropId);
        if (cached != null) {
            return cached;
        }
        DropEntity entity = dropRepository.findById(dropId)
                .orElseThrow(() -> new NotFoundException("DROP_NOT_FOUND", "Drop not found: " + dropId));
        DropResponse response = DropResponse.from(entity);
        writeCache(response);
        return response;
    }

    public void invalidateDrop(Long dropId) {
        if (redisTemplate == null || dropId == null) {
            return;
        }
        try {
            redisTemplate.delete(CACHE_KEY_PREFIX + dropId);
        } catch (Exception ex) {
            log.warn("Redis invalidate failed for drop {} (fail-open): {}", dropId, ex.getMessage());
        }
    }

    private DropResponse readCache(Long dropId) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(CACHE_KEY_PREFIX + dropId);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, DropResponse.class);
        } catch (Exception ex) {
            log.warn("Redis read failed for drop {} (fail-open): {}", dropId, ex.getMessage());
            return null;
        }
    }

    private void writeCache(DropResponse response) {
        if (redisTemplate == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(
                    CACHE_KEY_PREFIX + response.id(),
                    json,
                    Duration.ofSeconds(appProperties.getCache().getDropTtlSeconds())
            );
        } catch (JsonProcessingException ex) {
            log.warn("Unable to serialize drop {} for cache", response.id());
        } catch (Exception ex) {
            log.warn("Redis write failed for drop {} (fail-open): {}", response.id(), ex.getMessage());
        }
    }
}
