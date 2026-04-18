package com.ogautam.kinkeeper.service;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.ogautam.kinkeeper.config.CacheConfig;
import com.ogautam.kinkeeper.model.Asset;
import com.ogautam.kinkeeper.model.AssetType;
import com.ogautam.kinkeeper.model.LinkRef;
import com.ogautam.kinkeeper.model.LinkType;
import com.ogautam.kinkeeper.model.Reminder;
import com.ogautam.kinkeeper.model.ReminderRecurrence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Slf4j
@Service
public class ReminderService {

    private static final String COLLECTION = "reminders";

    private final Firestore firestore;
    private final AssetService assetService;

    public ReminderService(Firestore firestore, AssetService assetService) {
        this.firestore = firestore;
        this.assetService = assetService;
    }

    @Cacheable(value = CacheConfig.CACHE_REMINDERS, key = "#familyId")
    public List<Reminder> listByFamily(String familyId) throws ExecutionException, InterruptedException {
        List<QueryDocumentSnapshot> docs = firestore.collection(COLLECTION)
                .whereEqualTo("familyId", familyId)
                .get().get()
                .getDocuments();
        List<Reminder> out = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot d : docs) out.add(d.toObject(Reminder.class));
        out.sort(Comparator.comparing(
                (Reminder r) -> r.getDueAt() == null ? Instant.MAX : r.getDueAt()));
        return out;
    }

    /** Count of reminders the sidebar badge should show: open + due in 7 days or past due. */
    public long countDueSoon(String familyId) throws ExecutionException, InterruptedException {
        Instant cutoff = Instant.now().plus(7, ChronoUnit.DAYS);
        long total = 0;
        for (Reminder r : listByFamily(familyId)) {
            if (r.isCompleted()) continue;
            if (r.getRecurrence() == ReminderRecurrence.ODOMETER) {
                if (isOdometerDue(familyId, r)) total++;
                continue;
            }
            if (r.getDueAt() != null && r.getDueAt().isBefore(cutoff)) total++;
        }
        return total;
    }

    @CacheEvict(value = CacheConfig.CACHE_REMINDERS, key = "#familyId")
    public Reminder create(String familyId, String createdByUid, Reminder form)
            throws ExecutionException, InterruptedException {
        if (form.getTitle() == null || form.getTitle().isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        ReminderRecurrence recurrence = form.getRecurrence() != null ? form.getRecurrence() : ReminderRecurrence.NONE;
        if (recurrence == ReminderRecurrence.ODOMETER) {
            if (form.getDueOdometerKm() == null || form.getDueOdometerKm() <= 0) {
                throw new IllegalArgumentException("dueOdometerKm is required for odometer reminders");
            }
        } else if (form.getDueAt() == null) {
            throw new IllegalArgumentException("dueAt is required for date-based reminders");
        }

        DocumentReference ref = firestore.collection(COLLECTION).document();
        Reminder r = Reminder.builder()
                .id(ref.getId())
                .familyId(familyId)
                .title(form.getTitle().trim())
                .notes(form.getNotes())
                .dueAt(form.getDueAt())
                .recurrence(recurrence)
                .recurrenceIntervalKm(form.getRecurrenceIntervalKm())
                .dueOdometerKm(form.getDueOdometerKm())
                .linkedRefs(form.getLinkedRefs() == null ? List.of() : form.getLinkedRefs())
                .completed(false)
                .createdBy(createdByUid)
                .createdAt(Instant.now())
                .build();
        ref.set(r).get();
        log.info("Created reminder {} ({}) in family {}", ref.getId(), form.getTitle(), familyId);
        return r;
    }

    @CacheEvict(value = CacheConfig.CACHE_REMINDERS, key = "#familyId")
    public Reminder update(String familyId, String id, Map<String, Object> updates)
            throws ExecutionException, InterruptedException {
        requireInFamily(familyId, id);
        if (updates.isEmpty()) return requireInFamily(familyId, id);
        firestore.collection(COLLECTION).document(id).update(updates).get();
        return requireInFamily(familyId, id);
    }

    @CacheEvict(value = CacheConfig.CACHE_REMINDERS, key = "#familyId")
    public Reminder complete(String familyId, String id) throws ExecutionException, InterruptedException {
        Reminder r = requireInFamily(familyId, id);
        Instant now = Instant.now();

        if (r.getRecurrence() == null || r.getRecurrence() == ReminderRecurrence.NONE) {
            firestore.collection(COLLECTION).document(id).update(Map.of(
                    "completed", true,
                    "completedAt", now)).get();
            return requireInFamily(familyId, id);
        }

        if (r.getRecurrence() == ReminderRecurrence.ODOMETER) {
            int interval = r.getRecurrenceIntervalKm() == null ? 5000 : r.getRecurrenceIntervalKm();
            int nextDue = (r.getDueOdometerKm() == null ? 0 : r.getDueOdometerKm()) + interval;
            firestore.collection(COLLECTION).document(id).update(Map.of(
                    "dueOdometerKm", nextDue,
                    "completedAt", now)).get();
            return requireInFamily(familyId, id);
        }

        // Date-based recurrence: roll forward and stay open.
        Instant nextDue = rollForward(r.getDueAt() == null ? now : r.getDueAt(), r.getRecurrence());
        firestore.collection(COLLECTION).document(id).update(Map.of(
                "dueAt", nextDue,
                "completedAt", now)).get();
        return requireInFamily(familyId, id);
    }

    @CacheEvict(value = CacheConfig.CACHE_REMINDERS, key = "#familyId")
    public void delete(String familyId, String id) throws ExecutionException, InterruptedException {
        requireInFamily(familyId, id);
        firestore.collection(COLLECTION).document(id).delete().get();
        log.info("Deleted reminder {} from family {}", id, familyId);
    }

    private boolean isOdometerDue(String familyId, Reminder r) throws ExecutionException, InterruptedException {
        if (r.getLinkedRefs() == null) return false;
        for (LinkRef ref : r.getLinkedRefs()) {
            if (ref.getType() == LinkType.VEHICLE && ref.getId() != null) {
                Asset a = assetService.get(familyId, ref.getId());
                if (a.getType() == AssetType.VEHICLE
                        && a.getOdometerKm() != null
                        && r.getDueOdometerKm() != null
                        && a.getOdometerKm() >= r.getDueOdometerKm()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Instant rollForward(Instant from, ReminderRecurrence recurrence) {
        return switch (recurrence) {
            case DAILY -> from.plus(1, ChronoUnit.DAYS);
            case WEEKLY -> from.plus(7, ChronoUnit.DAYS);
            case MONTHLY -> from.atZone(ZoneOffset.UTC).plus(Period.ofMonths(1)).toInstant();
            case QUARTERLY -> from.atZone(ZoneOffset.UTC).plus(Period.ofMonths(3)).toInstant();
            case HALF_YEARLY -> from.atZone(ZoneOffset.UTC).plus(Period.ofMonths(6)).toInstant();
            case YEARLY -> from.atZone(ZoneOffset.UTC).plus(Period.ofYears(1)).toInstant();
            default -> from;
        };
    }

    private Reminder requireInFamily(String familyId, String id) throws ExecutionException, InterruptedException {
        DocumentSnapshot snap = firestore.collection(COLLECTION).document(id).get().get();
        if (!snap.exists()) throw new IllegalArgumentException("Reminder not found");
        Reminder r = snap.toObject(Reminder.class);
        if (r == null || !familyId.equals(r.getFamilyId())) {
            throw new IllegalArgumentException("Reminder not found");
        }
        return r;
    }
}
