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
public class Invite {
    private String id;
    private String familyId;
    private String email;
    private String role;
    private String invitedBy;
    private Instant createdAt;
    private String status;
}
