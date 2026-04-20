package com.ogautam.kinkeeper.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolResultBlockParam.Content;
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
import com.ogautam.kinkeeper.model.Conversation;
import com.ogautam.kinkeeper.model.LinkType;
import com.ogautam.kinkeeper.service.AssetService;
import com.ogautam.kinkeeper.service.CategoryService;
import com.ogautam.kinkeeper.service.ChatSessionService;
import com.ogautam.kinkeeper.service.ContactService;
import com.ogautam.kinkeeper.service.ConversationService;
import com.ogautam.kinkeeper.service.DocumentService;
import com.ogautam.kinkeeper.service.FamilyService;
import com.ogautam.kinkeeper.service.NutritionService;
import com.ogautam.kinkeeper.service.PlanService;
import com.ogautam.kinkeeper.service.ReminderService;
import com.ogautam.kinkeeper.service.UserService;
import com.ogautam.kinkeeper.model.NutritionEntry;
import com.ogautam.kinkeeper.model.NutritionFacts;
import com.ogautam.kinkeeper.model.Plan;
import com.ogautam.kinkeeper.model.PlanSegment;

import java.time.Instant;
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
              - asset: HOME, VEHICLE, APPLIANCE, or POLICY (POLICY covers both
                insurance policies AND recurring services/subscriptions —
                internet, electricity, gas, phone recharge, credit card, OTT, etc.)
            A single document may link to several subjects at once (e.g. a car
            insurance policy links the policyholder MEMBER AND the VEHICLE asset).

            Available tools (call list_* before acting — never fabricate IDs):
              Documents      — search_documents, get_document, read_document,
                               recategorize_document, save_attachment (pass `links`
                               to tie to multiple subjects). Use read_document when
                               the user asks a question about a specific document's
                               CONTENTS (e.g. "what's the due date on this policy?")
                               — it inlines the PDF/image so you can read it.
              Members        — list_members, add_member
              Contacts       — list_contacts, create_contact
              Categories     — list_categories, create_category (optional parentId to nest)
              Assets         — list_assets (optional type filter), create_asset
                               (set type to HOME/VEHICLE/APPLIANCE/POLICY, only fill the
                               fields that apply to that type — name is always required)
              Reminders      — list_reminders, create_reminder, complete_reminder. Every
                               reminder must be anchored to at least one asset
                               (HOME/VEHICLE/APPLIANCE/POLICY) or a CONVERSATION. For
                               ODOMETER reminders supply dueOdometerKm and
                               recurrenceIntervalKm and link a VEHICLE.
              Plans          — list_plans, get_plan, create_plan, add_plan_segment,
                               link_document_to_plan. Plans are time-bounded things
                               the family is organizing — trips, concerts, weddings,
                               conferences. Each plan carries an itinerary of
                               segments (FLIGHT/HOTEL/ACTIVITY/CONCERT/MEAL/
                               TRANSPORT/OTHER). When the user mentions an upcoming
                               trip/event or saves a ticket/reservation, offer to
                               tie it to a plan. A plan's links[] carries attendees
                               (MEMBER/CONTACT), attached docs, and related assets.
              Nutrition      — list_nutrition, nutrition_summary. Entries are
                               created by the Nutrition page's camera scanner, not
                               by you. Use list_nutrition when the user asks what
                               they ate ("what did I have yesterday?"). Use
                               nutrition_summary for totals/trends ("how much sugar
                               this week?") — it returns aggregated calories, sugar,
                               protein, carbs, fat over the window.
              Conversations  — log_conversation, search_conversations. Use
                               format=ENCOUNTER for a single recap (summary/outcome/
                               followUp). Use format=THREAD for a verbatim back-and-forth
                               (supply `messages` list). Always set `links` — can combine
                               CONTACT + MEMBER + asset types in one go (e.g. a lawyer
                               call about a home links both the lawyer CONTACT and the
                               HOME asset). If the user mentions a follow-up date/action,
                               AFTER logging offer to create a reminder anchored on that
                               conversation (pass linkedRefs=[{type:CONVERSATION,id}]).

            When the user attaches files, inspect each one, infer what it is, and pick
            the right combination of MEMBER / CONTACT / ASSET per file. Call create_*
            as needed before save_attachment. Keep labels short and ≤3.

            A single user turn may carry MANY attachments — every file appears as its
            own [Attachment: <name> (... attachmentId=<id>)] line. When the user
            uploads several files and asks you to "classify" / "sort" / "file" them,
            call save_attachment ONCE PER FILE, pairing each attachmentId with its
            own memberId/categoryId/labels/links. Do not lump multiple files into a
            single save_attachment call; the tool takes exactly one attachmentId at
            a time.

            An attachmentId stays valid across follow-up turns until save_attachment
            consumes it — reuse it without asking the user to re-attach.

            Bill / SMS ingestion
              When the user pastes a message that looks like a bill, recharge,
              subscription confirmation, renewal notice, or utility statement
              (internet, electricity, gas, phone, credit card, OTT, rent, etc.):
                1. list_assets type=POLICY and pick an existing match by
                   provider + account/identifier. Only create_asset if nothing fits.
                2. When creating, set type=POLICY and populate provider (ISP /
                   utility / bank), identifier (account / plan / customer #),
                   frequency (MONTHLY/QUARTERLY/YEARLY inferred from the cycle
                   or validity — e.g. "90 days" → QUARTERLY), amount (MRP /
                   bill amount), expiryDate (the next renewal/due date).
                3. Always create_reminder linked to that asset with dueAt set
                   to the renewal/due date and recurrence matching the
                   billing cycle, so the nudge fires every cycle.
                4. Ignore marketing fluff and one-time OTP / consent codes —
                   those are not worth storing. Report the asset + reminder
                   you created (or matched) in one short sentence so the user
                   can correct in the UI if needed.

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
    private final ConversationService conversationService;
    private final NutritionService nutritionService;
    private final PlanService planService;
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
                          ConversationService conversationService,
                          NutritionService nutritionService,
                          PlanService planService,
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
        this.conversationService = conversationService;
        this.nutritionService = nutritionService;
        this.planService = planService;
        this.defaultModel = defaultModel;
    }

    public ChatReply chat(FirebaseUserPrincipal principal,
                          List<ChatTurn> history,
                          String userMessage,
                          List<String> attachmentIds,
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

        // Multiple attachments: stitch each file's content (image inline when possible,
        // PDF as a document block so Claude can read it) into a single user turn, then
        // tag every file so save_attachment knows which id corresponds to which.
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            List<ContentBlockParam> parts = new ArrayList<>();
            StringBuilder tags = new StringBuilder();
            for (String aid : attachmentIds) {
                if (aid == null || aid.isBlank()) continue;
                AttachmentService.Loaded att = attachmentService.load(aid, principal.uid());
                String mime = att.mimeType() == null ? "" : att.mimeType().toLowerCase();
                String base64 = Base64.getEncoder().encodeToString(att.bytes());
                Base64ImageSource.MediaType imgType = imageMediaTypeFor(mime);
                if (imgType != null) {
                    parts.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                            .source(Base64ImageSource.builder().data(base64).mediaType(imgType).build())
                            .build()));
                } else if ("application/pdf".equals(mime)) {
                    parts.add(ContentBlockParam.ofDocument(DocumentBlockParam.builder()
                            .base64Source(base64)
                            .title(att.fileName() != null ? att.fileName() : aid)
                            .build()));
                }
                // reason: always append a text tag per file so save_attachment can look
                // up the right attachmentId even if the content is non-inlineable.
                tags.append("\n[Attachment: ").append(att.fileName() == null ? "" : att.fileName())
                        .append(" (").append(att.mimeType()).append(", ")
                        .append(att.size()).append(" bytes, attachmentId=")
                        .append(att.id()).append(")]");
            }
            parts.add(ContentBlockParam.ofText(TextBlockParam.builder()
                    .text(userMessage + (tags.length() > 0 ? "\n" + tags : ""))
                    .build()));
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(parts)
                    .build());
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
                    ToolResult result = runTool(principal, tu, sessionId);
                    ToolResultBlockParam.Builder trb = ToolResultBlockParam.builder()
                            .toolUseId(tu.id());
                    if (result.blocks().isEmpty()) {
                        trb.content(result.text());
                    } else {
                        List<Content.Block> blocks = new ArrayList<>();
                        if (result.text() != null && !result.text().isBlank()) {
                            blocks.add(Content.Block.ofText(
                                    TextBlockParam.builder().text(result.text()).build()));
                        }
                        blocks.addAll(result.blocks());
                        trb.contentOfBlocks(blocks);
                    }
                    toolResults.add(ContentBlockParam.ofToolResult(trb.build()));
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

    private ToolResult runTool(FirebaseUserPrincipal principal, ToolUseBlock tu, String sessionId) {
        try {
            Map<String, Object> input = parseInput(tu._input());
            log.info("Tool call: {} input={}", tu.name(), input);
            if ("read_document".equals(tu.name())) {
                return readDocument(principal, input);
            }
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
                case "log_conversation" -> {
                    userService.requireAdmin(principal.uid());
                    Family family = familyService.getFamilyForUser(principal.uid());
                    if (family == null) throw new IllegalArgumentException("User has no family");
                    Conversation form = objectMapper.convertValue(input, Conversation.class);
                    yield toJson(conversationService.create(family.getId(), principal.uid(), form));
                }
                case "search_conversations" -> {
                    Family family = familyService.getFamilyForUser(principal.uid());
                    if (family == null) yield "[]";
                    LinkType lt = null;
                    Object rawLt = input.get("linkType");
                    if (rawLt != null) {
                        try { lt = LinkType.valueOf(String.valueOf(rawLt).toUpperCase()); }
                        catch (IllegalArgumentException ignored) { /* invalid type: treat as no filter */ }
                    }
                    Instant from = parseInstantOrNull((String) input.get("fromDate"));
                    Instant to = parseInstantOrNull((String) input.get("toDate"));
                    yield toJson(conversationService.search(
                            family.getId(),
                            (String) input.get("query"),
                            lt,
                            (String) input.get("linkId"),
                            from,
                            to));
                }
                case "list_nutrition" -> {
                    Family family = familyService.getFamilyForUser(principal.uid());
                    if (family == null) yield "[]";
                    Instant from = parseInstantOrNull((String) input.get("fromDate"));
                    Instant to = parseInstantOrNull((String) input.get("toDate"));
                    yield toJson(nutritionService.search(
                            family.getId(), (String) input.get("memberId"), from, to));
                }
                case "nutrition_summary" -> {
                    Family family = familyService.getFamilyForUser(principal.uid());
                    if (family == null) yield "{}";
                    Instant from = parseInstantOrNull((String) input.get("fromDate"));
                    Instant to = parseInstantOrNull((String) input.get("toDate"));
                    if (from == null && to == null) {
                        Object rawDays = input.get("days");
                        int days = rawDays instanceof Number n ? n.intValue() : 7;
                        to = Instant.now();
                        from = to.minusSeconds(days * 86400L);
                    }
                    List<NutritionEntry> entries = nutritionService.search(
                            family.getId(), (String) input.get("memberId"), from, to);
                    yield toJson(summarize(entries, from, to));
                }
                case "list_plans" -> {
                    Family family = familyService.getFamilyForUser(principal.uid());
                    if (family == null) yield "[]";
                    yield toJson(planService.listByFamily(family.getId()));
                }
                case "get_plan" -> {
                    Family family = familyService.getFamilyForUser(principal.uid());
                    if (family == null) throw new IllegalArgumentException("User has no family");
                    yield toJson(planService.get(family.getId(), (String) input.get("id")));
                }
                case "create_plan" -> {
                    userService.requireAdmin(principal.uid());
                    Family family = familyService.getFamilyForUser(principal.uid());
                    if (family == null) throw new IllegalArgumentException("User has no family");
                    Plan form = objectMapper.convertValue(input, Plan.class);
                    yield toJson(planService.create(family.getId(), principal.uid(), form));
                }
                case "add_plan_segment" -> {
                    userService.requireAdmin(principal.uid());
                    Family family = familyService.getFamilyForUser(principal.uid());
                    if (family == null) throw new IllegalArgumentException("User has no family");
                    String planId = (String) input.get("planId");
                    if (planId == null || planId.isBlank()) {
                        throw new IllegalArgumentException("planId is required");
                    }
                    // The agent passes the segment under a `segment` key OR flat — accept both.
                    Object rawSegment = input.getOrDefault("segment", input);
                    PlanSegment seg = objectMapper.convertValue(rawSegment, PlanSegment.class);
                    yield toJson(planService.addSegment(family.getId(), planId, seg));
                }
                case "link_document_to_plan" -> {
                    userService.requireAdmin(principal.uid());
                    Family family = familyService.getFamilyForUser(principal.uid());
                    if (family == null) throw new IllegalArgumentException("User has no family");
                    yield toJson(planService.linkDocument(
                            family.getId(),
                            (String) input.get("planId"),
                            (String) input.get("documentId"),
                            principal));
                }
                case "save_attachment" -> {
                    userService.requireAdmin(principal.uid());
                    var saved = saveAttachment(principal, input);
                    if (sessionId != null) {
                        String consumedId = (String) input.get("attachmentId");
                        if (consumedId != null && !consumedId.isBlank()) {
                            chatSessionService.clearPendingAttachment(sessionId, consumedId);
                        }
                        chatSessionService.markRecentlySavedDocument(sessionId, saved.getId());
                    }
                    yield toJson(saved);
                }
                default -> "{\"error\":\"unknown tool\"}";
            };
            String preview = result.length() > 400 ? result.substring(0, 400) + "…(truncated)" : result;
            log.info("Tool result: {} bytes={} preview={}", tu.name(), result.length(), preview);
            return ToolResult.ofText(result);
        } catch (Exception e) {
            log.warn("Tool {} failed: {}", tu.name(), e.getMessage());
            return ToolResult.ofText("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    private ToolResult readDocument(FirebaseUserPrincipal principal, Map<String, Object> input) throws Exception {
        String id = (String) input.get("id");
        if (id == null || id.isBlank()) {
            return ToolResult.ofText("{\"error\":\"id is required\"}");
        }
        Document doc = documentService.getDocument(principal, id);
        String mime = doc.getMimeType() != null ? doc.getMimeType().toLowerCase() : "";
        String header = "Document metadata: " + toJson(doc);
        if ("application/pdf".equals(mime)) {
            byte[] bytes = documentService.downloadDocument(principal, id);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            DocumentBlockParam pdf = DocumentBlockParam.builder()
                    .base64Source(base64)
                    .title(doc.getFileName() != null ? doc.getFileName() : id)
                    .build();
            log.info("read_document: inlined PDF {} ({} bytes)", id, bytes.length);
            return new ToolResult(header, List.of(Content.Block.ofDocument(pdf)));
        }
        Base64ImageSource.MediaType imgMediaType = imageMediaTypeFor(mime);
        if (imgMediaType != null) {
            byte[] bytes = documentService.downloadDocument(principal, id);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            ImageBlockParam img = ImageBlockParam.builder()
                    .source(Base64ImageSource.builder()
                            .data(base64)
                            .mediaType(imgMediaType)
                            .build())
                    .build();
            log.info("read_document: inlined image {} ({} bytes, {})", id, bytes.length, mime);
            return new ToolResult(header, List.of(Content.Block.ofImage(img)));
        }
        return ToolResult.ofText(header + "\nContent cannot be inlined for mimeType=" + mime
                + ". Only PDFs and common images are readable inline.");
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

    private static Map<String, Object> summarize(List<NutritionEntry> entries, Instant from, Instant to) {
        double cal = 0, protein = 0, carbs = 0, sugar = 0, fat = 0, fiber = 0, sodium = 0;
        for (NutritionEntry e : entries) {
            NutritionFacts f = e.getFacts();
            if (f == null) continue;
            cal     += f.getCalories()      != null ? f.getCalories()      : 0;
            protein += f.getProteinG()      != null ? f.getProteinG()      : 0;
            carbs   += f.getCarbsG()        != null ? f.getCarbsG()        : 0;
            sugar   += f.getSugarG()        != null ? f.getSugarG()        : 0;
            fat     += f.getFatG()          != null ? f.getFatG()          : 0;
            fiber   += f.getFiberG()        != null ? f.getFiberG()        : 0;
            sodium  += f.getSodiumMg()      != null ? f.getSodiumMg()      : 0;
        }
        Map<String, Object> out = new HashMap<>();
        out.put("entryCount", entries.size());
        out.put("windowFrom", from != null ? from.toString() : null);
        out.put("windowTo", to != null ? to.toString() : null);
        out.put("totals", Map.of(
                "calories", cal,
                "proteinG", protein,
                "carbsG", carbs,
                "sugarG", sugar,
                "fatG", fat,
                "fiberG", fiber,
                "sodiumMg", sodium));
        return out;
    }

    private static Instant parseInstantOrNull(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return Instant.parse(iso); }
        catch (Exception e) { return null; }
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
                tool("read_document",
                        "Inline the actual contents of a document so you can answer questions about it. "
                                + "Works for PDFs and common image types (jpg/png/gif/webp). "
                                + "For other mime types the tool returns metadata only. Use this when the user "
                                + "asks anything about what IS INSIDE a document (dates, amounts, names, clauses).",
                        schema(Map.of(
                                "id", Map.of("type", "string", "description", "the document id to read")
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
                                "HOME wants address; POLICY covers insurance AND recurring subscriptions/services " +
                                "(internet, electricity, gas, phone recharge, credit card, OTT) — wants provider, " +
                                "identifier (policy/account/customer #), frequency (MONTHLY/QUARTERLY/YEARLY), amount, " +
                                "expiryDate (policy end OR next renewal date), optional linkedAssetIds.",
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
                tool("log_conversation",
                        "Record an interaction. links[] is required — tie it to at least one subject "
                                + "(contact/member/asset/document). Use ENCOUNTER for a single recap with summary/outcome/followUp. "
                                + "Use THREAD when the user wants to capture actual exchanged messages — supply `messages` list "
                                + "(each {from, content, at?}). After logging, if followUp mentions a date/action, offer to "
                                + "create_reminder with linkedRefs=[{type:CONVERSATION,id:<new conversation id>}].",
                        schema(Map.ofEntries(
                                Map.entry("format", Map.of("type", "string", "description", "ENCOUNTER | THREAD")),
                                Map.entry("channel", Map.of("type", "string", "description", "CALL | VISIT | MESSAGE | EMAIL | MEETING | OTHER")),
                                Map.entry("occurredAt", Map.of("type", "string", "description", "ISO-8601; defaults to now")),
                                Map.entry("title", Map.of("type", "string", "description", "short title; auto-derived if omitted")),
                                Map.entry("summary", Map.of("type", "string", "description", "ENCOUNTER: recap of what was discussed")),
                                Map.entry("outcome", Map.of("type", "string", "description", "ENCOUNTER: decision/result")),
                                Map.entry("followUp", Map.of("type", "string", "description", "action-to-take-next sentence; triggers reminder offer")),
                                Map.entry("messages", Map.of("type", "array",
                                        "items", Map.of("type", "object", "properties",
                                                Map.of("from", Map.of("type", "string"),
                                                        "content", Map.of("type", "string"),
                                                        "at", Map.of("type", "string"))),
                                        "description", "THREAD: list of {from, content, at} turns")),
                                Map.entry("links", Map.of("type", "array",
                                        "items", Map.of("type", "object", "properties",
                                                Map.of("type", Map.of("type", "string"), "id", Map.of("type", "string"))),
                                        "description", "REQUIRED. Subject links: MEMBER/CONTACT/HOME/VEHICLE/APPLIANCE/POLICY/DOCUMENT"))
                        ), List.of("format", "links"))),
                tool("search_conversations",
                        "Find logged conversations. All filters optional — no filter returns the full family timeline.",
                        schema(Map.of(
                                "query", Map.of("type", "string", "description", "substring across title/summary/outcome/followUp/messages"),
                                "linkType", Map.of("type", "string", "description", "restrict to conversations linked to this subject type"),
                                "linkId", Map.of("type", "string", "description", "restrict to a specific subject id (requires linkType)"),
                                "fromDate", Map.of("type", "string", "description", "ISO-8601 inclusive lower bound on occurredAt"),
                                "toDate", Map.of("type", "string", "description", "ISO-8601 inclusive upper bound on occurredAt")
                        ), List.of())),
                tool("list_nutrition",
                        "List logged food/drink entries. Filters optional — memberId restricts to one person, " +
                                "fromDate/toDate bound consumedAt.",
                        schema(Map.of(
                                "memberId", Map.of("type", "string"),
                                "fromDate", Map.of("type", "string", "description", "ISO-8601 inclusive lower bound"),
                                "toDate",   Map.of("type", "string", "description", "ISO-8601 inclusive upper bound")
                        ), List.of())),
                tool("nutrition_summary",
                        "Aggregate totals (calories, protein, carbs, sugar, fat, fiber, sodium) across nutrition entries. " +
                                "Use `days` for a rolling window (default 7) ending now, OR pass fromDate/toDate for an " +
                                "explicit range. Optionally scope to one memberId.",
                        schema(Map.of(
                                "memberId", Map.of("type", "string"),
                                "days",     Map.of("type", "integer", "description", "rolling window length in days, default 7"),
                                "fromDate", Map.of("type", "string"),
                                "toDate",   Map.of("type", "string")
                        ), List.of())),
                tool("list_plans",
                        "List trips, events, and celebrations the family is organizing. Returns id, name, type, dates, destination, and segments.",
                        schema(Map.of(), List.of())),
                tool("get_plan",
                        "Fetch a single plan with its full segment list and linked docs.",
                        schema(Map.of(
                                "id", Map.of("type", "string")
                        ), List.of("id"))),
                tool("create_plan",
                        "Create a new plan. type is one of TRIP | EVENT | CELEBRATION | OTHER. "
                                + "startDate/endDate are ISO dates (YYYY-MM-DD). Use links[] to attach "
                                + "attendees (MEMBER/CONTACT) and related subjects (DOCUMENT, HOME, VEHICLE). "
                                + "Segments can be added now or via add_plan_segment later.",
                        schema(Map.ofEntries(
                                Map.entry("name",        Map.of("type", "string", "description", "short name, e.g. 'Goa Dec 2026'")),
                                Map.entry("type",        Map.of("type", "string", "description", "TRIP | EVENT | CELEBRATION | OTHER")),
                                Map.entry("startDate",   Map.of("type", "string", "description", "YYYY-MM-DD")),
                                Map.entry("endDate",     Map.of("type", "string", "description", "YYYY-MM-DD")),
                                Map.entry("destination", Map.of("type", "string")),
                                Map.entry("notes",       Map.of("type", "string")),
                                Map.entry("links",       Map.of("type", "array",
                                        "items", Map.of("type", "object", "properties",
                                                Map.of("type", Map.of("type", "string"), "id", Map.of("type", "string"))),
                                        "description", "attendees + related subjects: MEMBER/CONTACT/DOCUMENT/HOME/VEHICLE/APPLIANCE/POLICY"))
                        ), List.of("name"))),
                tool("add_plan_segment",
                        "Append one itinerary line to an existing plan. kind is one of "
                                + "FLIGHT | HOTEL | ACTIVITY | CONCERT | MEAL | TRANSPORT | OTHER. Use "
                                + "documentId to tie the segment to a specific ticket/booking document.",
                        schema(Map.ofEntries(
                                Map.entry("planId",       Map.of("type", "string")),
                                Map.entry("kind",         Map.of("type", "string", "description", "FLIGHT | HOTEL | ACTIVITY | CONCERT | MEAL | TRANSPORT | OTHER")),
                                Map.entry("title",        Map.of("type", "string")),
                                Map.entry("location",     Map.of("type", "string")),
                                Map.entry("confirmation", Map.of("type", "string", "description", "PNR / booking reference")),
                                Map.entry("startAt",      Map.of("type", "string", "description", "ISO-8601")),
                                Map.entry("endAt",        Map.of("type", "string", "description", "ISO-8601")),
                                Map.entry("documentId",   Map.of("type", "string")),
                                Map.entry("notes",        Map.of("type", "string"))
                        ), List.of("planId", "kind", "title"))),
                tool("link_document_to_plan",
                        "Attach an existing document (ticket, booking PDF, itinerary) to a plan. "
                                + "Updates both sides of the link — the plan gets a DOCUMENT reference, "
                                + "the document gets a PLAN reference so filtering either way works.",
                        schema(Map.of(
                                "planId",     Map.of("type", "string"),
                                "documentId", Map.of("type", "string")
                        ), List.of("planId", "documentId"))),
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

    private record ToolResult(String text, List<Content.Block> blocks) {
        static ToolResult ofText(String text) { return new ToolResult(text, List.of()); }
    }
}
