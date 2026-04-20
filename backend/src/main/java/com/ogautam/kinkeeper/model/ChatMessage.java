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
public class ChatMessage {
    private String id;
    private String sessionId;
    private String role;
    private String content;
    private Instant createdAt;

    // Legacy single-attachment fields (kept so old messages still render).
    private String attachmentFileName;
    private String attachmentMimeType;
    private String attachmentDocumentId;

    /**
     * Current source of truth for attachments. New user messages always write
     * this list — the singular fields above stay blank on new writes so the
     * UI can key entirely off `attachments` once old history ages out.
     */
    private List<ChatAttachment> attachments;
}
