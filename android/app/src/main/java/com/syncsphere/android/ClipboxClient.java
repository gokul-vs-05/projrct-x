package com.syncsphere.android;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class ClipboxClient {
    public interface Listener {
        void onConnected();
        void onMessage(JSONObject message);
        void onError(String message);
    }

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();
    private final String url;
    private final String room;
    private final Listener listener;
    private WebSocket webSocket;
    private volatile boolean connected;
    private boolean closing;

    public ClipboxClient(String url, String room, Listener listener) {
        this.url = url;
        this.room = room;
        this.listener = listener;
    }

    public void connect() {
        Request request = new Request.Builder().url(url).build();
        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                socket.send("CONNECT\naccept-version:1.2\nhost:syncsphere\nheart-beat:10000,10000\n\n\u0000");
            }

            @Override
            public void onMessage(WebSocket socket, String frame) {
                parseFrame(frame);
            }

            @Override
            public void onMessage(WebSocket socket, ByteString frame) {
                parseFrame(frame.utf8());
            }

            @Override
            public void onFailure(WebSocket socket, Throwable error, Response response) {
                connected = false;
                if (!closing) {
                    listener.onError(error.getMessage() == null ? "Could not connect to Clipbox." : error.getMessage());
                }
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                connected = false;
                if (!closing) {
                    listener.onError("Clipbox disconnected.");
                }
            }
        });
    }

    private void parseFrame(String frame) {
        String normalized = frame.replace("\r\n", "\n");
        for (String raw : normalized.split("\u0000")) {
            String current = raw;
            while (current.startsWith("\n")) {
                current = current.substring(1);
            }
            if (current.startsWith("CONNECTED")) {
                connected = true;
                subscribe("/topic/clipbox/" + room);
                subscribe("/topic/clipbox/" + room + "/errors");
                listener.onConnected();
            } else if (current.startsWith("ERROR")) {
                connected = false;
                listener.onError(frameBody(current, "Clipbox server rejected the connection."));
            } else if (current.startsWith("MESSAGE")) {
                String body = frameBody(current, "");
                try {
                    listener.onMessage(new JSONObject(body));
                } catch (Exception error) {
                    listener.onError("Clipbox received an invalid server update.");
                }
            }
        }
    }

    private String frameBody(String frame, String fallback) {
        int separator = frame.indexOf("\n\n");
        if (separator < 0) {
            return fallback;
        }
        String body = frame.substring(separator + 2).trim();
        return body.isEmpty() ? fallback : body;
    }

    private void subscribe(String destination) {
        sendRaw("SUBSCRIBE\nid:" + UUID.randomUUID() + "\ndestination:" + destination + "\nack:auto\n\n\u0000");
    }

    public void send(String deviceId, String type, JSONObject details) {
        if (!connected || webSocket == null) {
            listener.onError("Join a Clipbox room before sending.");
            return;
        }
        try {
            JSONObject body = new JSONObject(details.toString());
            body.put("type", type);
            body.put("room", room);
            body.put("deviceId", deviceId);
            String json = body.toString();
            int length = json.getBytes(StandardCharsets.UTF_8).length;
            sendRaw("SEND\ndestination:/app/clipbox\ncontent-type:application/json\ncontent-length:"
                    + length + "\n\n" + json + "\u0000");
        } catch (Exception error) {
            listener.onError("Could not send clip: " + error.getMessage());
        }
    }

    private void sendRaw(String frame) {
        if (webSocket != null) {
            webSocket.send(frame);
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public void close() {
        closing = true;
        connected = false;
        if (webSocket != null) {
            webSocket.close(1000, "Leaving Clipbox");
            webSocket = null;
        }
        httpClient.dispatcher().executorService().shutdown();
    }
}
