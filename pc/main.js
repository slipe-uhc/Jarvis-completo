const { app, BrowserWindow, Tray, Menu, nativeImage, shell, ipcMain, session } = require('electron');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');

let win = null, tray = null, ps = null, quitting = false;

// Script do Windows (System.Speech): reconhece fala e imprime "HEARD:texto"
const PS = [
  '[Console]::OutputEncoding=[System.Text.Encoding]::UTF8',
  'Add-Type -AssemblyName System.Speech',
  '$e=$null',
  "foreach($n in @('pt-BR','pt-PT','en-US')){",
  '  try{ $ci=New-Object System.Globalization.CultureInfo($n); $e=New-Object System.Speech.Recognition.SpeechRecognitionEngine($ci); break }catch{}',
  '}',
  'if(-not $e){ $e=New-Object System.Speech.Recognition.SpeechRecognitionEngine }',
  '$e.SetInputToDefaultAudioDevice()',
  '$e.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))',
  'Register-ObjectEvent -InputObject $e -EventName SpeechRecognized -Action { [Console]::Out.WriteLine("HEARD:" + $Event.SourceEventArgs.Result.Text); [Console]::Out.Flush() } | Out-Null',
  '$e.RecognizeAsync([System.Speech.Recognition.RecognizeMode]::Multiple)',
  '[Console]::Out.WriteLine("READY " + $e.RecognizerInfo.Culture.Name); [Console]::Out.Flush()',
  'while($true){ Start-Sleep -Seconds 1 }'
].join('\r\n');

function send(ch, v) { if (win && !win.isDestroyed()) win.webContents.send(ch, v); }

function startSpeech() {
  if (ps) return;
  const f = path.join(app.getPath('userData'), 'speech.ps1');
  fs.writeFileSync(f, PS, 'utf8');
  ps = spawn('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', f], { windowsHide: true });
  let buf = '';
  ps.stdout.setEncoding('utf8');
  ps.stdout.on('data', (d) => {
    buf += d;
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const l = buf.slice(0, i).trim();
      buf = buf.slice(i + 1);
      if (l.startsWith('HEARD:')) send('heard', l.slice(6));
      else if (l) send('status', l);
    }
  });
  ps.stderr.on('data', (d) => send('status', 'ERRO de voz: ' + String(d).slice(0, 140)));
  ps.on('exit', () => { ps = null; });
}
function stopSpeech() { if (ps) { try { ps.kill(); } catch (e) {} ps = null; } }

function show() { if (win) { win.show(); win.focus(); } }

function createWindow() {
  win = new BrowserWindow({
    width: 1200, height: 820, backgroundColor: '#05080d', autoHideMenuBar: true, title: 'Jarvis',
    icon: path.join(__dirname, 'icon.png'),
    show: !process.argv.includes('--hidden'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, backgroundThrottling: false }
  });
  win.loadFile(path.join(__dirname, 'app', 'index.html'));
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => { if (!url.startsWith('file:')) { e.preventDefault(); shell.openExternal(url); } });
  win.on('close', (e) => { if (!quitting) { e.preventDefault(); win.hide(); } });
}

const lock = app.requestSingleInstanceLock();
if (!lock) {
  app.quit();
} else {
  app.on('second-instance', show);
  app.whenReady().then(() => {
    session.defaultSession.setPermissionRequestHandler((wc, perm, cb) => cb(true));
    createWindow();
    tray = new Tray(nativeImage.createFromPath(path.join(__dirname, 'icon.png')).resize({ width: 16, height: 16 }));
    tray.setToolTip('Jarvis');
    tray.setContextMenu(Menu.buildFromTemplate([
      { label: 'Abrir Jarvis', click: show },
      { label: 'Sair', click: () => { quitting = true; stopSpeech(); app.quit(); } }
    ]));
    tray.on('click', show);
    try { app.setLoginItemSettings({ openAtLogin: true, args: ['--hidden'] }); } catch (e) {}
    ipcMain.on('voice:start', startSpeech);
    ipcMain.on('voice:stop', stopSpeech);
    ipcMain.on('open', (e, u) => { if (/^https?:/i.test(u)) shell.openExternal(u); });
  });
  app.on('before-quit', () => { quitting = true; stopSpeech(); });
  app.on('window-all-closed', () => {});
}
