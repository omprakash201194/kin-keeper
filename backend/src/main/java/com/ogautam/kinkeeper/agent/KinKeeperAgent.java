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
import com.ogautam.kinkeeper.model.Asset;
import com.ogautam.kinkeeper.model.Document;
import com.ogautam.kinkeeper.model.Family;
import com.ogautam.kinkeeper.model.Reminder;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.ogautam.kinkeeper.service.AssetService;
import com.ogautam.kinkeeper.service.CategoryService;
import com.ogautam.kinkeeper.service.ChatSessionService;
import com.ogautam.kinkeeper.service.ContactService;
import com.ogautam.kinkeeper.service.DocumentService;
import com.ogautam.kinkeeper.service.FamilyService;
import com.ogautam.kinkeeper.service.ReminderService;
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
            You are Kin-Keeper, the assistant for a family document vault.

            Subjects a document can belong to:
              - family MEMBER (Wife, Self, Dad, …)
              - external CONTACT (lawyer, doctor, landlord — not a family login)
              - asset: HOME, VEHICLE, APPLIANCE, or POLICY
            A single document may link to several subjects at once (e.g. a car
            insurance policy links the policyholder MEMBER AND the VEHICLE asset).

            Available tools (call list_* before acting — never fabricate IDs):
              Documents    — search_documents, get_document, recategorize_document,
                             save_attachment (pass `links` to tie to multiple subjects)
              Members      — list_members, add_member
              Contacts     — list_contacts, create_contact
              Categories   — list_categories, create_category (optional parentId to nest)
              Assets       — list_assets (optional type filter), create_asset
                             (set type to HOME/VEHICLE/APPLIANCE/POLICY, only fill the
                             fields that apply to that type — name is always required)
              Reminders    — list_reminders, create_reminder, complete_reminder
                             (recurrence: NONE / DAILY / WEEKLY / MONTHLY / QUARTERLY
                             / HALF_YEARLY / YEARLY / ODOMETER; for ODOMETER supply
                             dueOdometerKm and recurrenceIntervalKm and link a VEHICLE)

            When the user attaches an image, inspect it, infer what it is, and pick
            the right combination of MEMBER / CONTACT / ASSET. Call create_* as needed
            before save_attachment. Keep labels short and ≤3.

            The attachmentId for the currently-attached file appears in the
            [Attachment: ... attachmentId=...] tag on the user's message. The same
            attachmentId stays valid across follow-up turns until save_attachment
            consumes it — reuse it without asking the user to re-attach.

            Keep answers brief.
            """;

    private final DocumentService documentService;
    private final FamilyService familyService;
    private final CategoryService categoryService;
    private final UserService userService;
    private final AttachmentService attachmentService;
    private final ChatSessionService chatSessionService;
    private final ContactService contactService;
    private final AssetService assetService;
    private final ReminderService reminderService;
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
                          ContactService contactService,
                          AssetService assetService,
                          ReminderService reminderService,
                          @Value("${anthropic.default-model:claude-sonnet-4-6}") String defaultModel) {
        this.documentService = documentService;
        this.familyService = familyService;
        this.categoryService = categoryService;
        this.userService = userService;
        this.attachmentService = attachmentService;
        this.chatSessionService = chatSessionService;
        this.contactService = contactService;
        this.assetService = assetService;
        this.reminderService = reminderService;
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
                case "list_contacts" -> {
                    Family family = familyService.getFamilyForUser(principal.uid());
                    if (family == null) yield "[]";
                    yield toJson(contactService.listByFamily(family.getId()));
                }
                case "create_contact" -> {
                    userService.requireAdmin(principal.uid());
                    Family family = familyService.getFamilyForUser(principal.uid());
                    if (family == null) throw new IllegalArgumentException("User has no family");
                    yield toJson(contactService.create(
                            family.getId(),
                            (String) input.get("name"),
                            (String) input.get("relationship"),
                            (String) input.get("phone"),
                            (String) input.get("email"),
                            (String) input.get("notes")));
                }
                case "list_assets" -> {
                    Family family = familyService.getFamilyForUser(principal.uid());
                    if (family == null) yield "[]";
                    String typeFilter = (String) input.get("type");
                    List<Asset> all = assetService.listByFamily(family.getId());
                    if (typeFilter != null && !typeFilter.isBlank()) {
                        String want = typeFilter.toUpperCase();
                        all = all.stream().filter(a -> a.getType() != null
                                && a.getType().name().equals(want)).toList();
                    }
                    yield toJson(all);
                }
                case "create_asset" -> {
                    userService.requireAdmin(principal.uid());
                    Family family = familyService.getFamilyForUser(principal.uid());
                    if (family == null) throw new IllegalArgumentException("User has no family");
                    Asset form = objectMapper.convertValue(input, Asset.class);
                    yield toJson(assetService.create(family.getId(), form));
                }
                case "list_reminders" -> {
                    Family family = familyService.getFamilyForUser(principal.uid());
                    if (family == null) yield "[]";
                    yield toJson(reminderService.listByFamily(family.getId()));
                }
                case "create_reminder" -> {
                    userService.requireAdmin(principal.uid());
                    Family family = familyService.getFamilyForUser(principal.uid());
                    if (family == null) throw new IllegalArgumentException("User has no family");
                    Reminder form = objectMapper.convertValue(input, Reminder.class);
                    yield toJson(reminderService.create(family.getId(), principal.uid(), form));
                }
                case "complete_reminder" -> {
                    userService.requireAdmin(principal.uid());
                    Family family = familyService.getFamilyForUser(principal.uid());
                    if (family == null) throw new IllegalArgumentException("User has no family");
                    yield toJson(reminderService.complete(family.getId(), (String) input.get("id")));
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
        List<com.ogautam.kinkeeper.model.LinkRef> links = new ArrayList<>();
        Object rawLinks = input.get("links");
        if (rawLinks instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?,?> m) {
                    Object t = m.get("type");
                    Object i = m.get("id");
                    if (t != null && i != null) {
                        try {
                            links.add(com.ogautam.kinkeeper.model.LinkRef.builder()
                                    .type(com.ogautam.kinkeeper.model.LinkType.valueOf(String.valueOf(t).toUpperCase()))
                                    .id(String.valueOf(i))
                                    .build());
                        } catch (IllegalArgumentException ignored) { /* unknown link type */ }
                    }
                }
            }
        }
        Document saved = documentService.uploadDocument(
                principal,
                effectiveFileName,
                att.mimeType(),
                att.size(),
                new ByteArrayInputStream(att.bytes()),
                memberId,
                categoryId,
                notes,
                labels,
                links);
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
                tool("list_contacts",
                        "List external contacts (non-family people like lawyers, doctors, landlords).",
                        schema(Map.of(), List.of())),
                tool("create_contact",
                        "Create a new external contact (not a family member, won't log in).",
                        schema(Map.of(
                                "name", Map.of("type", "string", "description", "contact's name"),
                                "relationship", Map.of("type", "string", "description", "e.g. Lawyer, Doctor, Landlord"),
                                "phone", Map.of("type", "string"),
                                "email", Map.of("type", "string"),
                                "notes", Map.of("type", "string")
                        ), List.of("name"))),
                tool("list_assets",
                        "List assets (homes, vehicles, appliances, policies). Optional type filter.",
                        schema(Map.of(
                                "type", Map.of("type", "string", "description", "optional: HOME, VEHICLE, APPLIANCE, or POLICY")
                        ), List.of())),
                tool("create_asset",
                        "Register a new asset. Required: type (HOME/VEHICLE/APPLIANCE/POLICY) and name. " +
                                "Fill only fields relevant to the type — VEHICLE wants make/model/identifier(reg)/odometerKm; " +
                                "APPLIANCE wants make/model/identifier(serial)/expiryDate(warranty end); " +
                                "HOME wants address; POLICY wants provider/identifier(policy number)/frequency/amount/expiryDate/linkedAssetIds.",
                        schema(Map.ofEntries(
                                Map.entry("type", Map.of("type", "string", "description", "HOME | VEHICLE | APPLIANCE | POLICY")),
                                Map.entry("name", Map.of("type", "string", "description", "short identifying name, e.g. 'Mumbai house'")),
                                Map.entry("make", Map.of("type", "string")),
                                Map.entry("model", Map.of("type", "string")),
                                Map.entry("identifier", Map.of("type", "string", "description", "serial / VIN / registration / policy number")),
                                Map.entry("address", Map.of("type", "string", "description", "HOME only")),
                                Map.entry("provider", Map.of("type", "string", "description", "POLICY only — insurer/bank")),
                                Map.entry("purchaseDate", Map.of("type", "string", "description", "YYYY-MM-DD")),
                                Map.entry("expiryDate", Map.of("type", "string", "description", "YYYY-MM-DD (warranty or policy end)")),
                                Map.entry("frequency", Map.of("type", "string", "description", "POLICY: MONTHLY | QUARTERLY | YEARLY")),
                                Map.entry("amount", Map.of("type", "number", "description", "premium / value")),
                                Map.entry("odometerKm", Map.of("type", "integer", "description", "VEHICLE only")),
                                Map.entry("notes", Map.of("type", "string"))
                        ), List.of("type", "name"))),
                tool("list_reminders",
                        "List all reminders for the family, with dueAt/completed status.",
                        schema(Map.of(), List.of())),
                tool("create_reminder",
                        "Create a reminder. Every reminder MUST be linked to at least one asset " +
                                "(HOME/VEHICLE/APPLIANCE/POLICY) via linkedRefs — if the user's request doesn't " +
                                "obviously map to an existing asset, call create_asset first. " +
                                "For date-based: supply dueAt (ISO-8601) + optional recurrence. " +
                                "For odometer (vehicle servicing): set recurrence=ODOMETER, dueOdometerKm=next target, " +
                                "recurrenceIntervalKm=interval, and include the VEHICLE in linkedRefs. " +
                                "Members/contacts/documents can also appear in linkedRefs in addition to the required asset.",
                        schema(Map.of(
                                "title", Map.of("type", "string"),
                                "notes", Map.of("type", "string"),
                                "dueAt", Map.of("type", "string", "description", "ISO-8601, e.g. 2026-09-15T00:00:00Z"),
                                "recurrence", Map.of("type", "string",
                                        "description", "NONE | DAILY | WEEKLY | MONTHLY | QUARTERLY | HALF_YEARLY | YEARLY | ODOMETER"),
                                "recurrenceIntervalKm", Map.of("type", "integer"),
                                "dueOdometerKm", Map.of("type", "integer"),
                                "linkedRefs", Map.of("type", "array",
                                        "items", Map.of("type", "object", "properties",
                                                Map.of("type", Map.of("type", "string"), "id", Map.of("type", "string"))),
                                        "description", "array of {type, id} links: MEMBER/CONTACT/HOME/VEHICLE/APPLIANCE/POLICY/DOCUMENT")
                        ), List.of("title"))),
                tool("complete_reminder",
                        "Mark a reminder completed. Recurring reminders automatically roll their dueAt (or dueOdometerKm) forward.",
                        schema(Map.of(
                                "id", Map.of("type", "string")
                        ), List.of("id"))),
                tool("save_attachment",
                        "Save a staged file attachment to the family vault. Only usable when the user has attached a file to the current message; the attachmentId is shown in the attachment tag of the user's message. Uploads the file to Drive and indexes it in Firestore with the provided member, category, and labels.",
                        schema(Map.of(
                                "attachmentId", Map.of("type", "string", "description", "id from the [Attachment: ... attachmentId=...] tag on the user's message"),
                                "memberId", Map.of("type", "string", "description", "primary member the document belongs to (can be null if the document is for a contact/asset only)"),
                                "categoryId", Map.of("type", "string", "description", "id of the category to file this under"),
                                "labels", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "short free-form tags (≤3)"),
                                "links", Map.of("type", "array",
                                        "items", Map.of("type", "object", "properties",
                                                Map.of("type", Map.of("type", "string"), "id", Map.of("type", "string"))),
                                        "description", "additional subjects: array of {type, id} where type is CONTACT/HOME/VEHICLE/APPLIANCE/POLICY"),
                                "fileName", Map.of("type", "string", "description", "optional: override the stored filename"),
                                "notes", Map.of("type", "string")
                        ), List.of("attachmentId", "categoryId")))
        );
    }

    private Tool tool(String name, String description, Tool.InputSchema inputSchema) {
        return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
    }

    private Tool.InputSchema schema(Map<String, ? extends Map<String, ? extends Object>> properties,
                                    List<String> required) {
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
