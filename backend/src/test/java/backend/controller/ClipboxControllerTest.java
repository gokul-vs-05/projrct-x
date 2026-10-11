package backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import backend.service.DeviceService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentCaptor.forClass;

class ClipboxControllerTest {
    @Test
    void joiningAndAddingClipBroadcastsTheRoomState() {
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        ClipboxController controller = new ClipboxController(messagingTemplate, new DeviceService());
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SEND);
        headers.setSessionId("session-1");

        controller.handle(Map.of(
                "type", "join",
                "room", "ABCD23",
                "deviceId", "device-1"
        ), headers);
        controller.handle(Map.of(
                "type", "add",
                "room", "ABCD23",
                "deviceId", "device-1",
                "text", "Hello from another device"
        ), headers);

        var stateCaptor = forClass(Map.class);
        verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/clipbox/ABCD23"), stateCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> finalState = (Map<String, Object>) stateCaptor.getAllValues().get(1);
        assertEquals(1, finalState.get("devices"));
        @SuppressWarnings("unchecked")
        List<ClipboxController.Clip> items = (List<ClipboxController.Clip>) finalState.get("items");
        assertEquals("Hello from another device", items.get(0).getText());
    }
}
