package com.ogautam.kinkeeper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * A dated nudge. Can stand alone or link to a document / asset / member /
 * contact via the linkedRefs list.
 *
 * Recurrence semantics:
 *   NONE      — one-off, stays on the list after dueAt until completed.
 *   DAILY/WEEKLY/MONTHLY/QUARTERLY/HALF_YEARLY/YEARLY — when the user marks
 *               the reminder completed, dueAt is rolled forward by the interval
 *               and completed flips back to false.
 *   ODOMETER  — vehicle servicing; dueOdometerKm is the target reading on the
 *               linked vehicle asset. A reminder is "due" when
 *               asset.odometerKm >= dueOdometerKm. On completion the interval
 *               (recurrenceIntervalKm) is added to dueOdometerKm.
 *
 * In-app only for now: no push / email. The sidebar badge counts reminders
 * where !completed AND (dueAt <= now OR odometer due).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Reminder {
    private String id;
    private String familyId;

    private String title;
    private String notes;

    private Instant dueAt;
    private ReminderRecurrence recurrence;
    private Integer recurrenceIntervalKm; // ODOMETER only
    private Integer dueOdometerKm;        // ODOMETER only

    private List<LinkRef> linkedRefs;

    private boolean completed;
    private Instant completedAt;

    private String createdBy;
    private Instant createdAt;
}
