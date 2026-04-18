package com.ogautam.kinkeeper.service;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.ogautam.kinkeeper.config.CacheConfig;
import com.ogautam.kinkeeper.crypto.CryptoService;
import com.ogautam.kinkeeper.model.UserProfile;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.google.cloud.firestore.FieldValue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Slf4j
@Service
public class UserService {

    private static final String USERS_COLLECTION = "users";

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_VIEWER = "viewer";

    private final Firestore firestore;
    private final CryptoService cryptoService;
    private final InviteService inviteService;

    public UserService(Firestore firestore, CryptoService cryptoService, InviteService inviteService) {
        this.firestore = firestore;
        this.cryptoService = cryptoService;
        this.inviteService = inviteService;
    }

    @Caching(evict = {
            @CacheEvict(value = CacheConfig.CACHE_USER_PROFILE, key = "#principal.uid()"),
            @CacheEvict(value = CacheConfig.CACHE_FAMILY_BY_USER, key = "#principal.uid()")
    })
    public UserProfile createOrUpdateUser(FirebaseUserPrincipal principal) throws ExecutionException, InterruptedException {
        // Single read at entry; all downstream logic uses this snapshot + an accumulated
        // updates map, collapsing what used to be 4 reads of the same doc into 1 read + 1 write.
        DocumentSnapshot doc = firestore.collection(USERS_COLLECTION)
                .document(principal.uid()).get().get();

        Map<String, Object> updates = new HashMap<>();
        String familyId;
        String role;

        if (doc.exists()) {
            updates.put("displayName", principal.name() != null ? principal.name() : "");
            updates.put("photoUrl", principal.picture() != null ? principal.picture() : "");
            familyId = stringOrNull(doc.get("familyId"));
            role = stringOrNull(doc.get("role"));
        } else {
            UserProfile profile = UserProfile.builder()
                    .uid(principal.uid())
                    .email(principal.email())
                    .displayName(principal.name())
                    .photoUrl(principal.picture())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            firestore.collection(USERS_COLLECTION).document(principal.uid())
                    .set(profile).get();
            log.info("Created new user profile for {}", principal.email());
            familyId = null;
            role = null;
        }

        // Auto-accept a pending invite if the user has no family yet.
        if (familyId == null) {
            var invite = inviteService.findPendingForEmail(principal.email());
            if (invite != null) {
                familyId = invite.getFamilyId();
                role = invite.getRole() != null ? invite.getRole() : ROLE_VIEWER;
                updates.put("familyId", familyId);
                updates.put("role", role);
                inviteService.markAccepted(invite.getId());
                log.info("Auto-accepted invite {} for {} into family {}", invite.getId(), principal.email(), familyId);
            }
        }

        // Role backfill for users who created families before role was persisted.
        if (familyId != null && role == null) {
            DocumentSnapshot family = firestore.collection("families").document(familyId).get().get();
            if (family.exists()) {
                Object adminUid = family.get("adminUid");
                role = principal.uid().equals(adminUid) ? ROLE_ADMIN : ROLE_VIEWER;
                updates.put("role", role);
                log.info("Backfilled role={} for user {}", role, principal.uid());
            }
        }

        if (doc.exists() && !updates.isEmpty()) {
            updates.put("updatedAt", Instant.now());
            firestore.collection(USERS_COLLECTION).document(principal.uid()).update(updates).get();
            log.info("Updated user profile for {}", principal.email());
        }

        return getUserByUid(principal.uid());
    }

    private static String stringOrNull(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isBlank() ? null : s;
    }

    public String getRole(String uid) throws ExecutionException, InterruptedException {
        DocumentSnapshot doc = firestore.collection(USERS_COLLECTION).document(uid).get().get();
        if (!doc.exists()) return null;
        Object role = doc.get("role");
        return role != null ? role.toString() : null;
    }

    public void requireAdmin(String uid) throws ExecutionException, InterruptedException {
        String role = getRole(uid);
        if (!ROLE_ADMIN.equals(role)) {
            throw new IllegalArgumentException("Admin role required");
        }
    }

    @Cacheable(value = CacheConfig.CACHE_USER_PROFILE, key = "#uid", unless = "#result == null")
    public UserProfile getUserByUid(String uid) throws ExecutionException, InterruptedException {
        DocumentSnapshot doc = firestore.collection(USERS_COLLECTION)
                .document(uid).get().get();
        if (!doc.exists()) {
            return null;
        }
        return doc.toObject(UserProfile.class);
    }

    public boolean hasApiKey(String uid) throws ExecutionException, InterruptedException {
        DocumentSnapshot doc = firestore.collection(USERS_COLLECTION).document(uid).get().get();
        if (!doc.exists()) return false;
        Object val = doc.get("claudeApiKeyEncrypted");
        return val != null && !val.toString().isBlank();
    }

    @CacheEvict(value = CacheConfig.CACHE_USER_PROFILE, key = "#uid")
    public void saveApiKey(String uid, String plaintext) throws ExecutionException, InterruptedException {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("API key is required");
        }
        String encrypted = cryptoService.encrypt(plaintext.trim());
        firestore.collection(USERS_COLLECTION).document(uid)
                .update(Map.of(
                        "claudeApiKeyEncrypted", encrypted,
                        "updatedAt", Instant.now()
                )).get();
        log.info("Saved Claude API key for user {}", uid);
    }

    public String getApiKey(String uid) throws ExecutionException, InterruptedException {
        DocumentSnapshot doc = firestore.collection(USERS_COLLECTION).document(uid).get().get();
        if (!doc.exists()) return null;
        Object encrypted = doc.get("claudeApiKeyEncrypted");
        if (encrypted == null || encrypted.toString().isBlank()) return null;
        return cryptoService.decrypt(encrypted.toString());
    }

    @CacheEvict(value = CacheConfig.CACHE_USER_PROFILE, key = "#uid")
    public void deleteApiKey(String uid) throws ExecutionException, InterruptedException {
        Map<String, Object> updates = new HashMap<>();
        updates.put("claudeApiKeyEncrypted", FieldValue.delete());
        updates.put("updatedAt", Instant.now());
        firestore.collection(USERS_COLLECTION).document(uid).update(updates).get();
        log.info("Deleted Claude API key for user {}", uid);
    }

    public boolean hasDriveRefreshToken(String uid) throws ExecutionException, InterruptedException {
        DocumentSnapshot doc = firestore.collection(USERS_COLLECTION).document(uid).get().get();
        if (!doc.exists()) return false;
        Object val = doc.get("driveRefreshTokenEncrypted");
        return val != null && !val.toString().isBlank();
    }

    @CacheEvict(value = CacheConfig.CACHE_USER_PROFILE, key = "#uid")
    public void saveDriveRefreshToken(String uid, String plaintext) throws ExecutionException, InterruptedException {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Refresh token is required");
        }
        String encrypted = cryptoService.encrypt(plaintext);
        firestore.collection(USERS_COLLECTION).document(uid)
                .update(Map.of(
                        "driveRefreshTokenEncrypted", encrypted,
                        "updatedAt", Instant.now()
                )).get();
        log.info("Saved Drive refresh token for user {}", uid);
    }

    public String getDriveRefreshToken(String uid) throws ExecutionException, InterruptedException {
        DocumentSnapshot doc = firestore.collection(USERS_COLLECTION).document(uid).get().get();
        if (!doc.exists()) return null;
        Object encrypted = doc.get("driveRefreshTokenEncrypted");
        if (encrypted == null || encrypted.toString().isBlank()) return null;
        return cryptoService.decrypt(encrypted.toString());
    }

    public int getChatRetentionDays(String uid) throws ExecutionException, InterruptedException {
        DocumentSnapshot doc = firestore.collection(USERS_COLLECTION).document(uid).get().get();
        if (!doc.exists()) return ChatSessionService.DEFAULT_RETENTION_DAYS;
        Object val = doc.get("chatRetentionDays");
        if (val == null) return ChatSessionService.DEFAULT_RETENTION_DAYS;
        try {
            return Math.toIntExact(((Number) val).longValue());
        } catch (Exception e) {
            return ChatSessionService.DEFAULT_RETENTION_DAYS;
        }
    }

    @CacheEvict(value = CacheConfig.CACHE_USER_PROFILE, key = "#uid")
    public void saveChatRetentionDays(String uid, int days) throws ExecutionException, InterruptedException {
        if (days < ChatSessionService.MIN_RETENTION_DAYS || days > ChatSessionService.MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException("chatRetentionDays must be between "
                    + ChatSessionService.MIN_RETENTION_DAYS + " and " + ChatSessionService.MAX_RETENTION_DAYS);
        }
        firestore.collection(USERS_COLLECTION).document(uid)
                .update(Map.of(
                        "chatRetentionDays", days,
                        "updatedAt", Instant.now()
                )).get();
        log.info("Saved chatRetentionDays={} for user {}", days, uid);
    }

    @CacheEvict(value = CacheConfig.CACHE_USER_PROFILE, key = "#uid")
    public void deleteDriveRefreshToken(String uid) throws ExecutionException, InterruptedException {
        Map<String, Object> updates = new HashMap<>();
        updates.put("driveRefreshTokenEncrypted", FieldValue.delete());
        updates.put("updatedAt", Instant.now());
        firestore.collection(USERS_COLLECTION).document(uid).update(updates).get();
        log.info("Deleted Drive refresh token for user {}", uid);
    }
}
