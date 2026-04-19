package com.ogautam.kinkeeper.service;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.ogautam.kinkeeper.drive.DriveService;
import com.ogautam.kinkeeper.model.Category;
import com.ogautam.kinkeeper.model.Document;
import com.ogautam.kinkeeper.model.Family;
import com.ogautam.kinkeeper.model.FamilyMember;
import com.ogautam.kinkeeper.model.LinkRef;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

@Slf4j
@Service
public class DocumentService {

    private static final String DOCUMENTS_COLLECTION = "documents";

    private final Firestore firestore;
    private final DriveService driveService;
    private final FamilyService familyService;
    private final CategoryService categoryService;

    public DocumentService(Firestore firestore, DriveService driveService,
                           FamilyService familyService, CategoryService categoryService) {
        this.firestore = firestore;
        this.driveService = driveService;
        this.familyService = familyService;
        this.categoryService = categoryService;
    }

    public Document uploadDocument(FirebaseUserPrincipal principal,
                                   String fileName,
                                   String mimeType,
                                   long fileSize,
                                   InputStream content,
                                   String memberId,
                                   String categoryId,
                                   String notes,
                                   List<String> labels,
                                   List<LinkRef> links)
            throws ExecutionException, InterruptedException, IOException, GeneralSecurityException {
        Family family = requireFamily(principal);
        List<String> pathSegments = folderPathFor(family.getId(), memberId, categoryId);
        String driveFileId = driveService.uploadFile(
                family.getAdminUid(), fileName, mimeType, content, pathSegments);

        DocumentReference ref = firestore.collection(DOCUMENTS_COLLECTION).document();
        Document doc = Document.builder()
                .id(ref.getId())
                .familyId(family.getId())
                .memberId(memberId)
                .categoryId(categoryId)
                .fileName(fileName)
                .mimeType(mimeType)
                .fileSize(fileSize)
                .driveFileId(driveFileId)
                .notes(notes)
                .tags(labels != null ? labels : List.of())
                .links(links != null ? links : List.of())
                .uploadedBy(principal.uid())
                .uploadedAt(Instant.now())
                .build();
        ref.set(doc).get();
        log.info("Uploaded document {} ({}) for family {} labels={}", ref.getId(), fileName, family.getId(), doc.getTags());
        return doc;
    }

    public Document setLinks(FirebaseUserPrincipal principal, String id, List<LinkRef> links)
            throws ExecutionException, InterruptedException {
        Document doc = loadAndAuthorize(principal, id);
        List<LinkRef> normalized = links != null ? links : List.of();
        firestore.collection(DOCUMENTS_COLLECTION).document(id).update("links", normalized).get();
        doc.setLinks(normalized);
        log.info("Updated links on document {} to {} entries", id, normalized.size());
        return doc;
    }

    public Document setLabels(FirebaseUserPrincipal principal, String id, List<String> labels)
            throws ExecutionException, InterruptedException {
        Document doc = loadAndAuthorize(principal, id);
        List<String> normalized = labels != null ? labels : List.of();
        firestore.collection(DOCUMENTS_COLLECTION).document(id).update("tags", normalized).get();
        doc.setTags(normalized);
        log.info("Updated labels on document {} to {}", id, normalized);
        return doc;
    }

    public List<Document> listDocuments(FirebaseUserPrincipal principal, String memberId, String categoryId)
            throws ExecutionException, InterruptedException {
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) {
            return List.of();
        }
        Query query = firestore.collection(DOCUMENTS_COLLECTION)
                .whereEqualTo("familyId", family.getId());
        if (memberId != null && !memberId.isBlank()) {
            query = query.whereEqualTo("memberId", memberId);
        }
        if (categoryId != null && !categoryId.isBlank()) {
            query = query.whereEqualTo("categoryId", categoryId);
        }
        List<QueryDocumentSnapshot> docs = query.get().get().getDocuments();
        List<Document> out = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot d : docs) {
            out.add(d.toObject(Document.class));
        }
        return out;
    }

    public Document getDocument(FirebaseUserPrincipal principal, String id)
            throws ExecutionException, InterruptedException {
        Document doc = loadAndAuthorize(principal, id);
        return doc;
    }

    public byte[] downloadDocument(FirebaseUserPrincipal principal, String id)
            throws ExecutionException, InterruptedException, IOException, GeneralSecurityException {
        Document doc = loadAndAuthorize(principal, id);
        Family family = familyService.getFamilyForUser(principal.uid());
        return driveService.downloadFile(family.getAdminUid(), doc.getDriveFileId());
    }

    public List<Document> searchDocuments(FirebaseUserPrincipal principal,
                                          String query,
                                          String memberId,
                                          String categoryId,
                                          String label)
            throws ExecutionException, InterruptedException {
        List<Document> all = listDocuments(principal, memberId, categoryId);
        if (label != null && !label.isBlank()) {
            String labelNeedle = label.toLowerCase();
            all.removeIf(d -> !tagsContain(d.getTags(), labelNeedle));
        }
        if (query == null || query.isBlank()) {
            return all;
        }
        String needle = query.toLowerCase();
        List<Document> hits = new ArrayList<>();
        for (Document d : all) {
            if (contains(d.getFileName(), needle)
                    || contains(d.getNotes(), needle)
                    || tagsContain(d.getTags(), needle)) {
                hits.add(d);
            }
        }
        return hits;
    }

    public Document recategorizeDocument(FirebaseUserPrincipal principal, String id, String newCategoryId)
            throws ExecutionException, InterruptedException {
        Document doc = loadAndAuthorize(principal, id);
        if (newCategoryId == null || newCategoryId.isBlank()) {
            throw new IllegalArgumentException("categoryId is required");
        }
        firestore.collection(DOCUMENTS_COLLECTION).document(id)
                .update("categoryId", newCategoryId).get();
        doc.setCategoryId(newCategoryId);
        log.info("Recategorized document {} to {}", id, newCategoryId);
        return doc;
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle);
    }

    private static boolean tagsContain(List<String> tags, String needle) {
        if (tags == null) return false;
        for (String t : tags) {
            if (t != null && t.toLowerCase().contains(needle)) return true;
        }
        return false;
    }

    public void deleteDocument(FirebaseUserPrincipal principal, String id)
            throws ExecutionException, InterruptedException, IOException, GeneralSecurityException {
        Document doc = loadAndAuthorize(principal, id);
        Family family = familyService.getFamilyForUser(principal.uid());
        try {
            driveService.deleteFile(family.getAdminUid(), doc.getDriveFileId());
        } catch (Exception e) {
            // reason: metadata deletion should still proceed if the underlying Drive file is already gone
            log.warn("Drive delete failed for file {}: {}", doc.getDriveFileId(), e.getMessage());
        }
        firestore.collection(DOCUMENTS_COLLECTION).document(id).delete().get();
        log.info("Deleted document {} from family {}", id, family.getId());
    }

    private Document loadAndAuthorize(FirebaseUserPrincipal principal, String id)
            throws ExecutionException, InterruptedException {
        Family family = requireFamily(principal);
        DocumentSnapshot snap = firestore.collection(DOCUMENTS_COLLECTION).document(id).get().get();
        if (!snap.exists()) {
            throw new IllegalArgumentException("Document not found");
        }
        Document doc = snap.toObject(Document.class);
        if (doc == null || !family.getId().equals(doc.getFamilyId())) {
            throw new IllegalArgumentException("Document not found");
        }
        return doc;
    }

    /**
     * Builds the Drive folder path for a new upload so files land in
     *   Kin-Keeper/{memberName or _Shared}/{categoryName or _Uncategorized}/file
     * instead of a single flat folder. Bulletproofed against missing lookups so
     * upload never fails just because a member was deleted mid-flow.
     */
    private List<String> folderPathFor(String familyId, String memberId, String categoryId)
            throws ExecutionException, InterruptedException {
        String memberName = null;
        if (memberId != null && !memberId.isBlank()) {
            try {
                var snap = firestore.collection("members").document(memberId).get().get();
                if (snap.exists()) {
                    FamilyMember m = snap.toObject(FamilyMember.class);
                    if (m != null && familyId.equals(m.getFamilyId())) {
                        memberName = m.getName();
                    }
                }
            } catch (Exception ignored) { /* fall through */ }
        }
        String categoryName = null;
        if (categoryId != null && !categoryId.isBlank()) {
            for (Category c : categoryService.listByFamily(familyId)) {
                if (categoryId.equals(c.getId())) {
                    categoryName = c.getName();
                    break;
                }
            }
        }
        List<String> segments = new ArrayList<>(2);
        segments.add(memberName != null ? memberName : "_Shared");
        segments.add(categoryName != null ? categoryName : "_Uncategorized");
        return segments;
    }

    private Family requireFamily(FirebaseUserPrincipal principal)
            throws ExecutionException, InterruptedException {
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) {
            throw new IllegalArgumentException("User has no family");
        }
        return family;
    }
}
