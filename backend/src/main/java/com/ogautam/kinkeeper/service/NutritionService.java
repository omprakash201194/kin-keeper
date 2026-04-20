package com.ogautam.kinkeeper.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.ogautam.kinkeeper.config.CacheConfig;
import com.ogautam.kinkeeper.model.NutritionEntry;
import com.ogautam.kinkeeper.model.NutritionFacts;
import com.ogautam.kinkeeper.model.NutritionSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Slf4j
@Service
public class NutritionService {

    private static final String COLLECTION = "nutrition";

    private static final String ANALYZER_PROMPT = """
            You are a nutrition analyzer. Look at the photo the user sends and return ONLY a
            JSON object (no prose, no markdown fences) with this exact shape:

            {
              "foodName": string,
              "description": string,
              "source": "PACKAGED" | "RAW" | "COOKED" | "DRINK" | "OTHER",
              "facts": {
                "servingDescription": string,
                "calories": number|null,
                "proteinG": number|null,
                "carbsG": number|null,
                "sugarG": number|null,
                "fatG": number|null,
                "saturatedFatG": number|null,
                "fiberG": number|null,
                "sodiumMg": number|null
              },
              "ingredients": [string],
              "healthBenefits": [string],
              "warnings": [string]
            }

            Rules:
              - If the image has a printed nutrition label (PACKAGED), read the numbers from
                the label exactly — don't estimate.
              - For raw / cooked / drinks without a label, give a realistic estimate for a
                typical single serving and set source accordingly.
              - `warnings` is for flags like "high added sugar", "high sodium",
                "ultra-processed", "contains nuts", etc. Keep each warning to a short phrase.
              - `healthBenefits` is short bullet phrases like "good source of fiber".
              - If the photo does not show food at all, return:
                {"foodName":"Not food","description":"…","source":"OTHER","facts":{"servingDescription":""},"ingredients":[],"healthBenefits":[],"warnings":["not food"]}
              - Return ONLY the JSON object. No explanation, no code fences.
            """;

    private final Firestore firestore;
    private final UserService userService;
    private final ApiUsageService apiUsageService;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final String defaultModel;

    public NutritionService(Firestore firestore, UserService userService,
                            ApiUsageService apiUsageService,
                            @Value("${anthropic.default-model:claude-sonnet-4-6}") String defaultModel) {
        this.firestore = firestore;
        this.userService = userService;
        this.apiUsageService = apiUsageService;
        this.defaultModel = defaultModel;
    }

    @Cacheable(value = CacheConfig.CACHE_NUTRITION, key = "#familyId")
    public List<NutritionEntry> listByFamily(String familyId) throws ExecutionException, InterruptedException {
        List<QueryDocumentSnapshot> docs = firestore.collection(COLLECTION)
                .whereEqualTo("familyId", familyId)
                .get().get().getDocuments();
        List<NutritionEntry> out = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot d : docs) out.add(d.toObject(NutritionEntry.class));
        out.sort(Comparator.comparing(
                (NutritionEntry e) -> e.getConsumedAt() == null ? Instant.MIN : e.getConsumedAt())
                .reversed());
        return out;
    }

    public List<NutritionEntry> search(String familyId, String memberId, Instant from, Instant to)
            throws ExecutionException, InterruptedException {
        List<NutritionEntry> out = new ArrayList<>();
        for (NutritionEntry e : listByFamily(familyId)) {
            if (memberId != null && !memberId.isBlank() && !memberId.equals(e.getMemberId())) continue;
            if (from != null && e.getConsumedAt() != null && e.getConsumedAt().isBefore(from)) continue;
            if (to != null && e.getConsumedAt() != null && e.getConsumedAt().isAfter(to)) continue;
            out.add(e);
        }
        return out;
    }

    @CacheEvict(value = CacheConfig.CACHE_NUTRITION, key = "#familyId")
    public NutritionEntry save(String familyId, String createdByUid, NutritionEntry form)
            throws ExecutionException, InterruptedException {
        if (form.getMemberId() == null || form.getMemberId().isBlank()) {
            throw new IllegalArgumentException("memberId is required — every entry must be attributed to a member");
        }
        Instant now = Instant.now();
        DocumentReference ref = firestore.collection(COLLECTION).document();
        NutritionEntry e = NutritionEntry.builder()
                .id(ref.getId())
                .familyId(familyId)
                .memberId(form.getMemberId())
                .consumedAt(form.getConsumedAt() != null ? form.getConsumedAt() : now)
                .foodName(form.getFoodName())
                .description(form.getDescription())
                .source(form.getSource() != null ? form.getSource() : NutritionSource.OTHER)
                .facts(form.getFacts())
                .ingredients(form.getIngredients() != null ? form.getIngredients() : List.of())
                .healthBenefits(form.getHealthBenefits() != null ? form.getHealthBenefits() : List.of())
                .warnings(form.getWarnings() != null ? form.getWarnings() : List.of())
                .createdBy(createdByUid)
                .createdAt(now)
                .build();
        ref.set(e).get();
        log.info("Logged nutrition entry {} '{}' for member {} in family {}",
                ref.getId(), e.getFoodName(), e.getMemberId(), familyId);
        return e;
    }

    @CacheEvict(value = CacheConfig.CACHE_NUTRITION, key = "#familyId")
    public void delete(String familyId, String id) throws ExecutionException, InterruptedException {
        DocumentSnapshot snap = firestore.collection(COLLECTION).document(id).get().get();
        if (!snap.exists()) throw new IllegalArgumentException("Nutrition entry not found");
        NutritionEntry e = snap.toObject(NutritionEntry.class);
        if (e == null || !familyId.equals(e.getFamilyId())) {
            throw new IllegalArgumentException("Nutrition entry not found");
        }
        firestore.collection(COLLECTION).document(id).delete().get();
        log.info("Deleted nutrition entry {} from family {}", id, familyId);
    }

    /**
     * Send the image to Claude vision with a strict JSON-out prompt, parse, and
     * hand back a ready-to-save NutritionEntry. Does NOT persist — the controller
     * decides whether to save or discard based on the client's intent.
     */
    public NutritionEntry analyze(String userUid, byte[] imageBytes, String mimeType) throws Exception {
        String apiKey = userService.getApiKey(userUid);
        if (apiKey == null) {
            throw new IllegalArgumentException("No Claude API key saved. Add one in Settings.");
        }
        Base64ImageSource.MediaType mt = mediaTypeFor(mimeType);
        if (mt == null) {
            throw new IllegalArgumentException("Unsupported image type: " + mimeType);
        }
        String base64 = Base64.getEncoder().encodeToString(imageBytes);

        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        MessageCreateParams params = MessageCreateParams.builder()
                .model(defaultModel)
                .maxTokens(1024)
                .system(ANALYZER_PROMPT)
                .addMessage(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(List.of(
                                ContentBlockParam.ofImage(ImageBlockParam.builder()
                                        .source(Base64ImageSource.builder()
                                                .data(base64)
                                                .mediaType(mt)
                                                .build())
                                        .build()),
                                ContentBlockParam.ofText(TextBlockParam.builder()
                                        .text("Analyze this food/drink. Return JSON only.")
                                        .build())))
                        .build())
                .build();

        Message response = client.messages().create(params);
        apiUsageService.record(userUid, defaultModel, response.usage());
        String raw = collectText(response).trim();
        log.info("Nutrition analyzer raw response ({} chars): {}",
                raw.length(), raw.length() > 300 ? raw.substring(0, 300) + "…" : raw);
        String json = stripFences(raw);

        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Nutrition analyzer returned non-JSON payload: {}", raw);
            throw new IllegalStateException("Analyzer response was not valid JSON");
        }

        NutritionSource src = NutritionSource.OTHER;
        Object rawSrc = parsed.get("source");
        if (rawSrc != null) {
            try { src = NutritionSource.valueOf(String.valueOf(rawSrc).toUpperCase()); }
            catch (IllegalArgumentException ignored) { /* keep OTHER */ }
        }

        NutritionFacts facts = null;
        Object rawFacts = parsed.get("facts");
        if (rawFacts instanceof Map<?, ?>) {
            facts = objectMapper.convertValue(rawFacts, NutritionFacts.class);
        }

        return NutritionEntry.builder()
                .foodName(asString(parsed.get("foodName")))
                .description(asString(parsed.get("description")))
                .source(src)
                .facts(facts)
                .ingredients(asStringList(parsed.get("ingredients")))
                .healthBenefits(asStringList(parsed.get("healthBenefits")))
                .warnings(asStringList(parsed.get("warnings")))
                .build();
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null) {
                String s = item.toString().trim();
                if (!s.isBlank()) out.add(s);
            }
        }
        return out;
    }

    private static String collectText(Message response) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(tb -> {
                if (sb.length() > 0) sb.append('\n');
                sb.append(tb.text());
            });
        }
        return sb.toString();
    }

    private static String stripFences(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) s = s.substring(firstNl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }

    private static Base64ImageSource.MediaType mediaTypeFor(String mimeType) {
        if (mimeType == null) return null;
        return switch (mimeType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> Base64ImageSource.MediaType.IMAGE_JPEG;
            case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
            case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
            case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
            default -> null;
        };
    }
}
