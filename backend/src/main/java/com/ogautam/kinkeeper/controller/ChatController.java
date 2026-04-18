package com.ogautam.kinkeeper.controller;

import com.ogautam.kinkeeper.agent.KinKeeperAgent;
import com.ogautam.kinkeeper.agent.KinKeeperAgent.ChatReply;
import com.ogautam.kinkeeper.agent.KinKeeperAgent.ChatTurn;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
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

    public ChatController(KinKeeperAgent agent) {
        this.agent = agent;
    }

    @PostMapping("/message")
    public ResponseEntity<?> message(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                     @RequestBody ChatRequest body) throws Exception {
        if (body.message() == null || body.message().isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        List<ChatTurn> turns = new ArrayList<>();
        if (body.history() != null) {
            for (HistoryTurn h : body.history()) {
                if (h.text() != null && !h.text().isBlank()) {
                    turns.add(new ChatTurn(h.role(), h.text()));
                }
            }
        }
        ChatReply reply = agent.chat(principal, turns, body.message());
        return ResponseEntity.ok(Map.of("reply", reply.text()));
    }

    public record HistoryTurn(String role, String text) {}
    public record ChatRequest(String message, List<HistoryTurn> history) {}
}
