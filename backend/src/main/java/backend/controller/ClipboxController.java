package backend.controller;

import backend.service.DeviceService;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class ClipboxController {
    private static final int MAX_DEVICES = 10;
    private static final int MAX_CLIPS = 30;
    private static final int MAX_CLIP_LENGTH = 20_000;
    private static final int MAX_SENDS_PER_WINDOW = 20;
    private static final long RATE_WINDOW_MILLIS = 10_000;
    private static final long ROOM_TTL_MINUTES = 30;

    private final SimpMessagingTemplate messagingTemplate;
    private final DeviceService deviceService;
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, String> sessionRooms = new HashMap<>();
    private final Map<String, Deque<Long>> sendTimes = new HashMap<>();

    public ClipboxController(SimpMessagingTemplate messagingTemplate, DeviceService deviceService) {
        this.messagingTemplate = messagingTemplate;
        this.deviceService = deviceService;
    }

    @MessageMapping("/clipbox")
    public synchronized void handle(Map<String, Object> message, StompHeaderAccessor headers) {
        pruneExpiredRooms();
        String sessionId = headers.getSessionId();
        if (sessionId == null) {
            return;
        }

        String type = stringValue(message.get("type"));
        String roomCode = stringValue(message.get("room")).toUpperCase();
        String deviceId = stringValue(message.get("deviceId"));
        if (!roomCode.matches("[A-Z0-9]{4,12}")) {
            sendError(roomCode, sessionId, "Room codes must be 4–12 letters or numbers.");
            return;
        }

        if ("join".equals(type)) {
            join(roomCode, deviceId, sessionId);
            return;
        }

        Room room = rooms.get(roomCode);
        if (room == null || !roomCode.equals(sessionRooms.get(sessionId))) {
            sendError(roomCode, sessionId, "Join this room before sending clips.");
            return;
        }

        if ("add".equals(type)) {
            addClip(roomCode, room, sessionId, message.get("text"));
        } else if ("delete".equals(type)) {
            String id = stringValue(message.get("id"));
            room.items.removeIf(item -> item.id.equals(id));
            broadcastState(roomCode, room);
        } else if ("clear".equals(type)) {
            room.items.clear();
            broadcastState(roomCode, room);
        } else {
            sendError(roomCode, sessionId, "Unsupported Clipbox action.");
        }
    }

    private void join(String roomCode, String deviceId, String sessionId) {
        if (!deviceId.matches("[A-Za-z0-9-]{1,64}")) {
            sendError(roomCode, sessionId, "Invalid device identifier.");
            return;
        }

        Room room = rooms.computeIfAbsent(roomCode, ignored -> new Room());
        room.expiresAt = null;
        String previousRoom = sessionRooms.put(sessionId, roomCode);
        if (previousRoom != null && !previousRoom.equals(roomCode)) {
            removeSession(previousRoom, sessionId);
        }

        Set<String> devices = new HashSet<>(room.sessions.values());
        if (!devices.contains(deviceId) && devices.size() >= MAX_DEVICES) {
            sessionRooms.remove(sessionId);
            sendError(roomCode, sessionId, "This room already has the maximum of 10 devices.");
            return;
        }

        room.sessions.put(sessionId, deviceId);
        broadcastState(roomCode, room);
    }

    private void addClip(String roomCode, Room room, String sessionId, Object rawText) {
        if (!(rawText instanceof String text) || text.isBlank()) {
            sendError(roomCode, sessionId, "Enter some text before sending.");
            return;
        }
        if (text.length() > MAX_CLIP_LENGTH) {
            sendError(roomCode, sessionId, "Clips are limited to 20,000 characters.");
            return;
        }
        if (!allowSend(room.sessions.get(sessionId))) {
            sendError(roomCode, sessionId, "Send limit reached. Please wait a few seconds.");
            return;
        }

        String deviceId = room.sessions.get(sessionId);
        Clip item = new Clip(UUID.randomUUID().toString(), text, Instant.now().toString(), deviceId);
        room.items.add(0, item);
        if (room.items.size() > MAX_CLIPS) {
            room.items.remove(room.items.size() - 1);
        }
        broadcastState(roomCode, room);
    }

    private boolean allowSend(String sessionId) {
        long now = System.currentTimeMillis();
        Deque<Long> times = sendTimes.computeIfAbsent(sessionId, ignored -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() >= RATE_WINDOW_MILLIS) {
            times.removeFirst();
        }
        if (times.size() >= MAX_SENDS_PER_WINDOW) {
            return false;
        }
        times.addLast(now);
        return true;
    }

    @EventListener
    public synchronized void onDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        String roomCode = sessionRooms.remove(sessionId);
        if (roomCode != null) {
            removeSession(roomCode, sessionId);
        }
    }

    private void removeSession(String roomCode, String sessionId) {
        Room room = rooms.get(roomCode);
        if (room == null) {
            return;
        }
        String deviceId = room.sessions.remove(sessionId);
        if (deviceId != null) {
            deviceService.setOnline(deviceId, false);
        }
        if (room.sessions.isEmpty()) {
            room.expiresAt = Instant.now().plus(ROOM_TTL_MINUTES, ChronoUnit.MINUTES);
        }
        broadcastState(roomCode, room);
    }

    private void broadcastState(String roomCode, Room room) {
        Set<String> devices = new HashSet<>(room.sessions.values());
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("type", "state");
        state.put("room", roomCode);
        state.put("devices", devices.size());
        state.put("items", new ArrayList<>(room.items));
        messagingTemplate.convertAndSend("/topic/clipbox/" + roomCode, state);
    }

    private void sendError(String roomCode, String sessionId, String reason) {
        Map<String, Object> error = Map.of("type", "error", "reason", reason);
        messagingTemplate.convertAndSend("/topic/clipbox/" + roomCode + "/errors", error);
    }

    private String stringValue(Object value) {
        return value instanceof String string ? string : "";
    }

    @Scheduled(fixedDelay = 60_000)
    public synchronized void pruneExpiredRooms() {
        Instant now = Instant.now();
        rooms.entrySet().removeIf(entry ->
                entry.getValue().expiresAt != null && !entry.getValue().expiresAt.isAfter(now));
        long cutoff = System.currentTimeMillis() - RATE_WINDOW_MILLIS;
        sendTimes.values().forEach(times -> {
            while (!times.isEmpty() && times.peekFirst() <= cutoff) {
                times.removeFirst();
            }
        });
        sendTimes.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private static final class Room {
        private final Map<String, String> sessions = new HashMap<>();
        private final List<Clip> items = new ArrayList<>();
        private Instant expiresAt;
    }

    public static final class Clip {
        private final String id;
        private final String text;
        private final String ts;
        private final String deviceId;

        private Clip(String id, String text, String ts, String deviceId) {
            this.id = id;
            this.text = text;
            this.ts = ts;
            this.deviceId = deviceId;
        }

        public String getId() {
            return id;
        }

        public String getText() {
            return text;
        }

        public String getTs() {
            return ts;
        }

        public String getDeviceId() {
            return deviceId;
        }
    }
}
