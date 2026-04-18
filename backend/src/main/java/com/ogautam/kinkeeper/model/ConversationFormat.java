package com.ogautam.kinkeeper.model;

public enum ConversationFormat {
    /** Single-entry summary: what was discussed, what was decided, what to do next. */
    ENCOUNTER,
    /** A sequence of back-and-forth messages captured verbatim (e.g. a chat log). */
    THREAD
}
