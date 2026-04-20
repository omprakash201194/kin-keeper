package com.ogautam.kinkeeper.push;

import com.ogautam.kinkeeper.model.Family;
import com.ogautam.kinkeeper.model.PushSubscription;
import com.ogautam.kinkeeper.model.Reminder;
import com.ogautam.kinkeeper.model.ReminderRecurrence;
import com.ogautam.kinkeeper.service.FamilyService;
import com.ogautam.kinkeeper.service.ReminderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Daily reminder digest: every morning at 07:00 UTC (12:30 IST), walks each
 * family's reminders and sends a single notification per user that summarises
 * what's due in the next 7 days and what's overdue.
 *
 * One digest per user per day — frequency is cheap enough that we don't need
 * a "last sent" guard; the cron only fires once.
 */
@Slf4j
@Component
public class ReminderDigestScheduler {

    private final FamilyService familyService;
    private final ReminderService reminderService;
    private final PushService pushService;
    private final PushProperties pushProps;

    public ReminderDigestScheduler(FamilyService familyService,
                                   ReminderService reminderService,
                                   PushService pushService,
                                   PushProperties pushProps) {
        this.familyService = familyService;
        this.reminderService = reminderService;
        this.pushService = pushService;
        this.pushProps = pushProps;
    }

    // 07:00 UTC every day. cron = sec min hour day month dow
    @Scheduled(cron = "0 0 7 * * *", zone = "UTC")
    public void runDailyDigest() {
        if (!pushProps.isEnabled()) {
            log.debug("Reminder digest skipped — VAPID not configured");
            return;
        }
        try {
            List<PushSubscription> subs = pushService.listAll();
            if (subs.isEmpty()) {
                log.debug("Reminder digest skipped — no subscribers");
                return;
            }
            int notified = 0;
            for (PushSubscription sub : subs) {
                try {
                    DigestText digest = buildDigestFor(sub.getUserId());
                    if (digest == null) continue;
                    int status = pushService.send(sub, digest.title(), digest.body(), "/reminders");
                    if (status >= 200 && status < 300) notified++;
                } catch (Exception e) {
                    log.warn("Digest failed for user {}: {}", sub.getUserId(), e.getMessage());
                }
            }
            log.info("Reminder digest: {}/{} subscribers notified", notified, subs.size());
        } catch (Exception e) {
            log.warn("Reminder digest run failed: {}", e.getMessage(), e);
        }
    }

    private DigestText buildDigestFor(String userId) throws Exception {
        Family family = familyService.getFamilyForUser(userId);
        if (family == null) return null;
        Instant now = Instant.now();
        Instant weekOut = now.plus(7, ChronoUnit.DAYS);
        int overdue = 0;
        int thisWeek = 0;
        String firstTitle = null;
        for (Reminder r : reminderService.listByFamily(family.getId())) {
            if (r.isCompleted()) continue;
            if (r.getRecurrence() == ReminderRecurrence.ODOMETER) continue;
            if (r.getDueAt() == null) continue;
            if (r.getDueAt().isBefore(now)) {
                overdue++;
                if (firstTitle == null) firstTitle = r.getTitle();
            } else if (r.getDueAt().isBefore(weekOut)) {
                thisWeek++;
                if (firstTitle == null) firstTitle = r.getTitle();
            }
        }
        if (overdue == 0 && thisWeek == 0) return null;

        StringBuilder body = new StringBuilder();
        if (overdue > 0) {
            body.append(overdue).append(overdue == 1 ? " overdue" : " overdue");
        }
        if (thisWeek > 0) {
            if (body.length() > 0) body.append(" · ");
            body.append(thisWeek).append(" due this week");
        }
        if (firstTitle != null) body.append(" — ").append(firstTitle);
        String title = overdue > 0 ? "Reminders need attention" : "Reminders this week";
        return new DigestText(title, body.toString());
    }

    private record DigestText(String title, String body) {}
}
