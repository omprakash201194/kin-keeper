package com.ogautam.kinkeeper.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ogautam.kinkeeper.model.Document;
import com.ogautam.kinkeeper.model.LinkRef;
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
    private final ObjectMapper objectMapper;

    public DocumentController(DocumentService documentService, UserService userService, ObjectMapper objectMapper) {
        this.documentService = documentService;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                  @RequestParam(required = false) String memberId,
                                  @RequestParam(required = false) String categoryId,
                                  @RequestParam(required = false) String label) throws Exception {
        List<Document> docs = label == null || label.isBlank()
                ? documentService.listDocuments(principal, memberId, categoryId)
                : documentService.searchDocuments(principal, null, memberId, categoryId, label);
        return ResponseEntity.ok(docs);
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestParam(required = false) String memberId,
                                    @RequestParam String categoryId,
                                    @RequestParam(required = false) String notes,
                                    @RequestParam(required = false) String labels,
                                    @RequestParam(required = false) String links) throws Exception {
        userService.requireAdmin(principal.uid());
        if (file.isEmpty()) {
            throw new IllegalArgumentException("file is empty");
        }
        List<LinkRef> parsedLinks = parseLinks(links);
        // Backward-compat: when memberId is passed, ensure it also appears in links.
        if (memberId != null && !memberId.isBlank()
                && parsedLinks.stream().noneMatch(l -> "MEMBER".equalsIgnoreCase(String.valueOf(l.getType()))
                        && memberId.equals(l.getId()))) {
            parsedLinks = new java.util.ArrayList<>(parsedLinks);
            parsedLinks.add(LinkRef.builder().type(com.ogautam.kinkeeper.model.LinkType.MEMBER).id(memberId).build());
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
                notes,
                parseLabels(labels),
                parsedLinks);
        return ResponseEntity.ok(doc);
    }

    /**
     * Bulk upload N files, each sharing the same member/category/labels/links.
     * Used by the Documents page "Upload all to same category" flow. Files that
     * fail individually are reported in `failed`; successes land in `documents`.
     */
    @PostMapping("/bulk-upload")
    public ResponseEntity<?> bulkUpload(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                        @RequestParam("files") List<MultipartFile> files,
                                        @RequestParam(required = false) String memberId,
                                        @RequestParam String categoryId,
                                        @RequestParam(required = false) String notes,
                                        @RequestParam(required = false) String labels,
                                        @RequestParam(required = false) String links) throws Exception {
        userService.requireAdmin(principal.uid());
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("files is empty");

        List<LinkRef> parsedLinks = parseLinks(links);
        if (memberId != null && !memberId.isBlank()
                && parsedLinks.stream().noneMatch(l -> "MEMBER".equalsIgnoreCase(String.valueOf(l.getType()))
                        && memberId.equals(l.getId()))) {
            parsedLinks = new java.util.ArrayList<>(parsedLinks);
            parsedLinks.add(LinkRef.builder().type(com.ogautam.kinkeeper.model.LinkType.MEMBER).id(memberId).build());
        }
        List<String> parsedLabels = parseLabels(labels);

        List<Document> saved = new java.util.ArrayList<>();
        List<Map<String, String>> failed = new java.util.ArrayList<>();
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            try {
                String mime = f.getContentType() != null ? f.getContentType() : "application/octet-stream";
                Document doc = documentService.uploadDocument(
                        principal, f.getOriginalFilename(), mime, f.getSize(), f.getInputStream(),
                        memberId, categoryId, notes, parsedLabels, parsedLinks);
                saved.add(doc);
            } catch (Exception ex) {
                log.warn("Bulk upload failed for {}: {}", f.getOriginalFilename(), ex.getMessage());
                failed.add(Map.of(
                        "fileName", f.getOriginalFilename() == null ? "" : f.getOriginalFilename(),
                        "error", ex.getMessage() == null ? "upload failed" : ex.getMessage()));
            }
        }
        return ResponseEntity.ok(Map.of("documents", saved, "failed", failed));
    }

    @PutMapping("/{id}/links")
    public ResponseEntity<?> updateLinks(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                         @PathVariable String id,
                                         @RequestBody Map<String, Object> body) throws Exception {
        userService.requireAdmin(principal.uid());
        Object raw = body.get("links");
        List<LinkRef> links = raw == null
                ? List.of()
                : objectMapper.convertValue(raw, new TypeReference<List<LinkRef>>() {});
        return ResponseEntity.ok(documentService.setLinks(principal, id, links));
    }

    private List<LinkRef> parseLinks(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<LinkRef>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("links must be a JSON array of {type, id}: " + e.getMessage());
        }
    }

    @PutMapping("/{id}/labels")
    public ResponseEntity<?> updateLabels(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                          @PathVariable String id,
                                          @RequestBody Map<String, Object> body) throws Exception {
        userService.requireAdmin(principal.uid());
        Object raw = body.get("labels");
        List<String> labels;
        if (raw instanceof List<?> list) {
            labels = list.stream().filter(v -> v != null).map(Object::toString).map(String::trim)
                    .filter(s -> !s.isBlank()).toList();
        } else if (raw instanceof String s) {
            labels = parseLabels(s);
        } else {
            labels = List.of();
        }
        return ResponseEntity.ok(documentService.setLabels(principal, id, labels));
    }

    private static List<String> parseLabels(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
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

    @PostMapping("/{id}/reindex")
    public ResponseEntity<?> reindex(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                     @PathVariable String id) throws Exception {
        userService.requireAdmin(principal.uid());
        return ResponseEntity.ok(documentService.reindexDocument(principal, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                    @PathVariable String id) throws Exception {
        userService.requireAdmin(principal.uid());
        documentService.deleteDocument(principal, id);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }
}
