package com.ogautam.kinkeeper.service;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.WriteBatch;
import com.ogautam.kinkeeper.model.Category;
import lombok.extern.slf4j.Slf4j;
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
}
