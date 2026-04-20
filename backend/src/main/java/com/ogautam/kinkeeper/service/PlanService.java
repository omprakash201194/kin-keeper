package com.ogautam.kinkeeper.service;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.ogautam.kinkeeper.config.CacheConfig;
import com.ogautam.kinkeeper.model.LinkRef;
import com.ogautam.kinkeeper.model.LinkType;
import com.ogautam.kinkeeper.model.Plan;
import com.ogautam.kinkeeper.model.PlanSegment;
import com.ogautam.kinkeeper.model.PlanType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

@Slf4j
@Service
public class PlanService {

    private static final String COLLECTION = "plans";

    private final Firestore firestore;
    private final DocumentService documentService;

    public PlanService(Firestore firestore, DocumentService documentService) {
        this.firestore = firestore;
        this.documentService = documentService;
    }

    @Cacheable(value = CacheConfig.CACHE_PLANS, key = "#familyId")
    public List<Plan> listByFamily(String familyId) throws ExecutionException, InterruptedException {
        List<QueryDocumentSnapshot> docs = firestore.collection(COLLECTION)
                .whereEqualTo("familyId", familyId)
                .get().get().getDocuments();
        List<Plan> out = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot d : docs) out.add(d.toObject(Plan.class));
        // Upcoming-first, then past — sort by startDate descending so the currently-active
        // and next-up plans sit at the top.
        out.sort(Comparator.comparing(
                (Plan p) -> p.getStartDate() == null ? LocalDate.MIN : p.getStartDate()).reversed());
        return out;
    }

    @CacheEvict(value = CacheConfig.CACHE_PLANS, key = "#familyId")
    public Plan create(String familyId, String createdByUid, Plan form)
            throws ExecutionException, InterruptedException {
        if (form.getName() == null || form.getName().isBlank()) {
            throw new IllegalArgumentException("Plan name is required");
        }
        Instant now = Instant.now();
        DocumentReference ref = firestore.collection(COLLECTION).document();
        Plan p = Plan.builder()
                .id(ref.getId())
                .familyId(familyId)
                .name(form.getName().trim())
                .type(form.getType() != null ? form.getType() : PlanType.TRIP)
                .startDate(form.getStartDate())
                .endDate(form.getEndDate())
                .destination(form.getDestination())
                .notes(form.getNotes())
                .segments(normalizeSegments(form.getSegments()))
                .links(form.getLinks() != null ? form.getLinks() : List.of())
                .createdBy(createdByUid)
                .createdAt(now)
                .updatedAt(now)
                .build();
        ref.set(p).get();
        log.info("Created plan {} '{}' ({}) in family {} with {} segment(s)",
                ref.getId(), p.getName(), p.getType(), familyId,
                p.getSegments() == null ? 0 : p.getSegments().size());
        return p;
    }

    @CacheEvict(value = CacheConfig.CACHE_PLANS, key = "#familyId")
    public Plan update(String familyId, String id, Map<String, Object> updates)
            throws ExecutionException, InterruptedException {
        Plan existing = requireInFamily(familyId, id);
        if (updates.isEmpty()) return existing;
        updates.put("updatedAt", Instant.now());
        firestore.collection(COLLECTION).document(id).update(updates).get();
        return requireInFamily(familyId, id);
    }

    @CacheEvict(value = CacheConfig.CACHE_PLANS, key = "#familyId")
    public void delete(String familyId, String id) throws ExecutionException, InterruptedException {
        requireInFamily(familyId, id);
        firestore.collection(COLLECTION).document(id).delete().get();
        log.info("Deleted plan {} from family {}", id, familyId);
    }

    public Plan get(String familyId, String id) throws ExecutionException, InterruptedException {
        return requireInFamily(familyId, id);
    }

    @CacheEvict(value = CacheConfig.CACHE_PLANS, key = "#familyId")
    public Plan addSegment(String familyId, String planId, PlanSegment segment)
            throws ExecutionException, InterruptedException {
        Plan plan = requireInFamily(familyId, planId);
        List<PlanSegment> current = plan.getSegments() != null
                ? new ArrayList<>(plan.getSegments())
                : new ArrayList<>();
        if (segment.getId() == null || segment.getId().isBlank()) {
            segment.setId(UUID.randomUUID().toString());
        }
        current.add(segment);
        sortSegments(current);
        firestore.collection(COLLECTION).document(planId)
                .update("segments", current, "updatedAt", Instant.now()).get();
        plan.setSegments(current);
        return plan;
    }

    /**
     * Link an existing Document to a Plan: adds a PLAN link to the document and
     * the DOCUMENT link to the plan. Idempotent — running it twice is safe.
     */
    @CacheEvict(value = CacheConfig.CACHE_PLANS, key = "#familyId")
    public Plan linkDocument(String familyId, String planId, String documentId,
                             com.ogautam.kinkeeper.security.FirebaseUserPrincipal principal)
            throws ExecutionException, InterruptedException {
        Plan plan = requireInFamily(familyId, planId);
        List<LinkRef> planLinks = plan.getLinks() != null ? new ArrayList<>(plan.getLinks()) : new ArrayList<>();
        boolean alreadyLinked = planLinks.stream().anyMatch(
                l -> l.getType() == LinkType.DOCUMENT && documentId.equals(l.getId()));
        if (!alreadyLinked) {
            planLinks.add(LinkRef.builder().type(LinkType.DOCUMENT).id(documentId).build());
            firestore.collection(COLLECTION).document(planId)
                    .update("links", planLinks, "updatedAt", Instant.now()).get();
            plan.setLinks(planLinks);
        }
        // Back-link on the document so filtering docs by plan works without a join.
        var doc = documentService.getDocument(principal, documentId);
        List<LinkRef> docLinks = doc.getLinks() != null ? new ArrayList<>(doc.getLinks()) : new ArrayList<>();
        boolean alreadyBackLinked = docLinks.stream().anyMatch(
                l -> l.getType() == LinkType.PLAN && planId.equals(l.getId()));
        if (!alreadyBackLinked) {
            docLinks.add(LinkRef.builder().type(LinkType.PLAN).id(planId).build());
            documentService.setLinks(principal, documentId, docLinks);
        }
        log.info("Linked document {} to plan {}", documentId, planId);
        return plan;
    }

    private Plan requireInFamily(String familyId, String id) throws ExecutionException, InterruptedException {
        DocumentSnapshot snap = firestore.collection(COLLECTION).document(id).get().get();
        if (!snap.exists()) throw new IllegalArgumentException("Plan not found");
        Plan p = snap.toObject(Plan.class);
        if (p == null || !familyId.equals(p.getFamilyId())) {
            throw new IllegalArgumentException("Plan not found");
        }
        return p;
    }

    private static List<PlanSegment> normalizeSegments(List<PlanSegment> segments) {
        if (segments == null) return List.of();
        List<PlanSegment> out = new ArrayList<>(segments.size());
        for (PlanSegment s : segments) {
            if (s == null) continue;
            if (s.getId() == null || s.getId().isBlank()) s.setId(UUID.randomUUID().toString());
            out.add(s);
        }
        sortSegments(out);
        return out;
    }

    private static void sortSegments(List<PlanSegment> segments) {
        segments.sort(Comparator.comparing(
                (PlanSegment s) -> s.getStartAt() == null ? Instant.MAX : s.getStartAt()));
    }
}
