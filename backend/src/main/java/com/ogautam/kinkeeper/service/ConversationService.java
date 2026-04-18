package com.ogautam.kinkeeper.service;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.ogautam.kinkeeper.config.CacheConfig;
import com.ogautam.kinkeeper.model.Conversation;
import com.ogautam.kinkeeper.model.ConversationFormat;
import com.ogautam.kinkeeper.model.ConversationMessage;
import com.ogautam.kinkeeper.model.LinkRef;
import com.ogautam.kinkeeper.model.LinkType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

@Slf4j
@Service
public class ConversationService {

    private static final String COLLECTION = "conversations";
    private static final int TITLE_MAX = 80;

    private final Firestore firestore;

    public ConversationService(Firestore firestore) {
        this.firestore = firestore;
    }

    @Cacheable(value = CacheConfig.CACHE_CONVERSATIONS, key = "#familyId")
    public List<Conversation> listByFamily(String familyId) throws ExecutionException, InterruptedException {
        List<QueryDocumentSnapshot> docs = firestore.collection(COLLECTION)
                .whereEqualTo("familyId", familyId)
                .get().get()
                .getDocuments();
        List<Conversation> out = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot d : docs) out.add(d.toObject(Conversation.class));
        out.sort(Comparator.comparing(
                (Conversation c) -> c.getOccurredAt() == null ? Instant.MIN : c.getOccurredAt())
                .reversed());
        return out;
    }

    public List<Conversation> search(String familyId, String query, LinkType linkType, String linkId,
                                     Instant fromDate, Instant toDate)
            throws ExecutionException, InterruptedException {
        String needle = query == null ? null : query.toLowerCase();
        List<Conversation> out = new ArrayList<>();
        for (Conversation c : listByFamily(familyId)) {
            if (linkType != null) {
                boolean match = false;
                if (c.getLinks() != null) {
                    for (LinkRef ref : c.getLinks()) {
                        if (ref.getType() == linkType
                                && (linkId == null || linkId.equals(ref.getId()))) {
                            match = true;
                            break;
                        }
                    }
                }
                if (!match) continue;
            }
            if (fromDate != null && c.getOccurredAt() != null && c.getOccurredAt().isBefore(fromDate)) continue;
            if (toDate != null && c.getOccurredAt() != null && c.getOccurredAt().isAfter(toDate)) continue;
            if (needle != null && !matchesText(c, needle)) continue;
            out.add(c);
        }
        return out;
    }

    @CacheEvict(value = CacheConfig.CACHE_CONVERSATIONS, key = "#familyId")
    public Conversation create(String familyId, String createdByUid, Conversation form)
            throws ExecutionException, InterruptedException {
        if (form.getFormat() == null) {
            throw new IllegalArgumentException("format is required (ENCOUNTER or THREAD)");
        }
        if (form.getLinks() == null || form.getLinks().isEmpty()) {
            throw new IllegalArgumentException(
                    "Conversations must link to at least one subject (contact, member, asset, or document)");
        }
        if (form.getFormat() == ConversationFormat.ENCOUNTER
                && (form.getSummary() == null || form.getSummary().isBlank())) {
            throw new IllegalArgumentException("summary is required for ENCOUNTER format");
        }
        if (form.getFormat() == ConversationFormat.THREAD
                && (form.getMessages() == null || form.getMessages().isEmpty())) {
            throw new IllegalArgumentException("THREAD format needs at least one message");
        }

        Instant now = Instant.now();
        DocumentReference ref = firestore.collection(COLLECTION).document();
        Conversation c = Conversation.builder()
                .id(ref.getId())
                .familyId(familyId)
                .title(deriveTitle(form))
                .format(form.getFormat())
                .channel(form.getChannel())
                .occurredAt(form.getOccurredAt() != null ? form.getOccurredAt() : now)
                .summary(form.getSummary())
                .outcome(form.getOutcome())
                .followUp(form.getFollowUp())
                .messages(normalizeMessages(form.getMessages()))
                .links(form.getLinks())
                .createdBy(createdByUid)
                .createdAt(now)
                .updatedAt(now)
                .build();
        ref.set(c).get();
        log.info("Logged conversation {} ({}) in family {} with {} subject link(s)",
                ref.getId(), c.getFormat(), familyId, c.getLinks().size());
        return c;
    }

    @CacheEvict(value = CacheConfig.CACHE_CONVERSATIONS, key = "#familyId")
    public Conversation update(String familyId, String id, Map<String, Object> updates)
            throws ExecutionException, InterruptedException {
        Conversation existing = requireInFamily(familyId, id);
        if (updates.isEmpty()) return existing;
        updates.put("updatedAt", Instant.now());
        firestore.collection(COLLECTION).document(id).update(updates).get();
        return requireInFamily(familyId, id);
    }

    @CacheEvict(value = CacheConfig.CACHE_CONVERSATIONS, key = "#familyId")
    public void delete(String familyId, String id) throws ExecutionException, InterruptedException {
        requireInFamily(familyId, id);
        firestore.collection(COLLECTION).document(id).delete().get();
        log.info("Deleted conversation {} from family {}", id, familyId);
    }

    public Conversation get(String familyId, String id) throws ExecutionException, InterruptedException {
        return requireInFamily(familyId, id);
    }

    private Conversation requireInFamily(String familyId, String id) throws ExecutionException, InterruptedException {
        DocumentSnapshot snap = firestore.collection(COLLECTION).document(id).get().get();
        if (!snap.exists()) throw new IllegalArgumentException("Conversation not found");
        Conversation c = snap.toObject(Conversation.class);
        if (c == null || !familyId.equals(c.getFamilyId())) {
            throw new IllegalArgumentException("Conversation not found");
        }
        return c;
    }

    private static String deriveTitle(Conversation form) {
        if (form.getTitle() != null && !form.getTitle().isBlank()) return truncate(form.getTitle());
        if (form.getSummary() != null && !form.getSummary().isBlank()) return truncate(form.getSummary());
        if (form.getMessages() != null && !form.getMessages().isEmpty()) {
            ConversationMessage m = form.getMessages().get(0);
            if (m.getContent() != null && !m.getContent().isBlank()) return truncate(m.getContent());
        }
        return "Conversation";
    }

    private static String truncate(String s) {
        String clean = s.strip().replaceAll("\\s+", " ");
        return clean.length() <= TITLE_MAX ? clean : clean.substring(0, TITLE_MAX - 1) + "…";
    }

    private static List<ConversationMessage> normalizeMessages(List<ConversationMessage> msgs) {
        if (msgs == null) return null;
        Instant t = Instant.now();
        List<ConversationMessage> out = new ArrayList<>(msgs.size());
        for (ConversationMessage m : msgs) {
            out.add(ConversationMessage.builder()
                    .id(m.getId() != null && !m.getId().isBlank() ? m.getId() : UUID.randomUUID().toString())
                    .from(m.getFrom())
                    .content(m.getContent())
                    .at(m.getAt() != null ? m.getAt() : t)
                    .build());
        }
        return out;
    }

    private static boolean matchesText(Conversation c, String needle) {
        return contains(c.getTitle(), needle)
                || contains(c.getSummary(), needle)
                || contains(c.getOutcome(), needle)
                || contains(c.getFollowUp(), needle)
                || messagesContain(c.getMessages(), needle);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle);
    }

    private static boolean messagesContain(List<ConversationMessage> msgs, String needle) {
        if (msgs == null) return false;
        for (ConversationMessage m : msgs) {
            if (contains(m.getFrom(), needle) || contains(m.getContent(), needle)) return true;
        }
        return false;
    }
}
