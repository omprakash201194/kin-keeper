package com.ogautam.kinkeeper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

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
    private String pendingAttachmentId;
    /**
     * Set by save_attachment while the agent loop is running; read + cleared by
     * ChatController after agent.chat() returns so we can patch the triggering
     * user message with the resulting Drive document id.
     */
    private String recentlySavedDocumentId;
}
