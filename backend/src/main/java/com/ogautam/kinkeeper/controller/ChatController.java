package com.ogautam.kinkeeper.controller;

import com.ogautam.kinkeeper.agent.AttachmentService;
import com.ogautam.kinkeeper.agent.KinKeeperAgent;
import com.ogautam.kinkeeper.agent.KinKeeperAgent.ChatReply;
import com.ogautam.kinkeeper.agent.KinKeeperAgent.ChatTurn;
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

        // Resolve effective attachment: new upload this turn, or the session's pending one.
        String effectiveAttachmentId = body.attachmentId() != null && !body.attachmentId().isBlank()
                ? body.attachmentId()
                : session.getPendingAttachmentId();

        if ((body.message() == null || body.message().isBlank())
                && (effectiveAttachmentId == null || effectiveAttachmentId.isBlank())) {
            throw new IllegalArgumentException("message or attachmentId is required");
        }

        // Persist a new attachment on the session so follow-up turns stay contextual.
        if (body.attachmentId() != null && !body.attachmentId().isBlank()) {
            sessions.setPendingAttachment(principal, id, body.attachmentId());
        }

        List<ChatMessage> existing = sessions.listMessages(principal, id);
        List<ChatTurn> turns = new ArrayList<>(existing.size());
        for (ChatMessage m : existing) {
            turns.add(new ChatTurn(m.getRole(), m.getContent()));
        }

        String userContent = body.message() == null ? "" : body.message();
        String renderedUser = body.attachmentId() != null && !body.attachmentId().isBlank()
                ? (userContent.isBlank() ? "[attached file]" : userContent + "\n\n[attached file]")
                : userContent;
        sessions.appendMessage(principal, id, "user", renderedUser);

        ChatReply reply = agent.chat(principal, turns, userContent, effectiveAttachmentId, id);
        ChatMessage assistant = sessions.appendMessage(principal, id, "assistant", reply.text());
        return ResponseEntity.ok(Map.of("reply", reply.text(), "messageId", assistant.getId()));
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

    public record SendRequest(String message, String attachmentId) {}
}
