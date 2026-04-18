package com.ogautam.kinkeeper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One turn within a THREAD-format conversation. `from` is free-form
 * ("Me", "Dr Krushna", a member's name) — not tied to a specific user
 * record, so we can capture exchanges with anyone.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMessage {
    private String id;
    private String from;
    private String content;
    private Instant at;
}
