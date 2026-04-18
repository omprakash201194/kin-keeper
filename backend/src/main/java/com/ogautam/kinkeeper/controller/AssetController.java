package com.ogautam.kinkeeper.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ogautam.kinkeeper.model.Asset;
import com.ogautam.kinkeeper.model.Family;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.ogautam.kinkeeper.service.AssetService;
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
@RequestMapping("/api/assets")
public class AssetController {

    /** Only these fields are writable through update; everything else is rejected silently. */
    private static final List<String> EDITABLE_FIELDS = List.of(
            "name", "make", "model", "identifier", "address", "provider",
            "purchaseDate", "expiryDate", "frequency", "amount", "odometerKm",
            "ownerMemberIds", "linkedAssetIds", "notes");

    private final AssetService assetService;
    private final FamilyService familyService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public AssetController(AssetService assetService, FamilyService familyService,
                           UserService userService, ObjectMapper objectMapper) {
        this.assetService = assetService;
        this.familyService = familyService;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<List<Asset>> list(@AuthenticationPrincipal FirebaseUserPrincipal principal) throws Exception {
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) return ResponseEntity.ok(Collections.emptyList());
        return ResponseEntity.ok(assetService.listByFamily(family.getId()));
    }

    @PostMapping
    public ResponseEntity<Asset> create(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                        @RequestBody Map<String, Object> body) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = requireFamily(principal);
        Asset form = objectMapper.convertValue(body, Asset.class);
        return ResponseEntity.ok(assetService.create(family.getId(), form));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Asset> update(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                        @PathVariable String id,
                                        @RequestBody Map<String, Object> body) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = requireFamily(principal);
        Map<String, Object> updates = new HashMap<>();
        for (String f : EDITABLE_FIELDS) {
            if (body.containsKey(f)) updates.put(f, body.get(f));
        }
        return ResponseEntity.ok(assetService.update(family.getId(), id, updates));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                    @PathVariable String id) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = requireFamily(principal);
        assetService.delete(family.getId(), id);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    private Family requireFamily(FirebaseUserPrincipal principal) throws Exception {
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) throw new IllegalArgumentException("User has no family");
        return family;
    }
}
