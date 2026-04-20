package com.ogautam.kinkeeper.service;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.ogautam.kinkeeper.config.CacheConfig;
import com.ogautam.kinkeeper.model.Asset;
import com.ogautam.kinkeeper.model.AssetType;
import com.ogautam.kinkeeper.model.Bill;
import com.ogautam.kinkeeper.model.BillSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Slf4j
@Service
public class BillService {

    private static final String COLLECTION = "bills";
    private static final String DEFAULT_CURRENCY = "INR";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final Firestore firestore;
    private final AssetService assetService;

    public BillService(Firestore firestore, AssetService assetService) {
        this.firestore = firestore;
        this.assetService = assetService;
    }

    @Cacheable(value = CacheConfig.CACHE_BILLS, key = "#familyId")
    public List<Bill> listByFamily(String familyId) throws ExecutionException, InterruptedException {
        List<QueryDocumentSnapshot> docs = firestore.collection(COLLECTION)
                .whereEqualTo("familyId", familyId)
                .get().get().getDocuments();
        List<Bill> out = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot d : docs) out.add(d.toObject(Bill.class));
        // Most recent due date first — that's what users and the agent want.
        out.sort(Comparator.comparing(
                (Bill b) -> b.getDueAt() == null ? Instant.MIN : b.getDueAt()).reversed());
        return out;
    }

    public List<Bill> listByAsset(String familyId, String assetId)
            throws ExecutionException, InterruptedException {
        List<Bill> out = new ArrayList<>();
        for (Bill b : listByFamily(familyId)) {
            if (assetId.equals(b.getAssetId())) out.add(b);
        }
        return out;
    }

    @CacheEvict(value = CacheConfig.CACHE_BILLS, key = "#familyId")
    public Bill create(String familyId, String createdByUid, Bill form)
            throws ExecutionException, InterruptedException {
        if (form.getAssetId() == null || form.getAssetId().isBlank()) {
            throw new IllegalArgumentException("assetId is required — bills must link to a POLICY asset");
        }
        // Authorise the link: the asset must exist in this family AND be a POLICY.
        // Non-POLICY assets (homes, vehicles) can have documents and reminders but
        // aren't the right home for recurring bills.
        Asset asset = assetService.get(familyId, form.getAssetId());
        if (asset == null || !familyId.equals(asset.getFamilyId())) {
            throw new IllegalArgumentException("Asset not found in this family");
        }
        if (asset.getType() != AssetType.POLICY) {
            throw new IllegalArgumentException(
                    "Bills can only be logged against POLICY assets (subscriptions / services). "
                            + "'" + asset.getName() + "' is a " + asset.getType() + ".");
        }
        if (form.getDueAt() == null) {
            throw new IllegalArgumentException("dueAt is required");
        }

        DocumentReference ref = firestore.collection(COLLECTION).document();
        Bill bill = Bill.builder()
                .id(ref.getId())
                .familyId(familyId)
                .assetId(form.getAssetId())
                .dueAt(form.getDueAt())
                .paidAt(form.getPaidAt())
                .amount(form.getAmount())
                .currency(form.getCurrency() != null && !form.getCurrency().isBlank()
                        ? form.getCurrency() : DEFAULT_CURRENCY)
                .source(form.getSource() != null ? form.getSource() : BillSource.MANUAL)
                .sourceText(form.getSourceText())
                .notes(form.getNotes())
                .createdBy(createdByUid)
                .createdAt(Instant.now())
                .build();
        ref.set(bill).get();
        log.info("Logged bill {} on asset {} amount={} {}",
                ref.getId(), bill.getAssetId(), bill.getAmount(), bill.getCurrency());

        // Bump the asset's expiryDate to whichever of its current value or this
        // bill's dueAt is later. That keeps "next renewal" accurate without
        // silently moving it backwards if the user logs an older bill as
        // history catch-up.
        maybeBumpAssetExpiry(familyId, asset, bill);
        return bill;
    }

    @CacheEvict(value = CacheConfig.CACHE_BILLS, key = "#familyId")
    public void delete(String familyId, String id) throws ExecutionException, InterruptedException {
        DocumentSnapshot snap = firestore.collection(COLLECTION).document(id).get().get();
        if (!snap.exists()) throw new IllegalArgumentException("Bill not found");
        Bill b = snap.toObject(Bill.class);
        if (b == null || !familyId.equals(b.getFamilyId())) {
            throw new IllegalArgumentException("Bill not found");
        }
        firestore.collection(COLLECTION).document(id).delete().get();
        log.info("Deleted bill {} from family {}", id, familyId);
    }

    /** Spend totals per asset this calendar month (for dashboard rollups). */
    public Map<String, BigDecimal> totalsByAssetThisMonth(String familyId)
            throws ExecutionException, InterruptedException {
        Instant now = Instant.now();
        LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
        Instant monthStart = today.withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Map<String, BigDecimal> out = new HashMap<>();
        for (Bill b : listByFamily(familyId)) {
            if (b.getDueAt() == null || b.getDueAt().isBefore(monthStart)) continue;
            if (b.getAmount() == null) continue;
            out.merge(b.getAssetId(), b.getAmount(), BigDecimal::add);
        }
        return out;
    }

    private void maybeBumpAssetExpiry(String familyId, Asset asset, Bill bill)
            throws ExecutionException, InterruptedException {
        String billDateIso = DATE_FMT.format(bill.getDueAt().atOffset(ZoneOffset.UTC).toLocalDate());
        String current = asset.getExpiryDate();
        if (current == null || current.isBlank() || billDateIso.compareTo(current) > 0) {
            Map<String, Object> updates = new HashMap<>();
            updates.put("expiryDate", billDateIso);
            assetService.update(familyId, asset.getId(), updates);
            log.info("Bumped asset {} expiryDate {} -> {} from new bill", asset.getId(), current, billDateIso);
        }
    }
}
