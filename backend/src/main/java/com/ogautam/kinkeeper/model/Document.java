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
    private String uploadedBy;
    private Instant uploadedAt;
}
