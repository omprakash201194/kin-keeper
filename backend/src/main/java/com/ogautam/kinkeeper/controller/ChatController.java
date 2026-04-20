package com.ogautam.kinkeeper.controller;

import com.ogautam.kinkeeper.agent.AttachmentService;
import com.ogautam.kinkeeper.agent.KinKeeperAgent;
import com.ogautam.kinkeeper.agent.KinKeeperAgent.ChatReply;
import com.ogautam.kinkeeper.agent.KinKeeperAgent.ChatTurn;
import com.ogautam.kinkeeper.model.ChatAttachment;
import com.ogautam.kinkeeper.model.ChatMessage;
import com.ogautam.kinkeeper.model.ChatSession;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.ogautam.kinkeeper.service.ChatSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final KinKeeperAgent agent;
    private final ChatSessionService sessions;
    private final AttachmentService attachments;

    public ChatController(KinKeeperAgent agent, ChatSessionService sessions, AttachmentService attachments) {
        this.agent = agent;
        this.sessions = sessions;
        this.attachments = attachments;
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<ChatSession>> list(@AuthenticationPrincipal FirebaseUserPrincipal principal) throws Exception {
        return ResponseEntity.ok(sessions.listForUser(principal));
    }

    @PostMapping("/sessions")
    public ResponseEntity<ChatSession> create(@AuthenticationPrincipal FirebaseUserPrincipal principal) throws Exception {
        return ResponseEntity.ok(sessions.create(principal));
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<?> get(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                 @PathVariable String id) throws Exception {
        ChatSession session = sessions.get(principal, id);
        List<ChatMessage> messages = sessions.listMessages(principal, id);
        return ResponseEntity.ok(Map.of("session", session, "messages", messages));
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                    @PathVariable String id) throws Exception {
        sessions.delete(principal, id);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    @PostMapping("/sessions/{id}/message")
    public ResponseEntity<?> message(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                     @PathVariable String id,
                                     @RequestBody SendRequest body) throws Exception {
        ChatSession session = sessions.get(principal, id);

        // Resolve effective attachments: new uploads this turn if present, otherwise
        // whatever the session still has staged from prior turns. Supports both the
        // list-form `attachmentIds` (primary) and the legacy singular `attachmentId`.
        List<String> incoming = new ArrayList<>();
        if (body.attachmentIds() != null) {
            for (String aid : body.attachmentIds()) {
                if (aid != null && !aid.isBlank()) incoming.add(aid);
            }
        }
        if (incoming.isEmpty() && body.attachmentId() != null && !body.attachmentId().isBlank()) {
            incoming.add(body.attachmentId());
        }

        List<String> effectiveIds;
        if (!incoming.isEmpty()) {
            effectiveIds = incoming;
            sessions.setPendingAttachments(principal, id, incoming);
        } else if (session.getPendingAttachmentIds() != null && !session.getPendingAttachmentIds().isEmpty()) {
            effectiveIds = session.getPendingAttachmentIds();
        } else if (session.getPendingAttachmentId() != null && !session.getPendingAttachmentId().isBlank()) {
            effectiveIds = List.of(session.getPendingAttachmentId());
        } else {
            effectiveIds = List.of();
        }

        if ((body.message() == null || body.message().isBlank()) && effectiveIds.isEmpty()) {
            throw new IllegalArgumentException("message or attachmentId is required");
        }

        List<ChatMessage> existing = sessions.listMessages(principal, id);
        List<ChatTurn> turns = new ArrayList<>(existing.size());
        for (ChatMessage m : existing) {
            turns.add(new ChatTurn(m.getRole(), m.getContent()));
        }

        String userContent = body.message() == null ? "" : body.message();

        // Capture filename + mime for every staged attachment so history can render
        // previews even if the agent doesn't save each one.
        List<ChatAttachment> attList = new ArrayList<>();
        for (String aid : effectiveIds) {
            try {
                AttachmentService.Loaded att = attachments.load(aid, principal.uid());
                attList.add(ChatAttachment.builder()
                        .fileName(att.fileName())
                        .mimeType(att.mimeType())
                        .build());
            } catch (Exception ignored) {
                // Redis entry expired — skip preview but still pass the id along.
            }
        }
        String renderedUser = renderUserContent(userContent, attList);
        // reason: keep the legacy singular fields populated for the first attachment so
        // older frontends that read them stay functional during the rollout window.
        String firstName = attList.isEmpty() ? null : attList.get(0).getFileName();
        String firstMime = attList.isEmpty() ? null : attList.get(0).getMimeType();
        ChatMessage userMsg = sessions.appendMessage(principal, id, "user",
                renderedUser, firstName, firstMime, attList.isEmpty() ? null : attList);

        ChatReply reply = agent.chat(principal, turns, userContent, effectiveIds, id);

        // Pair saved Drive doc ids with the message attachments in order.
        List<String> savedDocIds = sessions.consumeRecentlySavedDocuments(id);
        if (!savedDocIds.isEmpty()) {
            if (!attList.isEmpty()) {
                sessions.backfillMessageAttachments(id, userMsg.getId(), attList, savedDocIds);
                // Keep legacy field in sync for clients still reading the singular.
                sessions.setMessageDocumentId(id, userMsg.getId(), savedDocIds.get(0));
            }
        }

        ChatMessage assistant = sessions.appendMessage(principal, id, "assistant", reply.text());
        return ResponseEntity.ok(Map.of("reply", reply.text(), "messageId", assistant.getId()));
    }

    private static String renderUserContent(String userContent, List<ChatAttachment> attachments) {
        if (attachments.isEmpty()) return userContent;
        StringBuilder tags = new StringBuilder();
        for (ChatAttachment a : attachments) {
            tags.append("\n[attached: ").append(a.getFileName() == null ? "" : a.getFileName()).append("]");
        }
        if (userContent.isBlank()) return tags.toString().trim();
        return userContent + "\n" + tags.toString().trim();
    }

    @PostMapping("/attachments")
    public ResponseEntity<?> stageAttachment(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                             @RequestParam("file") MultipartFile file) throws Exception {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("file is empty");
        }
        String mime = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        AttachmentService.Staged staged = attachments.stage(
                principal.uid(),
                file.getOriginalFilename(),
                mime,
                file.getBytes());
        return ResponseEntity.ok(Map.of(
                "attachmentId", staged.id(),
                "fileName", staged.fileName() == null ? "" : staged.fileName(),
                "mimeType", staged.mimeType(),
                "size", staged.size()));
    }

    public record SendRequest(String message, String attachmentId, List<String> attachmentIds) {}
}
