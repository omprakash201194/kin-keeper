package com.ogautam.kinkeeper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-serving nutrition breakdown. All fields optional so the vision analyzer
 * can populate whichever values it can read/estimate. Numbers are for a single
 * serving as consumed; servingDescription spells out what "one serving" means.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NutritionFacts {
    private String servingDescription;  // e.g. "1 cup (240 ml)", "1 apple (~180 g)"

    private Double calories;
    private Double proteinG;
    private Double carbsG;
    private Double sugarG;
    private Double fatG;
    private Double saturatedFatG;
    private Double fiberG;
    private Double sodiumMg;
}
