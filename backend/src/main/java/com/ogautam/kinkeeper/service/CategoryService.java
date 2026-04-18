package com.ogautam.kinkeeper.service;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.WriteBatch;
import com.ogautam.kinkeeper.config.CacheConfig;
import com.ogautam.kinkeeper.model.Category;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

@Slf4j
@Service
public class CategoryService {

    private static final String CATEGORIES_COLLECTION = "categories";
    private static final List<String> DEFAULT_CATEGORIES =
            List.of("Government ID", "Medical", "Education", "Financial", "Legal");

    private final Firestore firestore;

    public CategoryService(Firestore firestore) {
        this.firestore = firestore;
    }

    @CacheEvict(value = CacheConfig.CACHE_CATEGORIES, key = "#familyId")
    public void seedDefaults(String familyId) throws ExecutionException, InterruptedException {
        WriteBatch batch = firestore.batch();
        for (String name : DEFAULT_CATEGORIES) {
            DocumentReference ref = firestore.collection(CATEGORIES_COLLECTION).document();
            Category category = Category.builder()
                    .id(ref.getId())
                    .familyId(familyId)
                    .name(name)
                    .isDefault(true)
                    .build();
            batch.set(ref, category);
        }
        batch.commit().get();
        log.info("Seeded {} default categories for family {}", DEFAULT_CATEGORIES.size(), familyId);
    }

    @Cacheable(value = CacheConfig.CACHE_CATEGORIES, key = "#familyId")
    public List<Category> listByFamily(String familyId) throws ExecutionException, InterruptedException {
        List<QueryDocumentSnapshot> docs = firestore.collection(CATEGORIES_COLLECTION)
                .whereEqualTo("familyId", familyId)
                .get().get()
                .getDocuments();
        List<Category> out = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot doc : docs) {
            out.add(doc.toObject(Category.class));
        }
        return out;
    }

    @CacheEvict(value = CacheConfig.CACHE_CATEGORIES, key = "#familyId")
    public Category create(String familyId, String name, String parentId)
            throws ExecutionException, InterruptedException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Category name is required");
        }
        if (parentId != null && !parentId.isBlank()) {
            DocumentSnapshot parent = firestore.collection(CATEGORIES_COLLECTION)
                    .document(parentId).get().get();
            if (!parent.exists() || !familyId.equals(parent.get("familyId"))) {
                throw new IllegalArgumentException("Parent category not found");
            }
        } else {
            parentId = null;
        }

        DocumentReference ref = firestore.collection(CATEGORIES_COLLECTION).document();
        Category category = Category.builder()
                .id(ref.getId())
                .familyId(familyId)
                .name(name.trim())
                .parentId(parentId)
                .isDefault(false)
                .build();
        ref.set(category).get();
        log.info("Created category {} ({}) in family {} parent={}", ref.getId(), name, familyId, parentId);
        return category;
    }

    @CacheEvict(value = CacheConfig.CACHE_CATEGORIES, key = "#familyId")
    public void delete(String familyId, String categoryId) throws ExecutionException, InterruptedException {
        DocumentSnapshot snap = firestore.collection(CATEGORIES_COLLECTION).document(categoryId).get().get();
        if (!snap.exists()) {
            throw new IllegalArgumentException("Category not found");
        }
        Category cat = snap.toObject(Category.class);
        if (cat == null || !familyId.equals(cat.getFamilyId())) {
            throw new IllegalArgumentException("Category not found");
        }
        if (cat.isDefault()) {
            throw new IllegalArgumentException("Default categories cannot be deleted");
        }

        // reason: orphaning child categories confuses the UI — require empty before delete
        long childCount = firestore.collection(CATEGORIES_COLLECTION)
                .whereEqualTo("familyId", familyId)
                .whereEqualTo("parentId", categoryId)
                .get().get().size();
        if (childCount > 0) {
            throw new IllegalArgumentException("Delete or move the sub-categories first");
        }
        long docCount = firestore.collection("documents")
                .whereEqualTo("familyId", familyId)
                .whereEqualTo("categoryId", categoryId)
                .get().get().size();
        if (docCount > 0) {
            throw new IllegalArgumentException("Move documents out of this category first (" + docCount + " still assigned)");
        }

        firestore.collection(CATEGORIES_COLLECTION).document(categoryId).delete().get();
        log.info("Deleted category {} from family {}", categoryId, familyId);
    }
}
