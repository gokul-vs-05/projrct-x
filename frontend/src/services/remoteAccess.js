import { Client } from "@stomp/stompjs";
import { getPairingWebSocketUrl } from "../utils/pairing.js";

let stomp = null;
let pc = null;
let dataChannel = null;
let sessionId = null;
let clientId = null;
let remoteVideo = null;
let callbacks = {};
let pendingIceCandidates = [];
let localIceCandidateCount = 0;
let remoteIceCandidateCount = 0;

const ICE_SERVERS = [
  { urls: import.meta.env.VITE_STUN_URL || "stun:stun.l.google.com:19302" },
  ...(import.meta.env.VITE_TURN_URL ? [{
    urls: import.meta.env.VITE_TURN_URL,
    username: import.meta.env.VITE_TURN_USERNAME || "",
    credential: import.meta.env.VITE_TURN_CREDENTIAL || ""
  }] : [])
];

function sendSignal(type, payload) {
  if (!stomp?.connected) return;
  stomp.publish({ destination: "/app/signal", body: JSON.stringify({
    sessionId, senderId: clientId, type, payload: JSON.stringify(payload)
  })});
}

export function createClientId() {
  return localStorage.getItem("syncsphere.remote.id") || (() => {
    const id = crypto.randomUUID();
    localStorage.setItem("syncsphere.remote.id", id);
    return id;
  })();
}

export async function startRemote({ session, video, onStatus, onVideo, onClipboard, onError }) {
  sessionId = session.trim().toUpperCase();
  clientId = createClientId();
  remoteVideo = video;
  pendingIceCandidates = [];
  localIceCandidateCount = 0;
  remoteIceCandidateCount = 0;
  callbacks = { onStatus, onVideo, onClipboard, onError };
  console.info("[SyncSphere WebRTC] Starting session", sessionId);

  stomp = new Client({
    brokerURL: getPairingWebSocketUrl(),
    reconnectDelay: 3000,
    onConnect: async () => {
      stomp.subscribe(`/topic/signal/${sessionId}`, async frame => {
        try {
          const msg = JSON.parse(frame.body);
          if (msg.senderId === clientId) return;
          console.info("[SyncSphere WebRTC] Signal received", msg.type);
          await handleSignal(msg.type, JSON.parse(msg.payload || "{}"));
        } catch (error) {
          console.error("[SyncSphere WebRTC] Signal handling failed", error);
          callbacks.onError?.(error);
          callbacks.onStatus?.("WebRTC signaling error");
        }
      });
      callbacks.onStatus?.("signaling-connected");
      await createPeer();
    },
    onWebSocketError: e => {
      console.error("[SyncSphere WebRTC] WebSocket error", e);
      callbacks.onError?.(e);
    },
    onStompError: e => {
      console.error("[SyncSphere WebRTC] STOMP error", e);
      callbacks.onError?.(e);
    }
  });
  stomp.activate();
}

async function createPeer() {
  pc = new RTCPeerConnection({ iceServers: ICE_SERVERS });
  pc.onicecandidate = e => {
    if (!e.candidate) {
      console.info("[SyncSphere WebRTC] Local ICE gathering complete", localIceCandidateCount);
      return;
    }
    localIceCandidateCount += 1;
    console.info("[SyncSphere WebRTC] Local ICE candidate", localIceCandidateCount, e.candidate.type || "unknown");
    sendSignal("ICE", e.candidate);
  };
  pc.onconnectionstatechange = () => {
    console.info("[SyncSphere WebRTC] Peer connection state", pc.connectionState);
    callbacks.onStatus?.(`WebRTC ${pc.connectionState}`);
  };
  pc.oniceconnectionstatechange = () => {
    console.info("[SyncSphere WebRTC] ICE connection state", pc.iceConnectionState);
    callbacks.onStatus?.(`ICE ${pc.iceConnectionState}`);
  };
  pc.onicegatheringstatechange = () => console.info("[SyncSphere WebRTC] ICE gathering state", pc.iceGatheringState);
  pc.onsignalingstatechange = () => console.info("[SyncSphere WebRTC] Signaling state", pc.signalingState);
  pc.ontrack = e => {
    if (remoteVideo) {
      console.info("[SyncSphere WebRTC] Remote track received", e.track.kind, e.track.readyState);
      remoteVideo.srcObject = e.streams[0] || new MediaStream([e.track]);
      callbacks.onVideo?.(true);
      remoteVideo.play().catch(error => callbacks.onError?.(error));
      callbacks.onStatus?.("Receiving Android screen");
      e.track.onended = () => callbacks.onVideo?.(false);
    }
  };
  pc.ondatachannel = e => setupDataChannel(e.channel);

}

function setupDataChannel(channel) {
  dataChannel = channel;
  channel.onopen = () => callbacks.onStatus?.("connected");
  channel.onclose = () => callbacks.onStatus?.("datachannel-closed");
  channel.onmessage = e => {
    try {
      const m = JSON.parse(e.data);
      if (m.type === "CLIPBOARD") callbacks.onClipboard?.(m.text || "");
    } catch {}
  };
}

async function handleSignal(type, payload) {
  if (!pc) await createPeer();
  if (type === "ANDROID_READY") {
    const offer = await pc.createOffer({ offerToReceiveVideo: true, offerToReceiveAudio: false });
    await pc.setLocalDescription(offer);
    console.info("[SyncSphere WebRTC] Local offer set; sending offer");
    sendSignal("OFFER", offer);
  } else if (type === "OFFER") {
    await pc.setRemoteDescription(payload);
    console.info("[SyncSphere WebRTC] Remote offer set");
    await applyPendingIceCandidates();
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    console.info("[SyncSphere WebRTC] Local answer set; sending answer");
    sendSignal("ANSWER", answer);
  } else if (type === "ANSWER") {
    await pc.setRemoteDescription(payload);
    console.info("[SyncSphere WebRTC] Remote answer set");
    await applyPendingIceCandidates();
  } else if (type === "ICE") {
    remoteIceCandidateCount += 1;
    console.info("[SyncSphere WebRTC] Remote ICE candidate", remoteIceCandidateCount, payload.type || "unknown");
    if (!pc.remoteDescription) {
      pendingIceCandidates.push(payload);
      return;
    }
    try {
      await pc.addIceCandidate(payload);
    } catch (error) {
      console.error("[SyncSphere WebRTC] Could not add remote ICE candidate", error);
      throw error;
    }
  }
}

async function applyPendingIceCandidates() {
  const candidates = pendingIceCandidates;
  pendingIceCandidates = [];
  console.info("[SyncSphere WebRTC] Applying queued ICE candidates", candidates.length);
  for (const candidate of candidates) {
    await pc.addIceCandidate(candidate);
  }
}

export function sendClipboard(text) {
  if (dataChannel?.readyState === "open") dataChannel.send(JSON.stringify({ type: "CLIPBOARD", text }));
}

export function requestClipboard() {
  if (dataChannel?.readyState === "open") dataChannel.send(JSON.stringify({ type: "CLIPBOARD_REQUEST" }));
}

export function sendTouch(x, y) {
  if (dataChannel?.readyState === "open") dataChannel.send(JSON.stringify({ type: "TOUCH", x, y }));
}

export function stopRemote() {
  dataChannel?.close(); pc?.close(); stomp?.deactivate();
  if (remoteVideo) {
    remoteVideo.srcObject = null;
    callbacks.onVideo?.(false);
  }
  dataChannel = null; pc = null; stomp = null;
  remoteVideo = null;
  sessionId = null;
  pendingIceCandidates = [];
  callbacks.onStatus?.("waiting");
  callbacks = {};
}
