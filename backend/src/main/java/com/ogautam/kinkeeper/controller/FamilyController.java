package com.ogautam.kinkeeper.controller;

import com.ogautam.kinkeeper.model.Family;
import com.ogautam.kinkeeper.model.FamilyMember;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.ogautam.kinkeeper.service.FamilyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
                                    @RequestBody Map<String, String> body) throws Exception {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        Family family = familyService.createFamily(principal, name);
        return ResponseEntity.ok(family);
    }

    @GetMapping
    public ResponseEntity<?> get(@AuthenticationPrincipal FirebaseUserPrincipal principal) throws Exception {
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) {
            return ResponseEntity.ok(Map.of("family", (Object) null));
        }
        return ResponseEntity.ok(family);
    }

    @PostMapping("/members")
    public ResponseEntity<?> addMember(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                       @RequestBody Map<String, String> body) throws Exception {
        String name = body.get("name");
        String relationship = body.get("relationship");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        FamilyMember member = familyService.addMember(principal, name, relationship);
        return ResponseEntity.ok(member);
    }

    @GetMapping("/members")
    public ResponseEntity<?> listMembers(@AuthenticationPrincipal FirebaseUserPrincipal principal) throws Exception {
        List<FamilyMember> members = familyService.listMembers(principal);
        return ResponseEntity.ok(members);
    }

    @PostMapping("/invite")
    public ResponseEntity<?> invite(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                    @RequestBody Map<String, String> body) {
        // TODO: invite another Google user to the family
        return ResponseEntity.ok(Map.of("status", "invited"));
    }
}
