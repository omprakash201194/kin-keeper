package com.ogautam.kinkeeper.service;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.ogautam.kinkeeper.config.CacheConfig;
import com.ogautam.kinkeeper.model.Asset;
import com.ogautam.kinkeeper.model.AssetType;
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
public class AssetService {

    private static final String COLLECTION = "assets";

    private final Firestore firestore;

    public AssetService(Firestore firestore) {
        this.firestore = firestore;
    }

    @Cacheable(value = CacheConfig.CACHE_ASSETS, key = "#familyId")
    public List<Asset> listByFamily(String familyId) throws ExecutionException, InterruptedException {
        List<QueryDocumentSnapshot> docs = firestore.collection(COLLECTION)
                .whereEqualTo("familyId", familyId)
                .get().get()
                .getDocuments();
        List<Asset> out = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot d : docs) out.add(d.toObject(Asset.class));
        out.sort((a, b) -> {
            int t = a.getType().compareTo(b.getType());
            if (t != 0) return t;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        return out;
    }

    @CacheEvict(value = CacheConfig.CACHE_ASSETS, key = "#familyId")
    public Asset create(String familyId, Asset form) throws ExecutionException, InterruptedException {
        if (form.getType() == null) throw new IllegalArgumentException("asset type is required");
        if (form.getName() == null || form.getName().isBlank()) {
            throw new IllegalArgumentException("asset name is required");
        }
        DocumentReference ref = firestore.collection(COLLECTION).document();
        Asset a = Asset.builder()
                .id(ref.getId())
                .familyId(familyId)
                .type(form.getType())
                .name(form.getName().trim())
                .make(form.getMake())
                .model(form.getModel())
                .identifier(form.getIdentifier())
                .address(form.getAddress())
                .provider(form.getProvider())
                .purchaseDate(form.getPurchaseDate())
                .expiryDate(form.getExpiryDate())
                .frequency(form.getFrequency())
                .amount(form.getAmount())
                .odometerKm(form.getOdometerKm())
                .ownerMemberIds(form.getOwnerMemberIds())
                .linkedAssetIds(form.getLinkedAssetIds())
                .notes(form.getNotes())
                .createdAt(Instant.now())
                .build();
        ref.set(a).get();
        log.info("Created {} asset {} ({}) in family {}", a.getType(), ref.getId(), a.getName(), familyId);
        return a;
    }

    @CacheEvict(value = CacheConfig.CACHE_ASSETS, key = "#familyId")
    public Asset update(String familyId, String id, Map<String, Object> updates)
            throws ExecutionException, InterruptedException {
        Asset existing = requireInFamily(familyId, id);
        if (updates.isEmpty()) return existing;
        firestore.collection(COLLECTION).document(id).update(updates).get();
        return requireInFamily(familyId, id);
    }

    public Asset get(String familyId, String id) throws ExecutionException, InterruptedException {
        return requireInFamily(familyId, id);
    }

    @CacheEvict(value = CacheConfig.CACHE_ASSETS, key = "#familyId")
    public void delete(String familyId, String id) throws ExecutionException, InterruptedException {
        requireInFamily(familyId, id);
        firestore.collection(COLLECTION).document(id).delete().get();
        log.info("Deleted asset {} from family {}", id, familyId);
    }

    /** Called by ReminderService to update the odometer from a completion event. */
    @CacheEvict(value = CacheConfig.CACHE_ASSETS, key = "#familyId")
    public void updateOdometer(String familyId, String id, int km) throws ExecutionException, InterruptedException {
        Asset a = requireInFamily(familyId, id);
        if (a.getType() != AssetType.VEHICLE) {
            throw new IllegalArgumentException("Odometer only applies to vehicles");
        }
        firestore.collection(COLLECTION).document(id).update("odometerKm", km).get();
    }

    private Asset requireInFamily(String familyId, String id) throws ExecutionException, InterruptedException {
        DocumentSnapshot snap = firestore.collection(COLLECTION).document(id).get().get();
        if (!snap.exists()) throw new IllegalArgumentException("Asset not found");
        Asset a = snap.toObject(Asset.class);
        if (a == null || !familyId.equals(a.getFamilyId())) {
            throw new IllegalArgumentException("Asset not found");
        }
        return a;
    }
}
