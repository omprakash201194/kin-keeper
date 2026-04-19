package com.ogautam.kinkeeper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * One logged food/drink item — the output of a camera scan + Claude-vision
 * analysis. Always linked to a family member (who consumed it) so totals can
 * be rolled up per person per day.
 *
 * Photos are NOT persisted in v1 — we keep the analysis only to save storage.
 * Re-scan if you want the image back.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NutritionEntry {
    private String id;
    private String familyId;
    private String memberId;

    private Instant consumedAt;

    private String foodName;        // short label, e.g. "Grilled chicken salad"
    private String description;     // freeform longer description from the analyzer
    private NutritionSource source;

    private NutritionFacts facts;

    private List<String> ingredients;
    private List<String> healthBenefits;
    private List<String> warnings;  // high sugar, allergen, ultra-processed, etc.

    private String createdBy;
    private Instant createdAt;
}
