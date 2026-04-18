package com.ogautam.kinkeeper.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Short-lived staging for files attached in chat. Stores the bytes (base64) +
 * metadata in Redis keyed by a UUID. Entries expire after 1 hour so abandoned
 * attachments are cleaned up automatically.
 */
@Slf4j
@Service
public class AttachmentService {

    private static final Duration TTL = Duration.ofHours(1);
    private static final String PREFIX = "attachment:";

    private final StringRedisTemplate redis;

    public AttachmentService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Staged stage(String ownerUid, String fileName, String mimeType, byte[] bytes) {
        String id = UUID.randomUUID().toString();
        String key = PREFIX + id;
        Map<String, String> payload = Map.of(
                "ownerUid", ownerUid,
                "fileName", fileName == null ? "" : fileName,
                "mimeType", mimeType == null ? "application/octet-stream" : mimeType,
                "size", String.valueOf(bytes.length),
                "contentBase64", Base64.getEncoder().encodeToString(bytes)
        );
        redis.opsForHash().putAll(key, payload);
        redis.expire(key, TTL);
        log.info("Staged attachment {} ({} bytes, {}) for user {}", id, bytes.length, mimeType, ownerUid);
        return new Staged(id, fileName, mimeType, bytes.length);
    }

    public Loaded load(String attachmentId, String expectedOwnerUid) {
        String key = PREFIX + attachmentId;
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Attachment not found or expired");
        }
        String owner = String.valueOf(entries.get("ownerUid"));
        if (!expectedOwnerUid.equals(owner)) {
            throw new IllegalArgumentException("Attachment not accessible");
        }
        byte[] bytes = Base64.getDecoder().decode(String.valueOf(entries.get("contentBase64")));
        return new Loaded(
                attachmentId,
                String.valueOf(entries.get("fileName")),
                String.valueOf(entries.get("mimeType")),
                bytes.length,
                bytes);
    }

    public void discard(String attachmentId) {
        redis.delete(PREFIX + attachmentId);
    }

    public record Staged(String id, String fileName, String mimeType, long size) {}
    public record Loaded(String id, String fileName, String mimeType, long size, byte[] bytes) {}
}
