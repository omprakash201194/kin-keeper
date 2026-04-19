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

    public void setPendingAttachment(FirebaseUserPrincipal principal, String sessionId, String attachmentId)
            throws ExecutionException, InterruptedException {
        get(principal, sessionId);
        firestore.collection(SESSIONS_COLLECTION).document(sessionId)
                .update("pendingAttachmentId", attachmentId).get();
    }

    public void clearPendingAttachment(String sessionId) throws ExecutionException, InterruptedException {
        // reason: used by the agent after save_attachment consumes the staged file.
        // Skips the ownership check because the agent has already authorized via principal.
        firestore.collection(SESSIONS_COLLECTION).document(sessionId)
                .update("pendingAttachmentId", null).get();
    }

    /** Called by the agent's save_attachment tool when a Drive upload succeeds. */
    public void markRecentlySavedDocument(String sessionId, String documentId)
            throws ExecutionException, InterruptedException {
        firestore.collection(SESSIONS_COLLECTION).document(sessionId)
                .update("recentlySavedDocumentId", documentId).get();
    }

    /** Called by ChatController after agent.chat() — returns the id and resets the field. */
    public String consumeRecentlySavedDocument(String sessionId)
            throws ExecutionException, InterruptedException {
        DocumentSnapshot snap = firestore.collection(SESSIONS_COLLECTION).document(sessionId).get().get();
        if (!snap.exists()) return null;
        Object val = snap.get("recentlySavedDocumentId");
        if (val == null) return null;
        firestore.collection(SESSIONS_COLLECTION).document(sessionId)
                .update("recentlySavedDocumentId", null).get();
        return val.toString();
    }

    /** Back-fill an attachmentDocumentId onto an existing message (used after save_attachment). */
    public void setMessageDocumentId(String sessionId, String messageId, String documentId)
            throws ExecutionException, InterruptedException {
        messages(sessionId).document(messageId)
                .update("attachmentDocumentId", documentId).get();
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
