package com.ogautam.kinkeeper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FamilyMember {
    private String id;
    private String familyId;
    private String name;
    private String relationship;
    private LocalDate dateOfBirth;
    private String addedBy;
    private Instant createdAt;
}
