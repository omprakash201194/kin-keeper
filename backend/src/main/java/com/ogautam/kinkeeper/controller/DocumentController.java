package com.ogautam.kinkeeper.controller;

import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.ogautam.kinkeeper.service.DocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                  @RequestParam(required = false) String memberId,
                                  @RequestParam(required = false) String categoryId) {
        // TODO: query documents from Firestore filtered by family, member, category
        return ResponseEntity.ok(Map.of("documents", java.util.List.of()));
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestParam String memberId,
                                    @RequestParam String categoryId,
                                    @RequestParam(required = false) String notes) {
        // TODO: upload file to Google Drive, store metadata in Firestore
        return ResponseEntity.ok(Map.of("status", "uploaded"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                 @PathVariable String id) {
        // TODO: get document metadata from Firestore
        return ResponseEntity.ok(Map.of("id", id));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                      @PathVariable String id) {
        // TODO: stream file from Google Drive
        return ResponseEntity.ok(Map.of("status", "not implemented"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                    @PathVariable String id) {
        // TODO: delete from Drive and Firestore
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }
}
