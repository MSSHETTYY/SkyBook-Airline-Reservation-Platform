package com.skybook.assistant.web;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.RestController;

import com.skybook.assistant.api.v1.AssistantApi;
import com.skybook.assistant.api.v1.model.AssistantReply;
import com.skybook.assistant.api.v1.model.AssistantRequest;
import com.skybook.assistant.service.AssistantService;

@RestController
class AssistantController implements AssistantApi {

    private final AssistantService assistant;

    AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AssistantReply> sendMessage(AssistantRequest request) {
        JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        AssistantService.Reply reply = assistant.reply(auth.getToken().getSubject(), request.getConversationId(),
                request.getMessage(), "Bearer " + auth.getToken().getTokenValue());
        return ResponseEntity.ok(new AssistantReply().conversationId(reply.conversationId()).reply(reply.text()));
    }
}
