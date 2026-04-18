package com.ogautam.kinkeeper.controller;

import com.ogautam.kinkeeper.model.Category;
import com.ogautam.kinkeeper.model.Family;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.ogautam.kinkeeper.service.CategoryService;
import com.ogautam.kinkeeper.service.FamilyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;
    private final FamilyService familyService;

    public CategoryController(CategoryService categoryService, FamilyService familyService) {
        this.categoryService = categoryService;
        this.familyService = familyService;
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
}
