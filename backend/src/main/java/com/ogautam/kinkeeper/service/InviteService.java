package com.ogautam.kinkeeper.service;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.ogautam.kinkeeper.model.Invite;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Slf4j
@Service
public class InviteService {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ACCEPTED = "accepted";
    public static final String STATUS_CANCELED = "canceled";

    private static final String INVITES_COLLECTION = "invites";

    private final Firestore firestore;

    public InviteService(Firestore firestore) {
        this.firestore = firestore;
    }

    public Invite createInvite(String familyId, String email, String role, String invitedByUid)
            throws ExecutionException, InterruptedException {
        String normalizedEmail = email.trim().toLowerCase();
        DocumentReference ref = firestore.collection(INVITES_COLLECTION).document();
        Invite invite = Invite.builder()
                .id(ref.getId())
                .familyId(familyId)
                .email(normalizedEmail)
                .role(role != null ? role : "viewer")
                .invitedBy(invitedByUid)
                .createdAt(Instant.now())
                .status(STATUS_PENDING)
                .build();
        ref.set(invite).get();
        log.info("Created invite {} for email {} to family {}", ref.getId(), normalizedEmail, familyId);
        return invite;
    }

    public List<Invite> listPending(String familyId) throws ExecutionException, InterruptedException {
        List<QueryDocumentSnapshot> docs = firestore.collection(INVITES_COLLECTION)
                .whereEqualTo("familyId", familyId)
                .whereEqualTo("status", STATUS_PENDING)
                .get().get()
                .getDocuments();
        List<Invite> out = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot d : docs) {
            out.add(d.toObject(Invite.class));
        }
        return out;
    }

    public void cancel(String inviteId, String expectedFamilyId)
            throws ExecutionException, InterruptedException {
        DocumentSnapshot snap = firestore.collection(INVITES_COLLECTION).document(inviteId).get().get();
        if (!snap.exists()) {
            throw new IllegalArgumentException("Invite not found");
        }
        Invite invite = snap.toObject(Invite.class);
        if (invite == null || !expectedFamilyId.equals(invite.getFamilyId())) {
            throw new IllegalArgumentException("Invite not found");
        }
        firestore.collection(INVITES_COLLECTION).document(inviteId)
                .update("status", STATUS_CANCELED).get();
        log.info("Canceled invite {}", inviteId);
    }

    public Invite findPendingForEmail(String email) throws ExecutionException, InterruptedException {
        if (email == null || email.isBlank()) return null;
        String normalizedEmail = email.trim().toLowerCase();
        List<QueryDocumentSnapshot> docs = firestore.collection(INVITES_COLLECTION)
                .whereEqualTo("email", normalizedEmail)
                .whereEqualTo("status", STATUS_PENDING)
                .limit(1)
                .get().get()
                .getDocuments();
        if (docs.isEmpty()) return null;
        return docs.get(0).toObject(Invite.class);
    }

    public void markAccepted(String inviteId) throws ExecutionException, InterruptedException {
        firestore.collection(INVITES_COLLECTION).document(inviteId)
                .update(Map.of("status", STATUS_ACCEPTED)).get();
    }
}
