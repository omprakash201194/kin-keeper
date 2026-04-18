package com.ogautam.kinkeeper.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ogautam.kinkeeper.model.Family;
import com.ogautam.kinkeeper.model.Reminder;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.ogautam.kinkeeper.service.FamilyService;
import com.ogautam.kinkeeper.service.ReminderService;
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
@RequestMapping("/api/reminders")
public class ReminderController {

    private static final List<String> EDITABLE_FIELDS = List.of(
            "title", "notes", "dueAt", "recurrence", "recurrenceIntervalKm",
            "dueOdometerKm", "linkedRefs", "completed");

    private final ReminderService reminderService;
    private final FamilyService familyService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public ReminderController(ReminderService reminderService, FamilyService familyService,
                              UserService userService, ObjectMapper objectMapper) {
        this.reminderService = reminderService;
        this.familyService = familyService;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<List<Reminder>> list(@AuthenticationPrincipal FirebaseUserPrincipal principal) throws Exception {
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) return ResponseEntity.ok(Collections.emptyList());
        return ResponseEntity.ok(reminderService.listByFamily(family.getId()));
    }

    @GetMapping("/count")
    public ResponseEntity<?> countDueSoon(@AuthenticationPrincipal FirebaseUserPrincipal principal) throws Exception {
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) return ResponseEntity.ok(Map.of("dueSoon", 0));
        return ResponseEntity.ok(Map.of("dueSoon", reminderService.countDueSoon(family.getId())));
    }

    @PostMapping
    public ResponseEntity<Reminder> create(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                           @RequestBody Map<String, Object> body) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = requireFamily(principal);
        Reminder form = objectMapper.convertValue(body, Reminder.class);
        return ResponseEntity.ok(reminderService.create(family.getId(), principal.uid(), form));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Reminder> update(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                           @PathVariable String id,
                                           @RequestBody Map<String, Object> body) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = requireFamily(principal);
        Map<String, Object> updates = new HashMap<>();
        for (String f : EDITABLE_FIELDS) {
            if (body.containsKey(f)) updates.put(f, body.get(f));
        }
        return ResponseEntity.ok(reminderService.update(family.getId(), id, updates));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<Reminder> complete(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                             @PathVariable String id) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = requireFamily(principal);
        return ResponseEntity.ok(reminderService.complete(family.getId(), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                    @PathVariable String id) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = requireFamily(principal);
        reminderService.delete(family.getId(), id);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    private Family requireFamily(FirebaseUserPrincipal principal) throws Exception {
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) throw new IllegalArgumentException("User has no family");
        return family;
    }
}
