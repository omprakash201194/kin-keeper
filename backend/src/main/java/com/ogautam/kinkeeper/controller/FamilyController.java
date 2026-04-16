package com.ogautam.kinkeeper.controller;

import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.ogautam.kinkeeper.service.FamilyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/family")
public class FamilyController {

    private final FamilyService familyService;

    public FamilyController(FamilyService familyService) {
        this.familyService = familyService;
    }

    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                    @RequestBody Map<String, String> body) {
        // TODO: create family with principal as admin
        return ResponseEntity.ok(Map.of("status", "created"));
    }

    @GetMapping
    public ResponseEntity<?> get(@AuthenticationPrincipal FirebaseUserPrincipal principal) {
        // TODO: get user's family
        return ResponseEntity.ok(Map.of("family", Map.of()));
    }

    @PostMapping("/members")
    public ResponseEntity<?> addMember(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                       @RequestBody Map<String, String> body) {
        // TODO: add family member (person, not user)
        return ResponseEntity.ok(Map.of("status", "added"));
    }

    @GetMapping("/members")
    public ResponseEntity<?> listMembers(@AuthenticationPrincipal FirebaseUserPrincipal principal) {
        // TODO: list family members
        return ResponseEntity.ok(Map.of("members", java.util.List.of()));
    }

    @PostMapping("/invite")
    public ResponseEntity<?> invite(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                    @RequestBody Map<String, String> body) {
        // TODO: invite another Google user to the family
        return ResponseEntity.ok(Map.of("status", "invited"));
    }
}
