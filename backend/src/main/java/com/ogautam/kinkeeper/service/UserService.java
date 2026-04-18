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

    private final Firestore firestore;
    private final CryptoService cryptoService;

    public UserService(Firestore firestore, CryptoService cryptoService) {
        this.firestore = firestore;
        this.cryptoService = cryptoService;
    }

    public UserProfile createOrUpdateUser(FirebaseUserPrincipal principal) throws ExecutionException, InterruptedException {
        DocumentSnapshot doc = firestore.collection(USERS_COLLECTION)
                .document(principal.uid()).get().get();

        if (doc.exists()) {
            firestore.collection(USERS_COLLECTION).document(principal.uid())
                    .update(Map.of(
                            "displayName", principal.name() != null ? principal.name() : "",
                            "photoUrl", principal.picture() != null ? principal.picture() : "",
                            "updatedAt", Instant.now().toString()
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

        return getUserByUid(principal.uid());
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
                        "updatedAt", Instant.now().toString()
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
        updates.put("updatedAt", Instant.now().toString());
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
                        "updatedAt", Instant.now().toString()
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
        updates.put("updatedAt", Instant.now().toString());
        firestore.collection(USERS_COLLECTION).document(uid).update(updates).get();
        log.info("Deleted Drive refresh token for user {}", uid);
    }
}
