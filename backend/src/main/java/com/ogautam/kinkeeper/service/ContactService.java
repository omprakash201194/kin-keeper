package com.ogautam.kinkeeper.service;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.ogautam.kinkeeper.config.CacheConfig;
import com.ogautam.kinkeeper.model.Contact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Slf4j
@Service
public class ContactService {

    private static final String COLLECTION = "contacts";

    private final Firestore firestore;

    public ContactService(Firestore firestore) {
        this.firestore = firestore;
    }

    @Cacheable(value = CacheConfig.CACHE_CONTACTS, key = "#familyId")
    public List<Contact> listByFamily(String familyId) throws ExecutionException, InterruptedException {
        List<QueryDocumentSnapshot> docs = firestore.collection(COLLECTION)
                .whereEqualTo("familyId", familyId)
                .get().get()
                .getDocuments();
        List<Contact> out = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot d : docs) out.add(d.toObject(Contact.class));
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    @CacheEvict(value = CacheConfig.CACHE_CONTACTS, key = "#familyId")
    public Contact create(String familyId, String name, String relationship,
                          String phone, String email, String notes)
            throws ExecutionException, InterruptedException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        DocumentReference ref = firestore.collection(COLLECTION).document();
        Contact c = Contact.builder()
                .id(ref.getId())
                .familyId(familyId)
                .name(name.trim())
                .relationship(relationship)
                .phone(phone)
                .email(email)
                .notes(notes)
                .createdAt(Instant.now())
                .build();
        ref.set(c).get();
        log.info("Created contact {} ({}) in family {}", ref.getId(), name, familyId);
        return c;
    }

    @CacheEvict(value = CacheConfig.CACHE_CONTACTS, key = "#familyId")
    public Contact update(String familyId, String id, Map<String, Object> updates)
            throws ExecutionException, InterruptedException {
        Contact existing = requireInFamily(familyId, id);
        if (updates.isEmpty()) return existing;
        firestore.collection(COLLECTION).document(id).update(updates).get();
        return requireInFamily(familyId, id);
    }

    @CacheEvict(value = CacheConfig.CACHE_CONTACTS, key = "#familyId")
    public void delete(String familyId, String id) throws ExecutionException, InterruptedException {
        requireInFamily(familyId, id);
        firestore.collection(COLLECTION).document(id).delete().get();
        log.info("Deleted contact {} from family {}", id, familyId);
    }

    private Contact requireInFamily(String familyId, String id) throws ExecutionException, InterruptedException {
        DocumentSnapshot snap = firestore.collection(COLLECTION).document(id).get().get();
        if (!snap.exists()) throw new IllegalArgumentException("Contact not found");
        Contact c = snap.toObject(Contact.class);
        if (c == null || !familyId.equals(c.getFamilyId())) {
            throw new IllegalArgumentException("Contact not found");
        }
        return c;
    }
}
