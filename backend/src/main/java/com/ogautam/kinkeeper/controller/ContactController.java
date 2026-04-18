package com.ogautam.kinkeeper.controller;

import com.ogautam.kinkeeper.model.Contact;
import com.ogautam.kinkeeper.model.Family;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.ogautam.kinkeeper.service.ContactService;
import com.ogautam.kinkeeper.service.FamilyService;
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
@RequestMapping("/api/contacts")
public class ContactController {

    private final ContactService contactService;
    private final FamilyService familyService;
    private final UserService userService;

    public ContactController(ContactService contactService, FamilyService familyService, UserService userService) {
        this.contactService = contactService;
        this.familyService = familyService;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<Contact>> list(@AuthenticationPrincipal FirebaseUserPrincipal principal) throws Exception {
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) return ResponseEntity.ok(Collections.emptyList());
        return ResponseEntity.ok(contactService.listByFamily(family.getId()));
    }

    @PostMapping
    public ResponseEntity<Contact> create(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                          @RequestBody Map<String, String> body) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = requireFamily(principal);
        return ResponseEntity.ok(contactService.create(
                family.getId(),
                body.get("name"),
                body.get("relationship"),
                body.get("phone"),
                body.get("email"),
                body.get("notes")));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Contact> update(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                          @PathVariable String id,
                                          @RequestBody Map<String, Object> body) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = requireFamily(principal);
        // reason: only forward known, editable fields to avoid Firestore noise from client-sent ids/familyId
        Map<String, Object> updates = new HashMap<>();
        for (String f : List.of("name", "relationship", "phone", "email", "notes")) {
            if (body.containsKey(f)) updates.put(f, body.get(f));
        }
        return ResponseEntity.ok(contactService.update(family.getId(), id, updates));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                    @PathVariable String id) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = requireFamily(principal);
        contactService.delete(family.getId(), id);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    private Family requireFamily(FirebaseUserPrincipal principal) throws Exception {
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) throw new IllegalArgumentException("User has no family");
        return family;
    }
}
