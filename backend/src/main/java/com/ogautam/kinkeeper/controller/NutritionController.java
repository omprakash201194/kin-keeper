package com.ogautam.kinkeeper.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ogautam.kinkeeper.model.Family;
import com.ogautam.kinkeeper.model.NutritionEntry;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.ogautam.kinkeeper.service.FamilyService;
import com.ogautam.kinkeeper.service.NutritionService;
import com.ogautam.kinkeeper.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/nutrition")
public class NutritionController {

    private final NutritionService nutritionService;
    private final FamilyService familyService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public NutritionController(NutritionService nutritionService,
                               FamilyService familyService,
                               UserService userService,
                               ObjectMapper objectMapper) {
        this.nutritionService = nutritionService;
        this.familyService = familyService;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<List<NutritionEntry>> list(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                                     @RequestParam(required = false) String memberId,
                                                     @RequestParam(required = false) String fromDate,
                                                     @RequestParam(required = false) String toDate) throws Exception {
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) return ResponseEntity.ok(Collections.emptyList());
        Instant from = fromDate != null && !fromDate.isBlank() ? Instant.parse(fromDate) : null;
        Instant to = toDate != null && !toDate.isBlank() ? Instant.parse(toDate) : null;
        if (memberId == null && from == null && to == null) {
            return ResponseEntity.ok(nutritionService.listByFamily(family.getId()));
        }
        return ResponseEntity.ok(nutritionService.search(family.getId(), memberId, from, to));
    }

    /**
     * Analyze a food/drink photo WITHOUT persisting. Used by the preview step
     * so the user can see what Claude saw before confirming the save.
     */
    @PostMapping("/analyze")
    public ResponseEntity<NutritionEntry> analyze(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                                  @RequestParam("file") MultipartFile file) throws Exception {
        if (file.isEmpty()) throw new IllegalArgumentException("file is empty");
        NutritionEntry draft = nutritionService.analyze(principal.uid(), file.getBytes(), file.getContentType());
        return ResponseEntity.ok(draft);
    }

    /**
     * Save an analyzed entry (or a manually-entered one). Body carries the
     * draft entity plus at minimum memberId + consumedAt.
     */
    @PostMapping
    public ResponseEntity<NutritionEntry> save(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                               @RequestBody Map<String, Object> body) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = requireFamily(principal);
        NutritionEntry form = objectMapper.convertValue(body, NutritionEntry.class);
        return ResponseEntity.ok(nutritionService.save(family.getId(), principal.uid(), form));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                    @PathVariable String id) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = requireFamily(principal);
        nutritionService.delete(family.getId(), id);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    private Family requireFamily(FirebaseUserPrincipal principal) throws Exception {
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) throw new IllegalArgumentException("User has no family");
        return family;
    }
}
