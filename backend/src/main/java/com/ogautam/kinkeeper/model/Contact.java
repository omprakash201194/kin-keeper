package com.ogautam.kinkeeper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A non-family person whose documents we keep — lawyer, doctor, landlord, etc.
 * Contacts never log into the app; they're just metadata attached to documents.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Contact {
    private String id;
    private String familyId;
    private String name;
    private String relationship;
    private String phone;
    private String email;
    private String notes;
    private Instant createdAt;
}
