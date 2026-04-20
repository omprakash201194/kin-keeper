package com.ogautam.kinkeeper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One payment event against a POLICY asset — a utility bill, a recharge, a
 * subscription renewal, a credit card statement. Always linked to an asset;
 * the agent is taught to list_assets first and only create a bill when a
 * matching POLICY exists.
 *
 * The asset's `expiryDate` tracks the next renewal/due; the Bill history
 * tracks what was actually paid (or what's come due) over time. Together
 * they support trend questions ("has my internet gone up?") and spend
 * rollups.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Bill {
    private String id;
    private String familyId;
    private String assetId;            // required — points at a POLICY asset

    private Instant dueAt;             // when the bill is/was due
    private Instant paidAt;            // optional — when actually paid

    private BigDecimal amount;
    private String currency;           // e.g. "INR"; defaults to "INR" server-side

    private BillSource source;
    private String sourceText;         // raw SMS/email text so we can re-parse later
    private String notes;

    private String createdBy;
    private Instant createdAt;
}
