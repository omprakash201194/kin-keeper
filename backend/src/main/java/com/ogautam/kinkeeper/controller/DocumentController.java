package com.ogautam.kinkeeper.controller;

import com.ogautam.kinkeeper.model.Document;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.ogautam.kinkeeper.service.DocumentService;
import com.ogautam.kinkeeper.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final UserService userService;

    public DocumentController(DocumentService documentService, UserService userService) {
        this.documentService = documentService;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                  @RequestParam(required = false) String memberId,
                                  @RequestParam(required = false) String categoryId) throws Exception {
        List<Document> docs = documentService.listDocuments(principal, memberId, categoryId);
        return ResponseEntity.ok(docs);
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestParam String memberId,
                                    @RequestParam String categoryId,
                                    @RequestParam(required = false) String notes) throws Exception {
        userService.requireAdmin(principal.uid());
        if (file.isEmpty()) {
            throw new IllegalArgumentException("file is empty");
        }
        String mimeType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        Document doc = documentService.uploadDocument(
                principal,
                file.getOriginalFilename(),
                mimeType,
                file.getSize(),
                file.getInputStream(),
                memberId,
                categoryId,
                notes);
        return ResponseEntity.ok(doc);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                 @PathVariable String id) throws Exception {
        Document doc = documentService.getDocument(principal, id);
        return ResponseEntity.ok(doc);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                      @PathVariable String id) throws Exception {
        Document doc = documentService.getDocument(principal, id);
        byte[] bytes = documentService.downloadDocument(principal, id);
        MediaType mediaType = doc.getMimeType() != null
                ? MediaType.parseMediaType(doc.getMimeType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + doc.getFileName().replace("\"", "") + "\"")
                .body(new ByteArrayResource(bytes));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                    @PathVariable String id) throws Exception {
        userService.requireAdmin(principal.uid());
        documentService.deleteDocument(principal, id);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }
}
