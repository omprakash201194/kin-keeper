package com.ogautam.kinkeeper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One file that was attached to a chat message. A single ChatMessage may carry
 * several of these so the user can upload many files in one turn and ask the
 * agent to route/classify each individually.
 *
 * `documentId` is filled in only after the agent's save_attachment tool runs
 * — until then the UI renders the filename as a pending pill.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatAttachment {
    private String fileName;
    private String mimeType;
    private String documentId;
}
