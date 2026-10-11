package backend.controller;

import backend.model.SignalMessage;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
public class SignalingController {
    private final SimpMessagingTemplate messagingTemplate;

    public SignalingController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/signal")
    public void signal(SignalMessage message) {
        if (message.getSessionId() == null || message.getSessionId().isBlank()) return;
        messagingTemplate.convertAndSend(
            "/topic/signal/" + message.getSessionId(), message
        );
    }
}
