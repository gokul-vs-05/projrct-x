const { contextBridge, ipcRenderer } = require("electron");
contextBridge.exposeInMainWorld("electronAPI", {
  setClipboard: text => ipcRenderer.send("set-clipboard", text),
  onClipboardFromWindows: cb => ipcRenderer.on("clipboard-from-windows", (_, text) => cb(text)),
  onRequestAndroidClipboard: cb => ipcRenderer.on("request-android-clipboard", cb)
});
