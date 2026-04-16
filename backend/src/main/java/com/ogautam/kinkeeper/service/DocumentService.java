package com.ogautam.kinkeeper.service;

import com.google.cloud.firestore.Firestore;
import com.ogautam.kinkeeper.drive.DriveService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DocumentService {

    private static final String DOCUMENTS_COLLECTION = "documents";

    private final Firestore firestore;
    private final DriveService driveService;

    public DocumentService(Firestore firestore, DriveService driveService) {
        this.firestore = firestore;
        this.driveService = driveService;
    }

    // TODO: uploadDocument, getDocument, listDocuments, deleteDocument, searchDocuments
}
