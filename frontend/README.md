# SyncSphere Frontend

React 19 + Vite + React Router + Axios.

## Run

1. Start the Spring Boot backend on http://localhost:8080.
2. In this folder:

```
npm install
npm run dev
```

Open http://localhost:5173.

Vite proxies `/api` to `http://localhost:8080` (see `vite.config.js`), so no CORS setup is needed in development. To point at a different backend, change the proxy target.

## Structure

- `src/services/api.js` - single Axios instance, attaches the JWT, redirects to login on 401
- `src/services/authService.js`, `fileService.js` - API calls
- `src/hooks` - `useAuth`, `useFiles`
- `src/components` - Navbar, Sidebar, UploadForm, FileCard, FileTable, ProtectedRoute, LoadingSpinner
- `src/pages` - Login, Register, Dashboard, RemoteAccess, Clipbox, NotFound

## Backend contract

- `POST /api/auth/login` returns `{ "token": "..." }`
- `GET /api/files` returns an array of files with `fileName`, `fileSize`, `uploadTime` (also accepts `filename`/`name`, `size`, `uploadedAt`)
- Download and delete use the file name: `/api/files/download/{filename}`, `/api/files/delete/{filename}`
- Cross-device Clipbox uses the backend STOMP WebSocket at `/ws` (`VITE_WS_URL` can override the default `ws://localhost:8080/ws`). Open `/clipbox` from the dashboard navigation and join the same room code on the other device. Room data is held in backend memory, with a maximum of 10 devices and 30 clips per room; it expires 30 minutes after the last device leaves. Room codes are shared access, so do not put secrets in a room.
- The Android app registers its manufacturer/model in the dashboard's Connected Devices list while it is connected to a Clipbox room. Set the same backend address in the app; phones on Wi-Fi should use the computer's LAN IP, not `localhost`.
- On Clipbox and Remote Access, use **Show Android QR** and scan it in the matching Android tab to fill the backend address and room/session code. Set the QR panel's **Phone-reachable backend WebSocket** to an address the phone can reach (for example `ws://<PC-LAN-IP>:8080/ws`; `localhost` will not work from another device). QR pairing codes grant access to the selected room/session; share them only with trusted people.
