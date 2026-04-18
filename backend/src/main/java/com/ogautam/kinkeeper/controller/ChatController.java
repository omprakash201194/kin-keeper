package com.ogautam.kinkeeper.controller;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final KinKeeperAgent agent;
    private final ChatSessionService sessions;

    public ChatController(KinKeeperAgent agent, ChatSessionService sessions) {
        this.agent = agent;
        this.sessions = sessions;
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
        if (body.message() == null || body.message().isBlank()) {
            throw new IllegalArgumentException("message is required");
        }

        List<ChatMessage> existing = sessions.listMessages(principal, id);
        List<ChatTurn> turns = new ArrayList<>(existing.size());
        for (ChatMessage m : existing) {
            turns.add(new ChatTurn(m.getRole(), m.getContent()));
        }

        sessions.appendMessage(principal, id, "user", body.message());
        ChatReply reply = agent.chat(principal, turns, body.message());
        ChatMessage assistant = sessions.appendMessage(principal, id, "assistant", reply.text());
        return ResponseEntity.ok(Map.of("reply", reply.text(), "messageId", assistant.getId()));
    }

    public record SendRequest(String message) {}
}
