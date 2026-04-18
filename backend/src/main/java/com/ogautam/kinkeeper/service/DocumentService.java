package com.ogautam.kinkeeper.service;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.ogautam.kinkeeper.drive.DriveService;
import com.ogautam.kinkeeper.model.Document;
import com.ogautam.kinkeeper.model.Family;
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

    public DocumentService(Firestore firestore, DriveService driveService, FamilyService familyService) {
        this.firestore = firestore;
        this.driveService = driveService;
        this.familyService = familyService;
    }

    public Document uploadDocument(FirebaseUserPrincipal principal,
                                   String fileName,
                                   String mimeType,
                                   long fileSize,
                                   InputStream content,
                                   String memberId,
                                   String categoryId,
                                   String notes)
            throws ExecutionException, InterruptedException, IOException, GeneralSecurityException {
        Family family = requireFamily(principal);
        String driveFileId = driveService.uploadFile(family.getAdminUid(), fileName, mimeType, content);

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
                .uploadedBy(principal.uid())
                .uploadedAt(Instant.now())
                .build();
        ref.set(doc).get();
        log.info("Uploaded document {} ({}) for family {}", ref.getId(), fileName, family.getId());
        return doc;
    }

    public List<Document> listDocuments(FirebaseUserPrincipal principal, String memberId, String categoryId)
            throws ExecutionException, InterruptedException {
        Family family = requireFamily(principal);
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

    private Family requireFamily(FirebaseUserPrincipal principal)
            throws ExecutionException, InterruptedException {
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) {
            throw new IllegalArgumentException("User has no family");
        }
        return family;
    }
}
