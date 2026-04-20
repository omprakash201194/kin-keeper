package com.ogautam.kinkeeper.service;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.WriteBatch;

import java.util.Comparator;
import com.ogautam.kinkeeper.model.ChatMessage;
import com.ogautam.kinkeeper.model.ChatSession;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Slf4j
@Service
public class ChatSessionService {

    public static final int DEFAULT_RETENTION_DAYS = 7;
    public static final int MIN_RETENTION_DAYS = 1;
    public static final int MAX_RETENTION_DAYS = 90;
    private static final int TITLE_MAX_LEN = 60;

    private static final String SESSIONS_COLLECTION = "chat_sessions";
    private static final String MESSAGES_SUBCOLLECTION = "messages";

    private final Firestore firestore;
    private final UserService userService;

    public ChatSessionService(Firestore firestore, UserService userService) {
        this.firestore = firestore;
        this.userService = userService;
    }

    public ChatSession create(FirebaseUserPrincipal principal)
            throws ExecutionException, InterruptedException {
        int days = userService.getChatRetentionDays(principal.uid());
        Instant now = Instant.now();
        DocumentReference ref = firestore.collection(SESSIONS_COLLECTION).document();
        ChatSession session = ChatSession.builder()
                .id(ref.getId())
                .userId(principal.uid())
                .title("New chat")
                .createdAt(now)
                .updatedAt(now)
                .expiresAt(now.plus(days, ChronoUnit.DAYS))
                .build();
        ref.set(session).get();
        log.info("Created chat session {} for user {}", ref.getId(), principal.uid());
        return session;
    }

    public List<ChatSession> listForUser(FirebaseUserPrincipal principal)
            throws ExecutionException, InterruptedException {
        Instant now = Instant.now();
        // reason: no server-side orderBy — Firestore would require a composite
        // index for equality+orderBy, and per-user session counts stay small.
        List<QueryDocumentSnapshot> docs = firestore.collection(SESSIONS_COLLECTION)
                .whereEqualTo("userId", principal.uid())
                .get().get()
                .getDocuments();
        List<ChatSession> active = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot doc : docs) {
            ChatSession s = doc.toObject(ChatSession.class);
            if (s.getExpiresAt() != null && s.getExpiresAt().isBefore(now)) {
                // reason: lazy delete keeps Firestore tidy without a scheduler for this personal-scale app
                deleteCascade(doc.getId());
                continue;
            }
            active.add(s);
        }
        active.sort(Comparator.comparing(ChatSession::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return active;
    }

    public ChatSession get(FirebaseUserPrincipal principal, String sessionId)
            throws ExecutionException, InterruptedException {
        DocumentSnapshot doc = firestore.collection(SESSIONS_COLLECTION).document(sessionId).get().get();
        if (!doc.exists()) {
            throw new IllegalArgumentException("Chat session not found");
        }
        ChatSession session = doc.toObject(ChatSession.class);
        if (session == null || !principal.uid().equals(session.getUserId())) {
            throw new IllegalArgumentException("Chat session not found");
        }
        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(Instant.now())) {
            deleteCascade(sessionId);
            throw new IllegalArgumentException("Chat session has expired");
        }
        return session;
    }

    public List<ChatMessage> listMessages(FirebaseUserPrincipal principal, String sessionId)
            throws ExecutionException, InterruptedException {
        get(principal, sessionId);
        List<QueryDocumentSnapshot> docs = messages(sessionId)
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .get().get()
                .getDocuments();
        List<ChatMessage> out = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot d : docs) {
            out.add(d.toObject(ChatMessage.class));
        }
        return out;
    }

    public ChatMessage appendMessage(FirebaseUserPrincipal principal, String sessionId,
                                     String role, String content)
            throws ExecutionException, InterruptedException {
        return appendMessage(principal, sessionId, role, content, null, null);
    }

    public ChatMessage appendMessage(FirebaseUserPrincipal principal, String sessionId,
                                     String role, String content,
                                     String attachmentFileName, String attachmentMimeType)
            throws ExecutionException, InterruptedException {
        return appendMessage(principal, sessionId, role, content,
                attachmentFileName, attachmentMimeType, null);
    }

    public ChatMessage appendMessage(FirebaseUserPrincipal principal, String sessionId,
                                     String role, String content,
                                     String attachmentFileName, String attachmentMimeType,
                                     List<com.ogautam.kinkeeper.model.ChatAttachment> attachments)
            throws ExecutionException, InterruptedException {
        ChatSession session = get(principal, sessionId);
        Instant now = Instant.now();
        DocumentReference ref = messages(sessionId).document();
        ChatMessage msg = ChatMessage.builder()
                .id(ref.getId())
                .sessionId(sessionId)
                .role(role)
                .content(content)
                .createdAt(now)
                .attachmentFileName(attachmentFileName)
                .attachmentMimeType(attachmentMimeType)
                .attachments(attachments)
                .build();
        ref.set(msg).get();

        int days = userService.getChatRetentionDays(principal.uid());
        Map<String, Object> sessionUpdates = new java.util.HashMap<>();
        sessionUpdates.put("updatedAt", now);
        sessionUpdates.put("expiresAt", now.plus(days, ChronoUnit.DAYS));
        // reason: auto-title from first user message so the sidebar is readable
        if ("user".equalsIgnoreCase(role) && ("New chat".equals(session.getTitle()) || session.getTitle() == null)) {
            sessionUpdates.put("title", truncateTitle(content));
        }
        firestore.collection(SESSIONS_COLLECTION).document(sessionId).update(sessionUpdates).get();
        return msg;
    }

    public void delete(FirebaseUserPrincipal principal, String sessionId)
            throws ExecutionException, InterruptedException {
        get(principal, sessionId);
        deleteCascade(sessionId);
    }

    public void setPendingAttachments(FirebaseUserPrincipal principal, String sessionId, List<String> attachmentIds)
            throws ExecutionException, InterruptedException {
        get(principal, sessionId);
        Map<String, Object> updates = new java.util.HashMap<>();
        updates.put("pendingAttachmentIds", attachmentIds == null ? List.of() : attachmentIds);
        // reason: keep legacy pendingAttachmentId in sync for the first file so any
        // rolling reads that haven't been migrated still see something.
        updates.put("pendingAttachmentId",
                attachmentIds == null || attachmentIds.isEmpty() ? null : attachmentIds.get(0));
        firestore.collection(SESSIONS_COLLECTION).document(sessionId).update(updates).get();
    }

    public void clearPendingAttachment(String sessionId, String attachmentId)
            throws ExecutionException, InterruptedException {
        // reason: used by the agent after save_attachment consumes one of the staged files.
        // Removes only the matching id so other files in the same turn remain addressable.
        DocumentSnapshot snap = firestore.collection(SESSIONS_COLLECTION).document(sessionId).get().get();
        if (!snap.exists()) return;
        Object raw = snap.get("pendingAttachmentIds");
        List<String> next = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().equals(attachmentId)) next.add(o.toString());
            }
        }
        Map<String, Object> updates = new java.util.HashMap<>();
        updates.put("pendingAttachmentIds", next);
        Object legacy = snap.get("pendingAttachmentId");
        if (legacy != null && legacy.toString().equals(attachmentId)) {
            updates.put("pendingAttachmentId", null);
        }
        firestore.collection(SESSIONS_COLLECTION).document(sessionId).update(updates).get();
    }

    /** Called by the agent's save_attachment tool when a Drive upload succeeds. */
    public void markRecentlySavedDocument(String sessionId, String documentId)
            throws ExecutionException, InterruptedException {
        DocumentSnapshot snap = firestore.collection(SESSIONS_COLLECTION).document(sessionId).get().get();
        List<String> cur = new ArrayList<>();
        if (snap.exists()) {
            Object raw = snap.get("recentlySavedDocumentIds");
            if (raw instanceof List<?> list) {
                for (Object o : list) if (o != null) cur.add(o.toString());
            }
        }
        cur.add(documentId);
        Map<String, Object> updates = new java.util.HashMap<>();
        updates.put("recentlySavedDocumentIds", cur);
        updates.put("recentlySavedDocumentId", documentId);
        firestore.collection(SESSIONS_COLLECTION).document(sessionId).update(updates).get();
    }

    /** Called by ChatController after agent.chat() — returns the ids and resets the field. */
    public List<String> consumeRecentlySavedDocuments(String sessionId)
            throws ExecutionException, InterruptedException {
        DocumentSnapshot snap = firestore.collection(SESSIONS_COLLECTION).document(sessionId).get().get();
        if (!snap.exists()) return List.of();
        List<String> out = new ArrayList<>();
        Object raw = snap.get("recentlySavedDocumentIds");
        if (raw instanceof List<?> list) {
            for (Object o : list) if (o != null) out.add(o.toString());
        } else {
            Object legacy = snap.get("recentlySavedDocumentId");
            if (legacy != null) out.add(legacy.toString());
        }
        if (out.isEmpty()) return out;
        Map<String, Object> updates = new java.util.HashMap<>();
        updates.put("recentlySavedDocumentIds", null);
        updates.put("recentlySavedDocumentId", null);
        firestore.collection(SESSIONS_COLLECTION).document(sessionId).update(updates).get();
        return out;
    }

    /** Back-fill an attachmentDocumentId onto an existing message (used after save_attachment). */
    public void setMessageDocumentId(String sessionId, String messageId, String documentId)
            throws ExecutionException, InterruptedException {
        messages(sessionId).document(messageId)
                .update("attachmentDocumentId", documentId).get();
    }

    /**
     * Back-fill documentIds onto a list of attachments already stored on a message.
     * Pairs each input attachmentId (the Redis-staged id seen by the agent) with the
     * resulting Drive document id, in order — the agent calls save_attachment once
     * per file, and recentlySavedDocumentIds preserves that order.
     */
    public void backfillMessageAttachments(String sessionId, String messageId,
                                           List<com.ogautam.kinkeeper.model.ChatAttachment> attachments,
                                           List<String> savedDocIds)
            throws ExecutionException, InterruptedException {
        if (attachments == null || attachments.isEmpty() || savedDocIds == null || savedDocIds.isEmpty()) return;
        // Assign saved doc ids to attachments that don't already have one, in order.
        int cursor = 0;
        for (com.ogautam.kinkeeper.model.ChatAttachment a : attachments) {
            if (a.getDocumentId() == null && cursor < savedDocIds.size()) {
                a.setDocumentId(savedDocIds.get(cursor++));
            }
        }
        messages(sessionId).document(messageId).update("attachments", attachments).get();
    }

    private void deleteCascade(String sessionId) throws ExecutionException, InterruptedException {
        List<QueryDocumentSnapshot> msgDocs = messages(sessionId).get().get().getDocuments();
        if (!msgDocs.isEmpty()) {
            WriteBatch batch = firestore.batch();
            for (QueryDocumentSnapshot d : msgDocs) {
                batch.delete(d.getReference());
            }
            batch.commit().get();
        }
        firestore.collection(SESSIONS_COLLECTION).document(sessionId).delete().get();
    }

    private CollectionReference messages(String sessionId) {
        return firestore.collection(SESSIONS_COLLECTION).document(sessionId)
                .collection(MESSAGES_SUBCOLLECTION);
    }

    private static String truncateTitle(String text) {
        String trimmed = text.strip().replaceAll("\\s+", " ");
        if (trimmed.length() <= TITLE_MAX_LEN) return trimmed;
        return trimmed.substring(0, TITLE_MAX_LEN - 1) + "…";
    }
}
