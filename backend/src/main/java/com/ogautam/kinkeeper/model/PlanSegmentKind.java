package com.ogautam.kinkeeper.model;

/**
 * What a single line on a plan's itinerary represents. Kept as a single enum
 * instead of per-kind sub-entities — the fields each kind cares about are
 * largely the same (start/end, location, confirmation #, linked document).
 */
public enum PlanSegmentKind {
    FLIGHT,
    HOTEL,
    ACTIVITY,
    CONCERT,
    MEAL,
    TRANSPORT,
    OTHER
}
