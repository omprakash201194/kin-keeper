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
        // reason: same fix pattern as ReminderController.update — normalise through
        // Jackson so BigDecimal and any future typed fields land as proper Java
        // objects in Firestore instead of raw JSON primitives.
        Asset typed = objectMapper.convertValue(body, Asset.class);
        Map<String, Object> updates = new HashMap<>();
        if (body.containsKey("name"))            updates.put("name",            typed.getName());
        if (body.containsKey("make"))            updates.put("make",            typed.getMake());
        if (body.containsKey("model"))           updates.put("model",           typed.getModel());
        if (body.containsKey("identifier"))      updates.put("identifier",      typed.getIdentifier());
        if (body.containsKey("address"))         updates.put("address",         typed.getAddress());
        if (body.containsKey("provider"))        updates.put("provider",        typed.getProvider());
        if (body.containsKey("purchaseDate"))    updates.put("purchaseDate",    typed.getPurchaseDate());
        if (body.containsKey("expiryDate"))      updates.put("expiryDate",      typed.getExpiryDate());
        if (body.containsKey("frequency"))       updates.put("frequency",       typed.getFrequency());
        if (body.containsKey("amount"))          updates.put("amount",          typed.getAmount());
        if (body.containsKey("odometerKm"))      updates.put("odometerKm",      typed.getOdometerKm());
        if (body.containsKey("ownerMemberIds"))  updates.put("ownerMemberIds",  typed.getOwnerMemberIds());
        if (body.containsKey("linkedAssetIds")) updates.put("linkedAssetIds", typed.getLinkedAssetIds());
        if (body.containsKey("notes"))           updates.put("notes",           typed.getNotes());
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
