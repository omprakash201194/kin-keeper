package com.ogautam.kinkeeper.controller;

import com.ogautam.kinkeeper.agent.KinKeeperAgent;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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
                                     @RequestBody Map<String, String> body) {
        // TODO: pass message to KinKeeperAgent, return AI response
        String userMessage = body.get("message");
        return ResponseEntity.ok(Map.of(
                "response", "Chat is not yet implemented. You said: " + userMessage
        ));
    }
}
