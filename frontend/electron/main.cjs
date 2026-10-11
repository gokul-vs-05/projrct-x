const { app, BrowserWindow, globalShortcut, clipboard, ipcMain } = require("electron");
const path = require("path");
let win;
function createWindow(){ win=new BrowserWindow({width:1400,height:900,webPreferences:{preload:path.join(__dirname,"preload.cjs"),contextIsolation:true,nodeIntegration:false}}); win.loadURL(process.env.SYNCSPHERE_URL || "http://localhost:5173/remote"); }
app.whenReady().then(()=>{ createWindow(); globalShortcut.register("CommandOrControl+Shift+C",()=>win?.webContents.send("clipboard-from-windows",clipboard.readText())); globalShortcut.register("CommandOrControl+Shift+V",()=>win?.webContents.send("request-android-clipboard")); ipcMain.on("set-clipboard",(_,text)=>clipboard.writeText(String(text||""))); });
app.on("will-quit",()=>globalShortcut.unregisterAll());
