package com.ogautam.kinkeeper.service;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.ogautam.kinkeeper.crypto.CryptoService;
import com.ogautam.kinkeeper.model.UserProfile;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.google.cloud.firestore.FieldValue;
import lombok.extern.slf4j.Slf4j;
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

    public UserProfile createOrUpdateUser(FirebaseUserPrincipal principal) throws ExecutionException, InterruptedException {
        DocumentSnapshot doc = firestore.collection(USERS_COLLECTION)
                .document(principal.uid()).get().get();

        if (doc.exists()) {
            firestore.collection(USERS_COLLECTION).document(principal.uid())
                    .update(Map.of(
                            "displayName", principal.name() != null ? principal.name() : "",
                            "photoUrl", principal.picture() != null ? principal.picture() : "",
                            "updatedAt", Instant.now()
                    )).get();
            log.info("Updated user profile for {}", principal.email());
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
        }

        maybeAutoAcceptInvite(principal.uid(), principal.email());
        ensureRoleConsistent(principal.uid());
        return getUserByUid(principal.uid());
    }

    private void ensureRoleConsistent(String uid) throws ExecutionException, InterruptedException {
        DocumentSnapshot user = firestore.collection(USERS_COLLECTION).document(uid).get().get();
        if (!user.exists()) return;
        Object familyId = user.get("familyId");
        Object role = user.get("role");
        if (familyId == null || familyId.toString().isBlank()) return;
        if (role != null && !role.toString().isBlank()) return;

        // reason: slice-4 shipped after some users had already created families; their user
        // doc has familyId but no role. Derive from family.adminUid so the admin UI renders.
        DocumentSnapshot family = firestore.collection("families")
                .document(familyId.toString()).get().get();
        if (!family.exists()) return;
        Object adminUid = family.get("adminUid");
        String derivedRole = uid.equals(adminUid) ? ROLE_ADMIN : ROLE_VIEWER;
        firestore.collection(USERS_COLLECTION).document(uid).update(Map.of(
                "role", derivedRole,
                "updatedAt", Instant.now()
        )).get();
        log.info("Backfilled role={} for user {}", derivedRole, uid);
    }

    private void maybeAutoAcceptInvite(String uid, String email)
            throws ExecutionException, InterruptedException {
        DocumentSnapshot user = firestore.collection(USERS_COLLECTION).document(uid).get().get();
        if (!user.exists()) return;
        Object existingFamilyId = user.get("familyId");
        if (existingFamilyId != null && !existingFamilyId.toString().isBlank()) return;

        var invite = inviteService.findPendingForEmail(email);
        if (invite == null) return;

        firestore.collection(USERS_COLLECTION).document(uid)
                .update(Map.of(
                        "familyId", invite.getFamilyId(),
                        "role", invite.getRole() != null ? invite.getRole() : ROLE_VIEWER,
                        "updatedAt", Instant.now()
                )).get();
        inviteService.markAccepted(invite.getId());
        log.info("Auto-accepted invite {} for {} into family {}", invite.getId(), email, invite.getFamilyId());
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

    public void deleteDriveRefreshToken(String uid) throws ExecutionException, InterruptedException {
        Map<String, Object> updates = new HashMap<>();
        updates.put("driveRefreshTokenEncrypted", FieldValue.delete());
        updates.put("updatedAt", Instant.now());
        firestore.collection(USERS_COLLECTION).document(uid).update(updates).get();
        log.info("Deleted Drive refresh token for user {}", uid);
    }
}
