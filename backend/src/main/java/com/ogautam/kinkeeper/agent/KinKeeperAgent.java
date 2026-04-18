package com.ogautam.kinkeeper.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ogautam.kinkeeper.model.Document;
import com.ogautam.kinkeeper.model.Family;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.ogautam.kinkeeper.service.CategoryService;
import com.ogautam.kinkeeper.service.ChatSessionService;
import com.ogautam.kinkeeper.service.DocumentService;
import com.ogautam.kinkeeper.service.FamilyService;
import com.ogautam.kinkeeper.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class KinKeeperAgent {

    private static final int MAX_ITERATIONS = 8;
    private static final String SYSTEM_PROMPT = """
            You are Kin-Keeper, the assistant for a family document vault. You help users
            find, review, and organize documents stored in their family vault.

            Available tools:
              - search_documents, get_document, list_members, list_categories
              - recategorize_document
              - create_category (when the user wants a category that doesn't exist yet;
                optional parentId for nesting — e.g. "Books" under "Education")
              - add_member (add a new person to the family)
              - save_attachment (save the currently-attached file to the vault)

            Keep answers brief. Never fabricate document IDs, filenames, member or
            category IDs — always fetch them with the appropriate list_ tool first.

            When the user attaches an image, inspect it to figure out what kind of
            document it is and extract anything useful for labels (city, issuer,
            account holder). Then list members and categories, pick the best fit, and
            call save_attachment. If no category fits well, it is fine to call
            create_category first and then save_attachment with the new categoryId.
            Keep labels short and ≤3.

            The attachmentId for the currently-attached file appears in the
            [Attachment: ... attachmentId=...] tag on the user's message. When the user
            continues the conversation about the same attachment without re-attaching,
            the same attachmentId is still valid — reuse it.
            """;

    private final DocumentService documentService;
    private final FamilyService familyService;
    private final CategoryService categoryService;
    private final UserService userService;
    private final AttachmentService attachmentService;
    private final ChatSessionService chatSessionService;
    private final String defaultModel;
    // reason: Firestore POJOs (FamilyMember, Document) carry java.time.Instant —
    // without JavaTimeModule, writeValueAsString fails with "Java 8 date/time type not supported".
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public KinKeeperAgent(DocumentService documentService,
                          FamilyService familyService,
                          CategoryService categoryService,
                          UserService userService,
                          AttachmentService attachmentService,
                          ChatSessionService chatSessionService,
                          @Value("${anthropic.default-model:claude-sonnet-4-6}") String defaultModel) {
        this.documentService = documentService;
        this.familyService = familyService;
        this.categoryService = categoryService;
        this.userService = userService;
        this.attachmentService = attachmentService;
        this.chatSessionService = chatSessionService;
        this.defaultModel = defaultModel;
    }

    public ChatReply chat(FirebaseUserPrincipal principal,
                          List<ChatTurn> history,
                          String userMessage,
                          String attachmentId,
                          String sessionId) throws Exception {
        String apiKey = userService.getApiKey(principal.uid());
        if (apiKey == null) {
            throw new IllegalArgumentException("No Claude API key saved. Add one in Settings.");
        }

        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();

        List<MessageParam> messages = new ArrayList<>();
        if (history != null) {
            for (ChatTurn turn : history) {
                messages.add(MessageParam.builder()
                        .role("user".equalsIgnoreCase(turn.role()) ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT)
                        .content(turn.text())
                        .build());
            }
        }

        // If an attachment is present, stitch text + image into a single user turn.
        if (attachmentId != null && !attachmentId.isBlank()) {
            AttachmentService.Loaded att = attachmentService.load(attachmentId, principal.uid());
            Base64ImageSource.MediaType mediaType = imageMediaTypeFor(att.mimeType());
            if (mediaType == null) {
                // Non-image — let the model know a file was attached but we can't analyze it inline.
                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(userMessage
                                + "\n\n[Attachment: " + att.fileName() + " (" + att.mimeType()
                                + ", " + att.size() + " bytes, attachmentId=" + att.id() + ")]")
                        .build());
            } else {
                String base64 = Base64.getEncoder().encodeToString(att.bytes());
                ImageBlockParam imageBlock = ImageBlockParam.builder()
                        .source(Base64ImageSource.builder()
                                .data(base64)
                                .mediaType(mediaType)
                                .build())
                        .build();
                List<ContentBlockParam> parts = new ArrayList<>();
                parts.add(ContentBlockParam.ofImage(imageBlock));
                parts.add(ContentBlockParam.ofText(TextBlockParam.builder()
                        .text(userMessage
                                + "\n\n[Attachment: " + att.fileName()
                                + ", attachmentId=" + att.id() + "]")
                        .build()));
                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(parts)
                        .build());
            }
        } else {
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(userMessage)
                    .build());
        }

        List<Tool> tools = buildTools();

        StringBuilder finalText = new StringBuilder();

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                    .model(defaultModel)
                    .maxTokens(4096)
                    .system(SYSTEM_PROMPT);
            for (Tool t : tools) {
                paramsBuilder.addTool(t);
            }
            for (MessageParam m : messages) {
                paramsBuilder.addMessage(m);
            }

            Message response = client.messages().create(paramsBuilder.build());

            List<ContentBlockParam> assistantBlocks = new ArrayList<>();
            List<ContentBlockParam> toolResults = new ArrayList<>();
            String turnText = collectText(response);
            if (!turnText.isBlank()) {
                if (finalText.length() > 0) finalText.append("\n");
                finalText.append(turnText);
            }

            for (ContentBlock block : response.content()) {
                if (block.text().isPresent()) {
                    assistantBlocks.add(ContentBlockParam.ofText(
                            TextBlockParam.builder().text(block.text().get().text()).build()));
                } else if (block.toolUse().isPresent()) {
                    ToolUseBlock tu = block.toolUse().get();
                    assistantBlocks.add(ContentBlockParam.ofToolUse(
                            ToolUseBlockParam.builder()
                                    .id(tu.id())
                                    .name(tu.name())
                                    .input(tu._input())
                                    .build()));
                    String result = runTool(principal, tu, sessionId);
                    toolResults.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(tu.id())
                                    .content(result)
                                    .build()));
                }
            }

            if (!assistantBlocks.isEmpty()) {
                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .contentOfBlockParams(assistantBlocks)
                        .build());
            }

            if (toolResults.isEmpty()) {
                break;
            }
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(toolResults)
                    .build());
        }

        return new ChatReply(finalText.toString().strip());
    }

    private String collectText(Message response) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(tb -> {
                if (sb.length() > 0) sb.append("\n");
                sb.append(tb.text());
            });
        }
        return sb.toString();
    }

    private String runTool(FirebaseUserPrincipal principal, ToolUseBlock tu, String sessionId) {
        try {
            Map<String, Object> input = parseInput(tu._input());
            log.info("Tool call: {} input={}", tu.name(), input);
            String result = switch (tu.name()) {
                case "search_documents" -> toJson(documentService.searchDocuments(
                        principal,
                        (String) input.get("query"),
                        (String) input.get("memberId"),
                        (String) input.get("categoryId"),
                        (String) input.get("label")));
                case "list_members" -> toJson(familyService.listMembers(principal));
                case "list_categories" -> {
                    var family = familyService.getFamilyForUser(principal.uid());
                    if (family == null) {
                        yield "[]";
                    }
                    yield toJson(categoryService.listByFamily(family.getId()));
                }
                case "get_document" -> toJson(documentService.getDocument(principal, (String) input.get("id")));
                case "recategorize_document" -> {
                    userService.requireAdmin(principal.uid());
                    yield toJson(documentService.recategorizeDocument(
                            principal,
                            (String) input.get("id"),
                            (String) input.get("categoryId")));
                }
                case "create_category" -> {
                    userService.requireAdmin(principal.uid());
                    Family family = familyService.getFamilyForUser(principal.uid());
                    if (family == null) {
                        throw new IllegalArgumentException("User has no family");
                    }
                    yield toJson(categoryService.create(
                            family.getId(),
                            (String) input.get("name"),
                            (String) input.get("parentId")));
                }
                case "add_member" -> {
                    userService.requireAdmin(principal.uid());
                    yield toJson(familyService.addMember(
                            principal,
                            (String) input.get("name"),
                            (String) input.get("relationship")));
                }
                case "save_attachment" -> {
                    userService.requireAdmin(principal.uid());
                    var saved = saveAttachment(principal, input);
                    if (sessionId != null) {
                        chatSessionService.clearPendingAttachment(sessionId);
                    }
                    yield toJson(saved);
                }
                default -> "{\"error\":\"unknown tool\"}";
            };
            String preview = result.length() > 400 ? result.substring(0, 400) + "…(truncated)" : result;
            log.info("Tool result: {} bytes={} preview={}", tu.name(), result.length(), preview);
            return result;
        } catch (Exception e) {
            log.warn("Tool {} failed: {}", tu.name(), e.getMessage());
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private Document saveAttachment(FirebaseUserPrincipal principal, Map<String, Object> input) throws Exception {
        String attachmentId = (String) input.get("attachmentId");
        if (attachmentId == null || attachmentId.isBlank()) {
            throw new IllegalArgumentException("attachmentId is required");
        }
        String memberId = (String) input.get("memberId");
        String categoryId = (String) input.get("categoryId");
        String notes = (String) input.get("notes");
        Object rawLabels = input.get("labels");
        List<String> labels = new ArrayList<>();
        if (rawLabels instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    String s = o.toString().trim();
                    if (!s.isBlank()) labels.add(s);
                }
            }
        }
        String fileName = (String) input.getOrDefault("fileName", null);

        AttachmentService.Loaded att = attachmentService.load(attachmentId, principal.uid());
        String effectiveFileName = (fileName != null && !fileName.isBlank()) ? fileName : att.fileName();
        Document saved = documentService.uploadDocument(
                principal,
                effectiveFileName,
                att.mimeType(),
                att.size(),
                new ByteArrayInputStream(att.bytes()),
                memberId,
                categoryId,
                notes,
                labels);
        attachmentService.discard(attachmentId);
        log.info("Agent saved attachment {} as document {} ({})", attachmentId, saved.getId(), effectiveFileName);
        return saved;
    }

    private static Base64ImageSource.MediaType imageMediaTypeFor(String mimeType) {
        if (mimeType == null) return null;
        String m = mimeType.toLowerCase();
        return switch (m) {
            case "image/jpeg", "image/jpg" -> Base64ImageSource.MediaType.IMAGE_JPEG;
            case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
            case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
            case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
            default -> null;
        };
    }

    private Map<String, Object> parseInput(JsonValue raw) {
        if (raw == null) return new HashMap<>();
        try {
            // Anthropic's JsonValue exposes a typed convert() that uses the SDK's own
            // Jackson mapper — stable across SDK versions.
            Map<String, Object> m = raw.convert(new TypeReference<Map<String, Object>>() {});
            return m != null ? m : new HashMap<>();
        } catch (Exception e) {
            log.warn("Failed to parse tool input: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"error\":\"serialization failed\"}";
        }
    }

    private List<Tool> buildTools() {
        return List.of(
                tool("search_documents",
                        "Search documents by a text query (matches filename, notes, and labels). Optional memberId, categoryId, and label filters. Use label to filter by a specific tag like 'Mumbai' or 'Pune'.",
                        schema(Map.of(
                                "query", Map.of("type", "string", "description", "case-insensitive substring match across filename/notes/labels"),
                                "memberId", Map.of("type", "string", "description", "optional: restrict to this member"),
                                "categoryId", Map.of("type", "string", "description", "optional: restrict to this category"),
                                "label", Map.of("type", "string", "description", "optional: exact label to match (case-insensitive)")
                        ), List.of("query"))),
                tool("list_members",
                        "List the family members in the user's family vault.",
                        schema(Map.of(), List.of())),
                tool("list_categories",
                        "List all categories in the user's family vault.",
                        schema(Map.of(), List.of())),
                tool("get_document",
                        "Fetch full metadata for a single document by its id.",
                        schema(Map.of(
                                "id", Map.of("type", "string", "description", "the document id")
                        ), List.of("id"))),
                tool("recategorize_document",
                        "Move a document to a different category.",
                        schema(Map.of(
                                "id", Map.of("type", "string", "description", "the document id"),
                                "categoryId", Map.of("type", "string", "description", "the new category id")
                        ), List.of("id", "categoryId"))),
                tool("create_category",
                        "Create a new category. Use when the user asks for a category that isn't in list_categories. Supply parentId to nest (e.g. 'Books' under 'Education'). Returns the new category with its id; immediately usable in save_attachment.",
                        schema(Map.of(
                                "name", Map.of("type", "string", "description", "category name, e.g. 'Books'"),
                                "parentId", Map.of("type", "string", "description", "optional parent category id")
                        ), List.of("name"))),
                tool("add_member",
                        "Add a new family member.",
                        schema(Map.of(
                                "name", Map.of("type", "string", "description", "the member's name"),
                                "relationship", Map.of("type", "string", "description", "e.g. Wife, Son, Self")
                        ), List.of("name"))),
                tool("save_attachment",
                        "Save a staged file attachment to the family vault. Only usable when the user has attached a file to the current message; the attachmentId is shown in the attachment tag of the user's message. Uploads the file to Drive and indexes it in Firestore with the provided member, category, and labels.",
                        schema(Map.of(
                                "attachmentId", Map.of("type", "string", "description", "id from the [Attachment: ... attachmentId=...] tag on the user's message"),
                                "memberId", Map.of("type", "string", "description", "id of the family member the document belongs to"),
                                "categoryId", Map.of("type", "string", "description", "id of the category to file this under"),
                                "labels", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "free-form tags, e.g. city names, issuers, account holder initials"),
                                "fileName", Map.of("type", "string", "description", "optional: override the stored filename with a more descriptive one"),
                                "notes", Map.of("type", "string", "description", "optional: one-line note about the document")
                        ), List.of("attachmentId", "memberId", "categoryId")))
        );
    }

    private Tool tool(String name, String description, Tool.InputSchema inputSchema) {
        return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
    }

    private Tool.InputSchema schema(Map<String, Map<String, Object>> properties, List<String> required) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        for (var entry : properties.entrySet()) {
            props.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
        }
        Tool.InputSchema.Builder b = Tool.InputSchema.builder().properties(props.build());
        if (!required.isEmpty()) {
            b.required(required);
        }
        return b.build();
    }

    public record ChatTurn(String role, String text) {}
    public record ChatReply(String text) {}
}
