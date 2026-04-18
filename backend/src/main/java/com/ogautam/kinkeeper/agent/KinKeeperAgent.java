package com.ogautam.kinkeeper.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
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
import com.ogautam.kinkeeper.model.Category;
import com.ogautam.kinkeeper.model.Document;
import com.ogautam.kinkeeper.model.FamilyMember;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.ogautam.kinkeeper.service.CategoryService;
import com.ogautam.kinkeeper.service.DocumentService;
import com.ogautam.kinkeeper.service.FamilyService;
import com.ogautam.kinkeeper.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class KinKeeperAgent {

    private static final int MAX_ITERATIONS = 8;
    private static final String SYSTEM_PROMPT = """
            You are Kin-Keeper, the assistant for a family document vault. You help users
            find, review, and organize documents stored in their family vault. You can
            search documents, list family members and categories, fetch document details,
            and change a document's category.

            Keep answers brief. When reporting search results, list filename and category.
            Never fabricate document IDs or filenames — if a tool returns nothing, say so.
            You cannot upload files; tell the user to use the Documents page to upload.
            """;

    private final DocumentService documentService;
    private final FamilyService familyService;
    private final CategoryService categoryService;
    private final UserService userService;
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
                          @Value("${anthropic.default-model:claude-sonnet-4-6}") String defaultModel) {
        this.documentService = documentService;
        this.familyService = familyService;
        this.categoryService = categoryService;
        this.userService = userService;
        this.defaultModel = defaultModel;
    }

    public ChatReply chat(FirebaseUserPrincipal principal,
                          List<ChatTurn> history,
                          String userMessage) throws Exception {
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
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(userMessage)
                .build());

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
                    String result = runTool(principal, tu);
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

    private String runTool(FirebaseUserPrincipal principal, ToolUseBlock tu) {
        try {
            Map<String, Object> input = parseInput(tu._input());
            log.info("Tool call: {} input={}", tu.name(), input);
            String result = switch (tu.name()) {
                case "search_documents" -> toJson(documentService.searchDocuments(
                        principal,
                        (String) input.get("query"),
                        (String) input.get("memberId"),
                        (String) input.get("categoryId")));
                case "list_members" -> toJson(familyService.listMembers(principal));
                case "list_categories" -> {
                    var family = familyService.getFamilyForUser(principal.uid());
                    if (family == null) {
                        yield "[]";
                    }
                    yield toJson(categoryService.listByFamily(family.getId()));
                }
                case "get_document" -> toJson(documentService.getDocument(principal, (String) input.get("id")));
                case "recategorize_document" -> toJson(documentService.recategorizeDocument(
                        principal,
                        (String) input.get("id"),
                        (String) input.get("categoryId")));
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
                        "Search documents by a text query (matches filename, notes, and tags). Optional memberId and categoryId filters.",
                        schema(Map.of(
                                "query", Map.of("type", "string", "description", "case-insensitive substring match"),
                                "memberId", Map.of("type", "string", "description", "optional: restrict to this member"),
                                "categoryId", Map.of("type", "string", "description", "optional: restrict to this category")
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
                        ), List.of("id", "categoryId")))
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
