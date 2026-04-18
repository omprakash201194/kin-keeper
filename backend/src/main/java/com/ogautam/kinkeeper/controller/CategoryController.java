package com.ogautam.kinkeeper.controller;

import com.ogautam.kinkeeper.model.Category;
import com.ogautam.kinkeeper.model.Family;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.ogautam.kinkeeper.service.CategoryService;
import com.ogautam.kinkeeper.service.FamilyService;
import com.ogautam.kinkeeper.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;
    private final FamilyService familyService;
    private final UserService userService;

    public CategoryController(CategoryService categoryService, FamilyService familyService, UserService userService) {
        this.categoryService = categoryService;
        this.familyService = familyService;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal FirebaseUserPrincipal principal) throws Exception {
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) {
            return ResponseEntity.ok(Collections.<Category>emptyList());
        }
        List<Category> categories = categoryService.listByFamily(family.getId());
        return ResponseEntity.ok(categories);
    }

    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                    @RequestBody Map<String, String> body) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) {
            throw new IllegalArgumentException("User has no family");
        }
        Category created = categoryService.create(family.getId(), body.get("name"), body.get("parentId"));
        return ResponseEntity.ok(created);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                    @PathVariable String id) throws Exception {
        userService.requireAdmin(principal.uid());
        Family family = familyService.getFamilyForUser(principal.uid());
        if (family == null) {
            throw new IllegalArgumentException("User has no family");
        }
        categoryService.delete(family.getId(), id);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }
}
