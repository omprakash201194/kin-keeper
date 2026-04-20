package com.ogautam.kinkeeper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One line on a plan's itinerary — a flight, a hotel stay, a concert slot, etc.
 *
 * `documentId` ties this segment to a Document (a boarding pass, hotel PDF,
 * e-ticket). That document should ALSO carry a LinkRef{type=PLAN, id=<plan>}
 * so filtering docs by plan surfaces every attached file in one go.
 *
 * Location is free text today. When map-view lands we'll add latitude/longitude
 * here and geocode on save — see ROADMAP.md.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanSegment {
    private String id;
    private PlanSegmentKind kind;

    private String title;
    private String location;
    private String confirmation;  // PNR / booking reference
    private String notes;

    private Instant startAt;
    private Instant endAt;

    private String documentId;
}
