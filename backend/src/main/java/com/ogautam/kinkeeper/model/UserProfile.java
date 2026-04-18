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
public class UserProfile {
    private String uid;
    private String email;
    private String displayName;
    private String photoUrl;
    private String familyId;
    private String claudeApiKeyEncrypted;
    private String driveRefreshTokenEncrypted;
    private Instant createdAt;
    private Instant updatedAt;
}
