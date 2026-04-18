package com.ogautam.kinkeeper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * A recorded interaction — visit, call, meeting, message thread.
 *
 * Two formats:
 *   ENCOUNTER — single-entry recap: `summary`, `outcome`, `followUp`. Good
 *               for "called Dr K — continue meds 2 more weeks — follow up Oct 3".
 *   THREAD    — verbatim list of messages in `messages`. Good for negotiations
 *               or anything you want to replay later.
 *
 * `links` ties the conversation to subjects via the polymorphic LinkRef model:
 * contacts, members, assets, and/or documents. Same pattern as Documents and
 * Reminders — multi-subject is the norm (e.g. a lawyer call about a house
 * purchase links both the CONTACT lawyer and the HOME asset).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Conversation {
    private String id;
    private String familyId;

    private String title;
    private ConversationFormat format;
    private ConversationChannel channel;
    private Instant occurredAt;

    // ENCOUNTER-format fields
    private String summary;
    private String outcome;
    private String followUp;

    // THREAD-format field
    private List<ConversationMessage> messages;

    private List<LinkRef> links;

    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
}
