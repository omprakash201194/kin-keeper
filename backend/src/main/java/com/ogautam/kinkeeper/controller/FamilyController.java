package com.ogautam.kinkeeper.controller;

import com.ogautam.kinkeeper.model.Family;
import com.ogautam.kinkeeper.model.FamilyMember;
import com.ogautam.kinkeeper.model.Invite;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.ogautam.kinkeeper.service.FamilyService;
import com.ogautam.kinkeeper.service.InviteService;
import com.ogautam.kinkeeper.service.UserService;

import java.util.List;
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
    private final InviteService inviteService;
    private final UserService userService;

    public FamilyController(FamilyService familyService,
                            InviteService inviteService,
                            UserService userService) {
        this.familyService = familyService;
        this.inviteService = inviteService;
        this.userService = userService;
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
        userService.requireAdmin(principal.uid());
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
                                    @RequestBody Map<String, String> body) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) {
            throw new IllegalArgumentException("User has no family");
        }
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email is required");
        }
        String role = body.getOrDefault("role", UserService.ROLE_VIEWER);
        Invite invite = inviteService.createInvite(family.getId(), email, role, principal.uid());
        return ResponseEntity.ok(invite);
    }

    @GetMapping("/invites")
    public ResponseEntity<?> listInvites(@AuthenticationPrincipal FirebaseUserPrincipal principal) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(inviteService.listPending(family.getId()));
    }

    @DeleteMapping("/invites/{inviteId}")
    public ResponseEntity<?> cancelInvite(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                          @PathVariable String inviteId) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) {
            throw new IllegalArgumentException("User has no family");
        }
        inviteService.cancel(inviteId, family.getId());
        return ResponseEntity.ok(Map.of("status", "canceled"));
    }
}
