package com.ogautam.kinkeeper.agent;

import com.ogautam.kinkeeper.service.DocumentService;
import com.ogautam.kinkeeper.service.FamilyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class KinKeeperAgent {

    private final DocumentService documentService;
    private final FamilyService familyService;

    public KinKeeperAgent(DocumentService documentService, FamilyService familyService) {
        this.documentService = documentService;
        this.familyService = familyService;
    }

    // TODO: Implement Claude tool-use chat loop
    // - Accept user message + optional file + user's Claude API key
    // - Define tools: search_documents, upload_document, list_members, list_categories, get_document, recategorize_document
    // - Send to Claude API with tool definitions
    // - Execute tool calls against DocumentService/FamilyService
    // - Return final response
}
