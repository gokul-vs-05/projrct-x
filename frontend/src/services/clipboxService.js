import { Client } from "@stomp/stompjs";
import { getPairingWebSocketUrl } from "../utils/pairing.js";

function getBrokerUrl() {
  return getPairingWebSocketUrl();
}

export function connectClipbox({ room, deviceId, onMessage, onError, onConnect }) {
  const client = new Client({
    brokerURL: getBrokerUrl(),
    reconnectDelay: 3000,
    onConnect: () => {
      client.subscribe(`/topic/clipbox/${room}`, frame => {
        try {
          onMessage(JSON.parse(frame.body));
        } catch (error) {
          onError(new Error("The server sent an invalid Clipbox update.", { cause: error }));
        }
      });
      client.subscribe(`/topic/clipbox/${room}/errors`, frame => {
        try {
          const message = JSON.parse(frame.body);
          onError(new Error(message.reason || "Clipbox request failed."));
        } catch (error) {
          onError(new Error("The server sent an invalid Clipbox error.", { cause: error }));
        }
      });
      client.publish({
        destination: "/app/clipbox",
        body: JSON.stringify({ type: "join", room, deviceId })
      });
      onConnect();
    },
    onWebSocketClose: () => onError(new Error("Clipbox disconnected. Reconnecting…")),
    onStompError: frame => {
      onError(new Error(frame.headers.message || "Clipbox signaling failed."));
    },
    onWebSocketError: () => {
      onError(new Error("Could not connect to the Clipbox server."));
    }
  });
  client.activate();
  return client;
}
