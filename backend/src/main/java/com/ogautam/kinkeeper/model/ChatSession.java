package com.ogautam.kinkeeper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {
    private String id;
    private String userId;
    private String familyId;
    private String title;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant expiresAt;
    /** Legacy single-file pending field. Kept for backward reads. */
    private String pendingAttachmentId;
    /**
     * Current source of truth — attachments the user has staged for the next
     * turn. A message can carry many files; the agent decides how to route
     * each one (often via repeated save_attachment calls).
     */
    private List<String> pendingAttachmentIds;
    /**
     * Set by save_attachment while the agent loop is running; read + cleared by
     * ChatController after agent.chat() returns so we can patch the triggering
     * user message with the resulting Drive document ids. List because several
     * files in one turn can each become their own saved document.
     */
    private List<String> recentlySavedDocumentIds;
    /** Legacy single-id field. Kept for backward reads. */
    private String recentlySavedDocumentId;
}
