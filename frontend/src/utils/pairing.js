export function getPairingWebSocketUrl() {
  const configuredUrl = import.meta.env.VITE_WS_URL;
  const url = configuredUrl
    ? new URL(configuredUrl)
    : new URL("/ws", window.location.origin);

  if (!configuredUrl && window.location.port === "5173") {
    url.port = "8080";
  }

  if (configuredUrl && ["localhost", "127.0.0.1", "::1"].includes(url.hostname)
    && !["localhost", "127.0.0.1", "::1"].includes(window.location.hostname)) {
    url.hostname = window.location.hostname;
  }

  if (url.protocol === "http:") url.protocol = "ws:";
  if (url.protocol === "https:") url.protocol = "wss:";
  if (url.protocol !== "ws:" && url.protocol !== "wss:") {
    throw new Error("VITE_WS_URL must use ws:// or wss://.");
  }
  if (!url.pathname.endsWith("/ws")) {
    url.pathname = `${url.pathname.replace(/\/$/, "")}/ws`;
  }
  return url.toString();
}

export function createPairingPayload(type, room, serverUrl = getPairingWebSocketUrl()) {
  const normalizedServer = new URL(serverUrl);
  if (normalizedServer.protocol !== "ws:" && normalizedServer.protocol !== "wss:") {
    throw new Error("Pairing server address must use ws:// or wss://.");
  }
  const url = new URL("syncsphere://pair");
  url.searchParams.set("type", type);
  url.searchParams.set("server", normalizedServer.toString());
  url.searchParams.set("room", room);
  return url.toString();
}
