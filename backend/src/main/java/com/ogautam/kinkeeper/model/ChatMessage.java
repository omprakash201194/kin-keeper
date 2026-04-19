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
public class ChatMessage {
    private String id;
    private String sessionId;
    private String role;
    private String content;
    private Instant createdAt;

    // Populated only on user messages that carried an attachment.
    private String attachmentFileName;
    private String attachmentMimeType;
    // Filled in after-the-fact if the agent's save_attachment tool persisted the file
    // to Drive. Lets the UI render an inline preview of the saved document.
    private String attachmentDocumentId;
}
