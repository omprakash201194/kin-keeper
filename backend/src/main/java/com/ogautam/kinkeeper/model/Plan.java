package com.ogautam.kinkeeper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A Plan is a time-bounded thing the family is organizing — a trip, a concert,
 * a wedding. Essentially the Wanderlog "trip" concept generalized to non-travel
 * events.
 *
 * `segments` holds the itinerary line-items (flights, hotels, activities).
 * `links` ties the plan to arbitrary subjects — members as attendees, contacts
 * (travel agent, promoter), assets (vehicle for a road trip), and documents
 * (tickets, bookings). The same LinkRef polymorphism the rest of the codebase
 * already uses; documents attached here should also back-link with type=PLAN.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Plan {
    private String id;
    private String familyId;

    private String name;
    private PlanType type;

    private LocalDate startDate;
    private LocalDate endDate;

    private String destination;    // "Goa", "Wembley Stadium, London" — free text
    private String notes;

    private List<PlanSegment> segments;
    private List<LinkRef> links;   // attendees (MEMBER/CONTACT), docs (DOCUMENT), assets, etc.

    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
}
