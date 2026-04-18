package com.ogautam.kinkeeper.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ogautam.kinkeeper.model.Conversation;
import com.ogautam.kinkeeper.model.Family;
import com.ogautam.kinkeeper.model.LinkType;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.ogautam.kinkeeper.service.ConversationService;
import com.ogautam.kinkeeper.service.FamilyService;
import com.ogautam.kinkeeper.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private static final List<String> EDITABLE_FIELDS = List.of(
            "title", "format", "channel", "occurredAt",
            "summary", "outcome", "followUp", "messages", "links");

    private final ConversationService conversationService;
    private final FamilyService familyService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public ConversationController(ConversationService conversationService,
                                  FamilyService familyService,
                                  UserService userService,
                                  ObjectMapper objectMapper) {
        this.conversationService = conversationService;
        this.familyService = familyService;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<List<Conversation>> list(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                                   @RequestParam(required = false) String query,
                                                   @RequestParam(required = false) String linkType,
                                                   @RequestParam(required = false) String linkId,
                                                   @RequestParam(required = false) String fromDate,
                                                   @RequestParam(required = false) String toDate) throws Exception {
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) return ResponseEntity.ok(Collections.emptyList());

        boolean hasFilter = query != null || linkType != null || linkId != null || fromDate != null || toDate != null;
        if (!hasFilter) {
            return ResponseEntity.ok(conversationService.listByFamily(family.getId()));
        }
        LinkType parsedType = linkType != null ? LinkType.valueOf(linkType.toUpperCase()) : null;
        Instant from = fromDate != null && !fromDate.isBlank() ? Instant.parse(fromDate) : null;
        Instant to = toDate != null && !toDate.isBlank() ? Instant.parse(toDate) : null;
        return ResponseEntity.ok(conversationService.search(family.getId(), query, parsedType, linkId, from, to));
    }

    @PostMapping
    public ResponseEntity<Conversation> create(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                               @RequestBody Map<String, Object> body) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = requireFamily(principal);
        Conversation form = objectMapper.convertValue(body, Conversation.class);
        return ResponseEntity.ok(conversationService.create(family.getId(), principal.uid(), form));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Conversation> update(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                               @PathVariable String id,
                                               @RequestBody Map<String, Object> body) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = requireFamily(principal);
        Map<String, Object> updates = new HashMap<>();
        for (String f : EDITABLE_FIELDS) {
            if (body.containsKey(f)) updates.put(f, body.get(f));
        }
        return ResponseEntity.ok(conversationService.update(family.getId(), id, updates));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                    @PathVariable String id) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = requireFamily(principal);
        conversationService.delete(family.getId(), id);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    private Family requireFamily(FirebaseUserPrincipal principal) throws Exception {
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) throw new IllegalArgumentException("User has no family");
        return family;
    }
}
