package com.ogautam.kinkeeper.service;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.ogautam.kinkeeper.model.UserProfile;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Slf4j
@Service
public class UserService {

    private static final String USERS_COLLECTION = "users";

    private final Firestore firestore;

    public UserService(Firestore firestore) {
        this.firestore = firestore;
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
}
