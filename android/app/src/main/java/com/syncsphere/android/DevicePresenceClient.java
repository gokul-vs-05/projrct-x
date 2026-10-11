package com.syncsphere.android;

import android.os.Build;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Callback;

final class DevicePresenceClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client = new OkHttpClient();

    void register(String webSocketUrl, String deviceId) {
        send(webSocketUrl, deviceId, true);
    }

    void setOffline(String webSocketUrl, String deviceId) {
        send(webSocketUrl, deviceId, false);
    }

    private void send(String webSocketUrl, String deviceId, boolean online) {
        String httpBase = webSocketUrl
                .replaceFirst("^wss://", "https://")
                .replaceFirst("^ws://", "http://")
                .replaceFirst("/ws/?$", "");
        String endpoint = httpBase + (online ? "/api/device/register" :
                "/api/device/" + deviceId + "/status?online=false");
        try {
            Request.Builder builder = new Request.Builder().url(endpoint);
            if (online) {
                JSONObject payload = new JSONObject();
                payload.put("deviceId", deviceId);
                payload.put("deviceName", friendlyDeviceName());
                payload.put("deviceType", "MOBILE");
                payload.put("userEmail", "");
                payload.put("online", true);
                builder.post(RequestBody.create(payload.toString(), JSON));
            } else {
                builder.put(RequestBody.create(new byte[0], null));
            }
            client.newCall(builder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException error) {
                    android.util.Log.w("SyncSphere", "Could not update dashboard device presence", error);
                }
                @Override
                public void onResponse(Call call, Response response) {
                    if (!response.isSuccessful()) {
                        android.util.Log.w("SyncSphere", "Device status request failed with HTTP " + response.code());
                    }
                    response.close();
                }
            });
        } catch (Exception error) {
            android.util.Log.w("SyncSphere", "Could not update dashboard device presence", error);
        }
    }

    static String friendlyDeviceName() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (!model.isEmpty() && !manufacturer.isEmpty()
                && !model.toLowerCase(Locale.ROOT).startsWith(manufacturer.toLowerCase(Locale.ROOT))) {
            return capitalize(manufacturer) + " " + model;
        }
        if (!model.isEmpty()) return model;
        if (!manufacturer.isEmpty()) return capitalize(manufacturer) + " Android device";
        return "Android device";
    }

    private static String capitalize(String value) {
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }
}
