package com.ogautam.kinkeeper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Physical or legal thing the family owns/holds that has documents attached:
 * a home, a vehicle, an appliance, or a policy. One entity with a type
 * discriminator — only the fields relevant to each type are expected to be
 * populated, the rest are null.
 *
 * Field usage by type:
 *   HOME       → name, address, purchaseDate, ownerMemberIds, notes
 *   VEHICLE    → name, make, model, identifier (registration/VIN),
 *                purchaseDate, odometerKm, ownerMemberIds, notes
 *   APPLIANCE  → name, make, model, identifier (serial),
 *                purchaseDate, expiryDate (warranty end), ownerMemberIds, notes
 *   POLICY     → name, provider (make slot), identifier (policy number),
 *                startDate (purchaseDate slot), expiryDate, amount (premium),
 *                frequency (YEARLY/MONTHLY), insuredMemberIds (ownerMemberIds slot),
 *                linkedAssetIds, notes
 *
 * Dates are stored as ISO-8601 strings (YYYY-MM-DD) to sidestep Firestore's
 * inconsistent LocalDate serialization.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Asset {
    private String id;
    private String familyId;
    private AssetType type;

    private String name;
    private String make;
    private String model;
    private String identifier;      // serial / VIN / reg number / policy number
    private String address;         // HOME only
    private String provider;        // POLICY only: insurer/bank name

    private String purchaseDate;    // ISO-8601 (YYYY-MM-DD)
    private String expiryDate;      // warranty end / policy end

    private String frequency;       // POLICY: YEARLY | MONTHLY | QUARTERLY
    private BigDecimal amount;      // POLICY premium, VEHICLE value, etc.

    private Integer odometerKm;     // VEHICLE only

    private List<String> ownerMemberIds;    // family members tied to this asset
    private List<String> linkedAssetIds;    // POLICY: covered assets (vehicle/home)

    private String notes;
    private Instant createdAt;
}
