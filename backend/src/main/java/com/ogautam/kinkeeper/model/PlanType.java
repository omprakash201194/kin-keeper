package com.ogautam.kinkeeper.model;

/**
 * What kind of plan this is. TRIP means "we're going somewhere"; the rest are
 * time-bounded things worth organizing even though they aren't travel —
 * concerts, weddings, conferences, festivals. The type is purely informational
 * today but lets us style the list and, later, gate map-view to TRIP.
 */
public enum PlanType {
    TRIP,
    EVENT,
    CELEBRATION,
    OTHER
}
