package com.ogautam.kinkeeper.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ogautam.kinkeeper.model.Family;
import com.ogautam.kinkeeper.model.Plan;
import com.ogautam.kinkeeper.model.PlanSegment;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.ogautam.kinkeeper.service.FamilyService;
import com.ogautam.kinkeeper.service.PlanService;
import com.ogautam.kinkeeper.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private static final List<String> EDITABLE_FIELDS = List.of(
            "name", "type", "startDate", "endDate", "destination",
            "notes", "segments", "links");

    private final PlanService planService;
    private final FamilyService familyService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public PlanController(PlanService planService,
                          FamilyService familyService,
                          UserService userService,
                          ObjectMapper objectMapper) {
        this.planService = planService;
        this.familyService = familyService;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<List<Plan>> list(@AuthenticationPrincipal FirebaseUserPrincipal principal) throws Exception {
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) return ResponseEntity.ok(Collections.emptyList());
        return ResponseEntity.ok(planService.listByFamily(family.getId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Plan> get(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                    @PathVariable String id) throws Exception {
        Family family = requireFamily(principal);
        return ResponseEntity.ok(planService.get(family.getId(), id));
    }

    @PostMapping
    public ResponseEntity<Plan> create(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                       @RequestBody Map<String, Object> body) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = requireFamily(principal);
        Plan form = objectMapper.convertValue(body, Plan.class);
        return ResponseEntity.ok(planService.create(family.getId(), principal.uid(), form));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Plan> update(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                       @PathVariable String id,
                                       @RequestBody Map<String, Object> body) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = requireFamily(principal);
        Map<String, Object> updates = new HashMap<>();
        for (String f : EDITABLE_FIELDS) {
            if (body.containsKey(f)) updates.put(f, body.get(f));
        }
        return ResponseEntity.ok(planService.update(family.getId(), id, updates));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                    @PathVariable String id) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = requireFamily(principal);
        planService.delete(family.getId(), id);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    @PostMapping("/{id}/segments")
    public ResponseEntity<Plan> addSegment(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                           @PathVariable String id,
                                           @RequestBody Map<String, Object> body) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = requireFamily(principal);
        PlanSegment segment = objectMapper.convertValue(body, PlanSegment.class);
        return ResponseEntity.ok(planService.addSegment(family.getId(), id, segment));
    }

    @PostMapping("/{id}/attach-document/{documentId}")
    public ResponseEntity<Plan> attachDocument(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                               @PathVariable String id,
                                               @PathVariable String documentId) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = requireFamily(principal);
        return ResponseEntity.ok(planService.linkDocument(family.getId(), id, documentId, principal));
    }

    private Family requireFamily(FirebaseUserPrincipal principal) throws Exception {
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) throw new IllegalArgumentException("User has no family");
        return family;
    }
}
