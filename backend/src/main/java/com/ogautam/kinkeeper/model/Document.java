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
public class Document {
    private String id;
    private String familyId;
    private String memberId;
    private String categoryId;
    private String fileName;
    private String mimeType;
    private long fileSize;
    private String driveFileId;
    private String driveFolderId;
    private String notes;
    private List<String> tags;
    private List<LinkRef> links;     // additional subjects beyond memberId (contacts, assets)
    private String uploadedBy;
    private Instant uploadedAt;

    /**
     * Full-text contents of the document, extracted via Claude vision on upload.
     * Populated for PDFs and common image types; null for office docs, archives,
     * or when the uploader has no Claude API key saved. When present, used by
     * search_documents to match against the actual document contents, not just
     * filename/tags/notes.
     */
    private String extractedText;
    private Instant extractedAt;
}
