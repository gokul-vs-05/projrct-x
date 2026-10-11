import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import Navbar from "../components/Navbar.jsx";
import Sidebar from "../components/Sidebar.jsx";
import PairingQr from "../components/PairingQr.jsx";
import useAuth from "../hooks/useAuth.js";
import { connectClipbox } from "../services/clipboxService.js";

const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const MAX_CLIP_LENGTH = 20000;

function createRoomCode() {
  const values = crypto.getRandomValues(new Uint32Array(6));
  return Array.from(values, value => CODE_ALPHABET[value % CODE_ALPHABET.length]).join("");
}

function getDeviceId() {
  let deviceId = localStorage.getItem("syncsphere.clipbox.device");
  if (!deviceId) {
    deviceId = crypto.randomUUID();
    localStorage.setItem("syncsphere.clipbox.device", deviceId);
  }
  return deviceId;
}

export default function Clipbox() {
  const { email, logout } = useAuth();
  const [searchParams, setSearchParams] = useSearchParams();
  const clientRef = useRef(null);
  const seenItemsRef = useRef(new Set());
  const initializedRoomRef = useRef(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [room, setRoom] = useState(() => (searchParams.get("room") || localStorage.getItem("syncsphere.clipbox.room") || "").toUpperCase());
  const roomRef = useRef(room);
  const [connected, setConnected] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [status, setStatus] = useState("Enter or generate a code to start a shared room.");
  const [statusType, setStatusType] = useState("");
  const [items, setItems] = useState([]);
  const [deviceCount, setDeviceCount] = useState(0);
  const [text, setText] = useState("");
  const [autoCopy, setAutoCopy] = useState(() => localStorage.getItem("syncsphere.clipbox.autoCopy") === "1");
  const autoCopyRef = useRef(autoCopy);

  useEffect(() => () => {
    clientRef.current?.deactivate();
  }, []);

  const setMessage = (message, type = "") => {
    setStatus(message);
    setStatusType(type);
  };

  const receiveMessage = message => {
    if (message.room && message.room !== roomRef.current) return;
    if (message.type === "state") {
      setItems(message.items || []);
      setDeviceCount(message.devices || 0);
      setConnected(true);
      setConnecting(false);
      setMessage(`Room ${message.room} · ${message.devices} ${message.devices === 1 ? "device" : "devices"} connected`, "success");
      const latest = message.items?.[0];
      if (!initializedRoomRef.current) {
        (message.items || []).forEach(item => seenItemsRef.current.add(item.id));
        initializedRoomRef.current = true;
      } else if (autoCopyRef.current && latest && latest.deviceId !== getDeviceId() && !seenItemsRef.current.has(latest.id)) {
        seenItemsRef.current.add(latest.id);
        navigator.clipboard?.writeText(latest.text).catch(() => {
          setMessage("A new clip arrived, but browser permissions blocked automatic clipboard access.", "error");
        });
      }
    } else if (message.type === "devices") {
      setDeviceCount(message.devices || 0);
      setMessage(`Room ${room} · ${message.devices} ${message.devices === 1 ? "device" : "devices"} connected`, "success");
    }
  };

  const connect = nextRoom => {
    const normalizedRoom = nextRoom.trim().toUpperCase();
    if (!/^[A-Z0-9]{4,12}$/.test(normalizedRoom)) {
      setMessage("Room codes must be 4–12 letters or numbers.", "error");
      return;
    }

    clientRef.current?.deactivate();
    setRoom(normalizedRoom);
    roomRef.current = normalizedRoom;
    localStorage.setItem("syncsphere.clipbox.room", normalizedRoom);
    setSearchParams({ room: normalizedRoom });
    setItems([]);
    setDeviceCount(0);
    setConnected(false);
    setConnecting(true);
    setMessage("Connecting to shared room…");
    seenItemsRef.current.clear();
    initializedRoomRef.current = false;

    const client = connectClipbox({
      room: normalizedRoom,
      deviceId: getDeviceId(),
      onMessage: receiveMessage,
      onConnect: () => setMessage("Connected. Joining room…"),
      onError: error => {
        setConnecting(false);
        setMessage(error.message, "error");
      }
    });
    clientRef.current = client;
  };

  const disconnect = () => {
    clientRef.current?.deactivate();
    clientRef.current = null;
    setConnected(false);
    setConnecting(false);
    setItems([]);
    setDeviceCount(0);
    setSearchParams({});
    setMessage("Disconnected. Enter or generate a code to start a shared room.");
  };

  const send = (type, payload = {}) => {
    const client = clientRef.current;
    if (!connected || !client?.connected) {
      setMessage("Connect to a room before sending clips.", "error");
      return;
    }
    client.publish({
      destination: "/app/clipbox",
      body: JSON.stringify({ type, room, deviceId: getDeviceId(), ...payload })
    });
  };

  const copyText = async (value, label = "Clip") => {
    try {
      await navigator.clipboard.writeText(value);
      setMessage(`${label} copied to clipboard.`, "success");
    } catch (error) {
      setMessage(`Could not copy to clipboard: ${error.message}`, "error");
    }
  };

  const copyRoomLink = async () => {
    const link = `${window.location.origin}/clipbox?room=${encodeURIComponent(room)}`;
    await copyText(link, "Room link");
  };

  const pasteAndSend = async () => {
    try {
      const clipboardText = await navigator.clipboard.readText();
      setText(clipboardText);
      if (clipboardText.trim()) send("add", { text: clipboardText });
    } catch (error) {
      setMessage(`Clipboard access was blocked: ${error.message}. Paste into the text box instead.`, "error");
    }
  };

  const submitClip = () => {
    if (!text.trim()) {
      setMessage("Enter some text before sending.", "error");
      return;
    }
    if (text.length > MAX_CLIP_LENGTH) {
      setMessage(`Clips are limited to ${MAX_CLIP_LENGTH.toLocaleString()} characters.`, "error");
      return;
    }
    send("add", { text });
    setText("");
  };

  return (
    <div className="shell">
      <Navbar email={email} onLogout={logout} onMenu={() => setMenuOpen(value => !value)} />
      <Sidebar open={menuOpen} onClose={() => setMenuOpen(false)} activePage="clipbox" fileCount={0} />
      <main className="content clipbox-page">
        <div className="page-head">
          <h1>Clipbox</h1>
          <p className="muted">Share text and clipboard snippets between devices in the same room.</p>
        </div>

        <section className="card clipbox-card">
          <div className="clipbox-room-bar">
            <label className="clipbox-room-field">
              <span>Room code</span>
              <input
                className="search clipbox-room-input"
                value={room}
                maxLength={12}
                autoComplete="off"
                placeholder="Enter a code"
                onChange={event => setRoom(event.target.value.toUpperCase().replace(/[^A-Z0-9]/g, ""))}
                onKeyDown={event => event.key === "Enter" && connect(room)}
                disabled={connected || connecting}
              />
            </label>
            {!connected && <button className="btn btn-primary" onClick={() => connect(room)} disabled={connecting}>Join room</button>}
            {!connected && <button className="btn" onClick={() => { const code = createRoomCode(); setRoom(code); connect(code); }} disabled={connecting}>New code</button>}
            {connected && <button className="btn" onClick={copyRoomLink}>Copy invite link</button>}
            {connected && <PairingQr type="clipbox" room={room} />}
            {(connected || connecting) && <button className="btn btn-danger" onClick={disconnect}>Leave room</button>}
          </div>
          <p className={`clipbox-status ${statusType}`} role="status">{status}</p>

          <label className="clipbox-compose-label" htmlFor="clipbox-text">Text to share</label>
          <textarea
            id="clipbox-text"
            className="clipbox-textarea"
            value={text}
            maxLength={MAX_CLIP_LENGTH}
            placeholder="Paste or type text to share with every device in this room…"
            onChange={event => setText(event.target.value)}
            onKeyDown={event => {
              if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) submitClip();
            }}
            disabled={!connected}
          />
          <div className="clipbox-actions">
            <button className="btn btn-primary" onClick={submitClip} disabled={!connected}>Send clip</button>
            <button className="btn" onClick={pasteAndSend} disabled={!connected}>Paste from clipboard &amp; send</button>
            <button className="btn btn-danger" onClick={() => window.confirm("Delete every clip in this room?") && send("clear")} disabled={!connected || items.length === 0}>Clear room</button>
            <span className="clipbox-count">{text.length.toLocaleString()} / {MAX_CLIP_LENGTH.toLocaleString()}</span>
          </div>
          <label className="clipbox-auto-copy">
            <input
              type="checkbox"
              checked={autoCopy}
              onChange={event => {
                setAutoCopy(event.target.checked);
                autoCopyRef.current = event.target.checked;
                localStorage.setItem("syncsphere.clipbox.autoCopy", event.target.checked ? "1" : "0");
              }}
            />
            Automatically copy clips received from other devices
          </label>
          <p className="clipbox-security-note">Room contents are kept in server memory and expire 30 minutes after the last device leaves. Anyone with the room code can read its clips; do not share passwords or other secrets.</p>
        </section>

        <section className="card">
          <div className="table-head">
            <h2>Shared clips</h2>
            <span className="muted">{connected ? `${deviceCount} connected ${deviceCount === 1 ? "device" : "devices"}` : "Join a room to see its clips"}</span>
          </div>
          {!items.length ? (
            <p className="empty">{connected ? "Nothing in this room yet. Send your first clip." : "No room joined."}</p>
          ) : (
            <div className="clipbox-list">
              {items.map(item => (
                <article className="clipbox-item" key={item.id}>
                  <div className="clipbox-item-content">
                    <pre>{item.text}</pre>
                    <small>{new Date(item.ts).toLocaleString()}</small>
                  </div>
                  <div className="clipbox-item-actions">
                    <button className="btn btn-small" onClick={() => copyText(item.text)}>Copy</button>
                    <button className="btn btn-small btn-danger" onClick={() => send("delete", { id: item.id })}>Delete</button>
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>
      </main>
    </div>
  );
}
