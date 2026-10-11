import { useEffect, useRef, useState } from "react";
import { startRemote, stopRemote, sendClipboard, requestClipboard, sendTouch } from "../services/remoteAccess";
import PairingQr from "../components/PairingQr.jsx";

export default function RemoteAccess() {
  const videoRef = useRef(null);
  const [session, setSession] = useState(() => (crypto.randomUUID().replaceAll("-", "").slice(0, 8)).toUpperCase());
  const [status, setStatus] = useState("waiting");
  const [connected, setConnected] = useState(false);
  const [hasVideo, setHasVideo] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    const onKey = async e => {
      if (e.ctrlKey && e.shiftKey && e.key.toLowerCase() === "c") {
        e.preventDefault();
        try { sendClipboard(await navigator.clipboard.readText()); } catch { setError("Clipboard read was blocked by the browser. Use the Windows companion for a global shortcut."); }
      }
      if (e.ctrlKey && e.shiftKey && e.key.toLowerCase() === "v") {
        e.preventDefault(); requestClipboard();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("keydown", onKey);
      stopRemote();
    };
  }, []);

  useEffect(() => {
    window.electronAPI?.onClipboardFromWindows(text => sendClipboard(text));
    window.electronAPI?.onRequestAndroidClipboard(() => requestClipboard());
  }, []);

  const connect = async () => {
    stopRemote();
    setError(""); setStatus("connecting");
    try {
      await startRemote({
        session, video: videoRef.current,
        onStatus: s => {
          setStatus(s);
          setConnected(["connected", "ICE connected", "ICE completed", "WebRTC connected", "Receiving Android screen"].includes(s));
        },
        onVideo: setHasVideo,
        onClipboard: text => {
          navigator.clipboard?.writeText(text).catch(() => {});
          window.electronAPI?.setClipboard(text);
        },
        onError: e => {
          const message = String(e?.message || e);
          console.error("[SyncSphere WebRTC] Remote access error", e);
          setError(message);
          setStatus("error");
        }
      });
    } catch (e) { setError(e.message); setStatus("error"); }
  };

  const disconnect = () => {
    stopRemote();
    setConnected(false);
    setHasVideo(false);
    setStatus("waiting");
  };

  const handleVideoClick = e => {
    if (!videoRef.current) return;
    const r = videoRef.current.getBoundingClientRect();
    sendTouch((e.clientX - r.left) / r.width, (e.clientY - r.top) / r.height);
  };

  return <main className="content remote-page">
    <section className="card remote-card">
      <h1>Android Remote Access</h1>
      <p className="muted">Connect here first, then scan the Android pairing QR and approve screen capture on your phone.</p>
      <div className="pair-code">{session}</div>
      <PairingQr type="screen" room={session} label="Show Android pairing QR" />
      <button className="btn btn-primary" onClick={connect} disabled={status !== "waiting" && status !== "error"}>Connect Android</button>
      {status !== "waiting" && <button className="btn" onClick={disconnect}>Stop session</button>}
      <span className="remote-status">Status: <b>{status}</b></span>
      {connected && !hasVideo && <p className="muted">WebRTC connected; waiting for the Android video track.</p>}
      {error && <p className="msg error">{error}</p>}
      <div className="video-wrap">
        <video ref={videoRef} muted playsInline onClick={handleVideoClick} />
        {!hasVideo && <div className="video-placeholder">{connected ? "Connected; waiting for video…" : "Waiting for Android screen…"}</div>}
      </div>
      <div className="shortcut-help">
        <b>Clipboard:</b> Ctrl + Shift + C sends Windows clipboard · Ctrl + Shift + V requests Android clipboard.
      </div>
    </section>
  </main>;
}
