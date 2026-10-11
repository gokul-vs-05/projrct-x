import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { createPairingPayload, getPairingWebSocketUrl } from "../utils/pairing.js";

export default function PairingQr({ type, room, label = "Show Android QR" }) {
  const [open, setOpen] = useState(false);
  const [dataUrl, setDataUrl] = useState("");
  const [error, setError] = useState("");
  const [serverUrl, setServerUrl] = useState(() => (
    localStorage.getItem("syncsphere.pairing.serverUrl") || getPairingWebSocketUrl()
  ));

  useEffect(() => {
    if (!open) return undefined;
    let active = true;
    setError("");
    let payload;
    try {
      payload = createPairingPayload(type, room, serverUrl);
    } catch (generateError) {
      setError(`Could not create pairing QR: ${generateError.message}`);
      return () => { active = false; };
    }
    QRCode.toDataURL(payload, {
      errorCorrectionLevel: "M",
      margin: 2,
      width: 260,
      color: { dark: "#0f1e3d", light: "#ffffff" }
    }).then(value => {
      if (active) setDataUrl(value);
    }).catch(generateError => {
      if (active) setError(`Could not create pairing QR: ${generateError.message}`);
    });
    return () => { active = false; };
  }, [open, room, serverUrl, type]);

  return (
    <div className="pairing-qr">
      <button className="btn" type="button" onClick={() => setOpen(value => !value)}>
        {open ? "Hide Android QR" : label}
      </button>
      {open && (
        <div className="pairing-qr-panel">
          <p className="muted">In SyncSphere Android, tap <b>Scan QR</b> and point the camera here.</p>
          <label className="pairing-qr-server">
            Phone-reachable backend WebSocket
            <input
              className="search"
              value={serverUrl}
              onChange={event => {
                setServerUrl(event.target.value.trim());
                localStorage.setItem("syncsphere.pairing.serverUrl", event.target.value.trim());
              }}
              placeholder="ws://192.168.1.10:8080/ws"
              spellCheck="false"
            />
          </label>
          {["localhost", "127.0.0.1"].some(host => serverUrl.includes(host)) && (
            <p className="pairing-qr-hint">For Android on Wi‑Fi, replace localhost with this computer’s LAN IP address.</p>
          )}
          {error ? <p className="msg error">{error}</p> : dataUrl ? <img src={dataUrl} alt={`QR code to pair ${type === "screen" ? "screen mirroring" : "Clipbox"} room ${room}`} /> : <p className="muted">Generating QR code…</p>}
          <code>{room}</code>
          <p className="pairing-qr-hint">Anyone who scans this code can join this session. Only share it with people you trust.</p>
        </div>
      )}
    </div>
  );
}
