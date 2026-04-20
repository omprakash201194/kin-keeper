package com.ogautam.kinkeeper.service;

import com.anthropic.models.messages.Usage;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Self-metered BYOK API cost tracking. Every outbound Anthropic call reports a
 * `usage` block (input/output/cache tokens); we accumulate per-user, per-month
 * in Firestore and compute cost from a hardcoded price table at read time so
 * the numbers stay accurate if Anthropic changes pricing.
 *
 * Firestore layout:
 *   api_usage/{uid}_{YYYYMM}
 *     userId, month (YYYY-MM), updatedAt,
 *     totalCalls, totalInputTokens, totalOutputTokens,
 *     totalCacheReadTokens, totalCacheWriteTokens,
 *     byModel: { "<modelId>": { calls, inputTokens, outputTokens,
 *                               cacheReadTokens, cacheWriteTokens } }
 */
@Slf4j
@Service
public class ApiUsageService {

    private static final String COLLECTION = "api_usage";
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    /** Cents per 1M tokens, keyed by model id. Values per Anthropic's Jan 2026 pricing. */
    private static final Map<String, ModelPrice> PRICES = Map.of(
            "claude-opus-4-7",       new ModelPrice(1500, 7500, 150, 1875),
            "claude-sonnet-4-6",     new ModelPrice( 300, 1500,  30,  375),
            "claude-sonnet-4-5",     new ModelPrice( 300, 1500,  30,  375),
            "claude-haiku-4-5",      new ModelPrice( 100,  500,  10,  125));

    /** Fallback for unknown models — assume Sonnet pricing, log so we can add it later. */
    private static final ModelPrice DEFAULT_PRICE = new ModelPrice(300, 1500, 30, 375);

    private final Firestore firestore;

    public ApiUsageService(Firestore firestore) {
        this.firestore = firestore;
    }

    /** Best-effort: never throws, never blocks. A failed usage write must not break the caller's flow. */
    public void record(String userUid, String model, Usage usage) {
        if (userUid == null || usage == null) return;
        try {
            String monthKey = monthKey(Instant.now());
            String docId = userUid + "_" + monthKey;
            long input = usage.inputTokens();
            long output = usage.outputTokens();
            long cacheRead = usage.cacheReadInputTokens().orElse(0L);
            long cacheWrite = usage.cacheCreationInputTokens().orElse(0L);
            String modelKey = sanitize(model);

            Map<String, Object> updates = new HashMap<>();
            updates.put("userId", userUid);
            updates.put("month", monthKey.substring(0, 4) + "-" + monthKey.substring(4));
            updates.put("updatedAt", Instant.now());
            updates.put("totalCalls", FieldValue.increment(1));
            updates.put("totalInputTokens", FieldValue.increment(input));
            updates.put("totalOutputTokens", FieldValue.increment(output));
            updates.put("totalCacheReadTokens", FieldValue.increment(cacheRead));
            updates.put("totalCacheWriteTokens", FieldValue.increment(cacheWrite));
            String prefix = "byModel." + modelKey + ".";
            updates.put(prefix + "calls", FieldValue.increment(1));
            updates.put(prefix + "inputTokens", FieldValue.increment(input));
            updates.put(prefix + "outputTokens", FieldValue.increment(output));
            updates.put(prefix + "cacheReadTokens", FieldValue.increment(cacheRead));
            updates.put(prefix + "cacheWriteTokens", FieldValue.increment(cacheWrite));

            firestore.collection(COLLECTION).document(docId).set(updates, SetOptions.merge()).get();
        } catch (Exception e) {
            log.warn("ApiUsage record failed for user {}: {}", userUid, e.getMessage());
        }
    }

    /**
     * Return this user's rollup for the current month plus a lifetime total.
     * Cost is computed at read time from the current price table.
     */
    public UsageReport getReport(String userUid) throws ExecutionException, InterruptedException {
        String thisMonth = monthKey(Instant.now());
        DocumentSnapshot monthSnap = firestore.collection(COLLECTION)
                .document(userUid + "_" + thisMonth).get().get();
        MonthSummary current = monthSnap.exists()
                ? summariseFromDoc(monthSnap, thisMonth)
                : new MonthSummary(thisMonth.substring(0, 4) + "-" + thisMonth.substring(4),
                0, 0, 0, 0, 0, 0, List.of());

        // Lifetime: sum all rollups prefixed with this uid. Personal scale — a
        // few dozen docs at most; fine to read all.
        var all = firestore.collection(COLLECTION)
                .whereEqualTo("userId", userUid).get().get().getDocuments();
        long totalCalls = 0, totalInput = 0, totalOutput = 0, totalCacheRead = 0, totalCacheWrite = 0;
        long totalCents = 0;
        for (var snap : all) {
            MonthSummary m = summariseFromDoc(snap, null);
            totalCalls += m.totalCalls();
            totalInput += m.totalInputTokens();
            totalOutput += m.totalOutputTokens();
            totalCacheRead += m.totalCacheReadTokens();
            totalCacheWrite += m.totalCacheWriteTokens();
            totalCents += m.estimatedCostCents();
        }
        return new UsageReport(current, totalCalls, totalInput, totalOutput,
                totalCacheRead, totalCacheWrite, totalCents);
    }

    @SuppressWarnings("unchecked")
    private MonthSummary summariseFromDoc(DocumentSnapshot snap, String fallbackMonthKey) {
        String month = snap.getString("month");
        if (month == null && fallbackMonthKey != null) {
            month = fallbackMonthKey.substring(0, 4) + "-" + fallbackMonthKey.substring(4);
        }
        long calls = longFrom(snap.get("totalCalls"));
        long input = longFrom(snap.get("totalInputTokens"));
        long output = longFrom(snap.get("totalOutputTokens"));
        long cacheRead = longFrom(snap.get("totalCacheReadTokens"));
        long cacheWrite = longFrom(snap.get("totalCacheWriteTokens"));

        List<ModelUsage> perModel = new ArrayList<>();
        long costCents = 0;
        Object rawByModel = snap.get("byModel");
        if (rawByModel instanceof Map<?, ?> byModel) {
            for (var e : byModel.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> mm)) continue;
                String modelId = String.valueOf(e.getKey());
                long mCalls = longFrom(mm.get("calls"));
                long mInput = longFrom(mm.get("inputTokens"));
                long mOutput = longFrom(mm.get("outputTokens"));
                long mCacheRead = longFrom(mm.get("cacheReadTokens"));
                long mCacheWrite = longFrom(mm.get("cacheWriteTokens"));
                ModelPrice p = PRICES.getOrDefault(modelId, DEFAULT_PRICE);
                long cents = cents(mInput, p.inputCentsPerMTok())
                        + cents(mOutput, p.outputCentsPerMTok())
                        + cents(mCacheRead, p.cacheReadCentsPerMTok())
                        + cents(mCacheWrite, p.cacheWriteCentsPerMTok());
                costCents += cents;
                perModel.add(new ModelUsage(modelId, mCalls, mInput, mOutput,
                        mCacheRead, mCacheWrite, cents));
            }
        }
        return new MonthSummary(month, calls, input, output, cacheRead, cacheWrite, costCents, perModel);
    }

    private static long longFrom(Object v) {
        if (v instanceof Number n) return n.longValue();
        return 0L;
    }

    /** Rounds UP to the nearest cent so a single very small call still registers as 1¢. */
    private static long cents(long tokens, long centsPerMTok) {
        if (tokens <= 0) return 0;
        // tokens * cents / 1_000_000, rounded up
        return (tokens * centsPerMTok + 999_999) / 1_000_000;
    }

    private static String monthKey(Instant now) {
        return MONTH_FMT.format(now.atOffset(ZoneOffset.UTC));
    }

    /** Firestore field paths use '.' as a separator, so dots in model ids would break updates. */
    private static String sanitize(String model) {
        if (model == null || model.isBlank()) return "unknown";
        return model.replace('.', '-');
    }

    public record ModelPrice(long inputCentsPerMTok, long outputCentsPerMTok,
                             long cacheReadCentsPerMTok, long cacheWriteCentsPerMTok) {}

    public record ModelUsage(String model, long calls, long inputTokens, long outputTokens,
                             long cacheReadTokens, long cacheWriteTokens, long estimatedCostCents) {}

    public record MonthSummary(String month, long totalCalls,
                               long totalInputTokens, long totalOutputTokens,
                               long totalCacheReadTokens, long totalCacheWriteTokens,
                               long estimatedCostCents,
                               List<ModelUsage> byModel) {}

    public record UsageReport(MonthSummary currentMonth,
                              long lifetimeCalls,
                              long lifetimeInputTokens,
                              long lifetimeOutputTokens,
                              long lifetimeCacheReadTokens,
                              long lifetimeCacheWriteTokens,
                              long lifetimeEstimatedCostCents) {}
}
