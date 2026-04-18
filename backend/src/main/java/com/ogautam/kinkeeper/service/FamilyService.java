package com.ogautam.kinkeeper.service;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.ogautam.kinkeeper.model.Family;
import com.ogautam.kinkeeper.model.FamilyMember;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Slf4j
@Service
public class FamilyService {

    private static final String USERS_COLLECTION = "users";
    private static final String FAMILIES_COLLECTION = "families";
    private static final String MEMBERS_COLLECTION = "members";

    private final Firestore firestore;
    private final CategoryService categoryService;

    public FamilyService(Firestore firestore, CategoryService categoryService) {
        this.firestore = firestore;
        this.categoryService = categoryService;
    }

    public Family createFamily(FirebaseUserPrincipal principal, String name)
            throws ExecutionException, InterruptedException {
        String existingFamilyId = getUserFamilyId(principal.uid());
        if (existingFamilyId != null) {
            throw new IllegalArgumentException("User already belongs to family " + existingFamilyId);
        }

        DocumentReference ref = firestore.collection(FAMILIES_COLLECTION).document();
        Family family = Family.builder()
                .id(ref.getId())
                .name(name)
                .adminUid(principal.uid())
                .createdAt(Instant.now())
                .build();
        ref.set(family).get();

        firestore.collection(USERS_COLLECTION).document(principal.uid())
                .update(Map.of(
                        "familyId", ref.getId(),
                        "role", UserService.ROLE_ADMIN,
                        "updatedAt", Instant.now()
                )).get();

        categoryService.seedDefaults(ref.getId());
        seedAdminAsMember(ref.getId(), principal);

        log.info("Created family {} for admin {}", ref.getId(), principal.email());
        return family;
    }

    private void seedAdminAsMember(String familyId, FirebaseUserPrincipal principal)
            throws ExecutionException, InterruptedException {
        DocumentReference ref = firestore.collection(MEMBERS_COLLECTION).document();
        String displayName = principal.name() != null && !principal.name().isBlank()
                ? principal.name()
                : principal.email();
        FamilyMember self = FamilyMember.builder()
                .id(ref.getId())
                .familyId(familyId)
                .name(displayName)
                .relationship("Self")
                .addedBy(principal.uid())
                .createdAt(Instant.now())
                .build();
        ref.set(self).get();
    }

    public Family getFamilyForUser(String uid) throws ExecutionException, InterruptedException {
        String familyId = getUserFamilyId(uid);
        if (familyId == null) {
            return null;
        }
        DocumentSnapshot doc = firestore.collection(FAMILIES_COLLECTION)
                .document(familyId).get().get();
        return doc.exists() ? doc.toObject(Family.class) : null;
    }

    public FamilyMember addMember(FirebaseUserPrincipal principal, String name, String relationship)
            throws ExecutionException, InterruptedException {
        String familyId = requireFamilyId(principal);

        DocumentReference ref = firestore.collection(MEMBERS_COLLECTION).document();
        FamilyMember member = FamilyMember.builder()
                .id(ref.getId())
                .familyId(familyId)
                .name(name)
                .relationship(relationship)
                .addedBy(principal.uid())
                .createdAt(Instant.now())
                .build();
        ref.set(member).get();

        log.info("Added member {} ({}) to family {}", member.getId(), name, familyId);
        return member;
    }

    public List<FamilyMember> listMembers(FirebaseUserPrincipal principal)
            throws ExecutionException, InterruptedException {
        String familyId = getUserFamilyId(principal.uid());
        if (familyId == null) {
            return List.of();
        }
        List<QueryDocumentSnapshot> docs = firestore.collection(MEMBERS_COLLECTION)
                .whereEqualTo("familyId", familyId)
                .get().get()
                .getDocuments();

        List<FamilyMember> members = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot doc : docs) {
            members.add(doc.toObject(FamilyMember.class));
        }
        return members;
    }

    private String getUserFamilyId(String uid) throws ExecutionException, InterruptedException {
        DocumentSnapshot user = firestore.collection(USERS_COLLECTION).document(uid).get().get();
        if (!user.exists()) {
            return null;
        }
        Object familyId = user.get("familyId");
        return familyId != null ? familyId.toString() : null;
    }

    private String requireFamilyId(FirebaseUserPrincipal principal)
            throws ExecutionException, InterruptedException {
        String familyId = getUserFamilyId(principal.uid());
        if (familyId == null) {
            throw new IllegalArgumentException("User " + principal.email() + " has no family");
        }
        return familyId;
    }
}
