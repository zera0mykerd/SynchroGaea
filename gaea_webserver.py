#!/usr/bin/env python3
"""
SynchroGaea Server  v3
  App  TCP  → :9999
  Web  UI   → http://0.0.0.0:8080
  WebSocket → :8765

Requires: pip install websockets
"""

import asyncio
import struct
import threading
import json
import sys
import ssl
import tempfile
import os
import time
import hashlib
import getpass
import base64
from http.server import HTTPServer, BaseHTTPRequestHandler
from datetime import datetime

class _EventTime:
    def __str__(self):
        return datetime.now().strftime("%Y-%m-%d %H:%M:%S")

EVENT_TIME = _EventTime()

RESET   = "\033[0m"
BOLD    = "\033[1m"
GREEN   = "\033[92m"
CYAN    = "\033[96m"
YELLOW  = "\033[93m"
MAGENTA = "\033[95m"
RED     = "\033[91m"

TCP_PORT  = 9999
HTTP_PORT = 8080
WS_PORT   = 8765
MAX_PAYLOAD = 16_000_000
#TCP_READER_LIMIT = 8 * 1024 * 1024
TCP_READER_LIMIT = 128 * 1024
_ws_clients: set   = set()
_android_writer    = None
_FRAME_HDR  = bytes([1])
_AUDIO_HDR  = bytes([2])
_LOG_HDR    = bytes([255])
_BANNED_IPS = {}
HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="shortcut icon" href="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAkAAAAJCAYAAADgkQYQAAAASElEQVR42mOAgwlK/0FU2Ibs/8h8DAUMB3T/wzBMHF0BiknoChECD0z/gxSBaLg4TCeIA6TBmPG/LZhGEidoEkluIuw7osIJAOeBWtkwJEj3AAAAAElFTkSuQmCC" />
<title>SynchroGaea - SRV</title>
<style type="text/css">
:root{--g:#00ff9d;--d:#001a0f;--s:#003d1f;--r:#ff3c3c;--y:#ffd700;--panel:#0a1f14;--bdr:#1a4d2e}
*{box-sizing:border-box;margin:0;padding:0}
body{background:var(--d);color:var(--g);font-family:'Share Tech Mono',monospace;
     display:flex;flex-direction:column;height:100vh;overflow:hidden}
header{display:flex;align-items:center;justify-content:space-between;
       padding:8px 16px;background:var(--panel);border-bottom:1px solid var(--bdr);flex-shrink:0}
header h1{font-family:'Orbitron',sans-serif;font-size:1rem;letter-spacing:.2em;color:var(--g)}
#st{font-size:.7rem;padding:3px 10px;border-radius:2px;background:#0d2b1a;border:1px solid var(--bdr)}
#st.ok{color:var(--g)}#st.err{color:var(--r)}#st.warn{color:var(--y)}
main{display:flex;flex:1;overflow:hidden}
#vw{flex:1;display:flex;align-items:center;justify-content:center;
    background:#000;position:relative;overflow:hidden}
#vid{max-width:100%;max-height:100%;display:block;object-fit:contain;will-change:transform}
#ovl{position:absolute;top:8px;left:8px;font-size:.65rem;color:rgba(0,255,157,.45);
     pointer-events:none;line-height:1.7;text-shadow:0 0 6px #000}
aside{width:220px;background:var(--panel);border-left:1px solid var(--bdr);
      display:flex;flex-direction:column;padding:12px;gap:10px;overflow-y:auto;flex-shrink:0}
.card{background:var(--d);border:1px solid var(--bdr);border-radius:4px;padding:10px}
.card h2{font-family:'Orbitron',sans-serif;font-size:.6rem;letter-spacing:.15em;
         color:#4dff9e;margin-bottom:8px;border-bottom:1px solid var(--bdr);padding-bottom:4px}
.row{display:flex;align-items:center;justify-content:space-between;gap:6px;margin-bottom:6px}
label{font-size:.65rem;color:#7aff9e;white-space:nowrap}
input[type=range]{flex:1;accent-color:var(--g);height:4px;cursor:pointer}
span.v{font-size:.65rem;color:var(--y);min-width:28px;text-align:right}
button{width:100%;padding:7px;background:var(--s);border:1px solid var(--g);
       color:var(--g);font-family:'Orbitron',sans-serif;font-size:.6rem;letter-spacing:.1em;
       cursor:pointer;border-radius:3px;transition:background .12s,color .12s}
button:hover{background:var(--g);color:var(--d)}
button:active{filter:brightness(1.3)}
button.danger{border-color:var(--r);color:var(--r)}
button.danger:hover{background:var(--r);color:#000}
.trow{display:flex;gap:5px;margin-top:4px}
.trow button{flex:1;font-size:.55rem;padding:5px 2px}
.on{background:var(--g)!important;color:var(--d)!important}
select{background:var(--s);border:1px solid var(--bdr);color:var(--g);
       font-family:inherit;font-size:.65rem;padding:2px 4px;border-radius:2px;width:100%}
#log{flex:1;min-height:80px;background:#000;border:1px solid var(--bdr);
     border-radius:3px;padding:6px;font-size:.6rem;overflow-y:auto;color:#4dff7c;line-height:1.5}
#log p{border-bottom:1px solid #0a1f14;padding:1px 0}
@media(max-width:600px){
  aside{width:100%;border-left:none;border-top:1px solid var(--bdr);
        flex-direction:row;flex-wrap:wrap;max-height:50vh;overflow-y:auto}
  .card{flex:1;min-width:150px}
}
</style>
</head>
<body>
<header>
  <h1>&#x2BC3; SYNCHROGAEA - <span style="font-size: 12px;">webserver</span></h1>
  <div id="st" class="err">APP DISCONNECTED</div>
</header>
<main>
  <div id="vw">
    <img id="vid" alt="">
    <div id="ovl">FPS: -- | --x--</div>
  </div>
  <aside>
    <div class="card">
      <h2>VIDEO</h2>
      <div class="row">
        <label>QUALITY</label>
        <input type="range" id="qs" min="1" max="100" value="30"
               oninput="document.getElementById('qv').textContent=this.value">
        <span class="v" id="qv">30</span>
      </div>
      <button onclick="sendQuality()">APPLY QUALITY</button>
      <div style="margin-top:8px"><label>ROTATION</label>
        <div class="trow">
          <button id="r0"   class="on" onclick="setRot(0)">0&deg;</button>
          <button id="r90"  onclick="setRot(90)">90&deg;</button>
          <button id="r180" onclick="setRot(180)">180&deg;</button>
          <button id="r270" onclick="setRot(270)">270&deg;</button>
        </div>
      </div>
      <div class="trow" style="margin-top:8px">
        <button id="mh" onclick="toggleMirror('h')">&#x21C6; MIRROR H</button>
        <button id="mv" onclick="toggleMirror('v')">&#x21C5; MIRROR V</button>
      </div>
    </div>
    <div class="card">
      <h2>AUDIO</h2>
      <div class="row">
        <label>SAMPLE RATE</label>
        <select id="sr">
          <option value="8000">8 kHz</option>
          <option value="16000" selected>16 kHz</option>
          <option value="22050">22 kHz</option>
          <option value="44100">44 kHz</option>
        </select>
      </div>
      <button onclick="syncRate()">SYNC RATE</button>
      <button id="btnA" onclick="toggleAudio()" style="margin-top:6px">&#128266; ENABLE AUDIO</button>
      <button id="btnM" onclick="toggleMic()" style="margin-top:6px">&#127897; ENABLE MIC</button>
    </div>
    <div class="card">
      <h2>COMMANDS</h2>
      <button class="danger" onclick="sendVibrate()">&#9889; VIBRATE</button>
      <button id="btnRec" onclick="toggleWebRec()" style="margin-top:6px; border-color:var(--y); color:var(--y)">🔴 START WEB REC</button>
    </div>
    <div class="card" style="flex:1;display:flex;flex-direction:column;min-height:100px">
      <h2>LOG</h2>
      <div id="log"></div>
    </div>
  </aside>
</main>
<script type="text/javascript">
'use strict';
const WS = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.hostname + ':8765';
const img = document.getElementById('vid');
const ovl = document.getElementById('ovl');
const st  = document.getElementById('st');
const logEl = document.getElementById('log');
let ws;
let rot=0, mh=false, mv=false;
let fpsCnt=0, fpsT=performance.now(), fps=0;
let actx=null, audioOn=false, nextT=0;
let mediaRecorder;
let recordedChunks = [];
let recCanvas = document.createElement('canvas');
let recCtx = recCanvas.getContext('2d');
let audioDest;
let isRecording = false;
function toggleAudio(){
  if(!actx){
    actx = new (window.AudioContext||window.webkitAudioContext)(
      {sampleRate:+document.getElementById('sr').value, latencyHint:'playback'});
    nextT = actx.currentTime + 0.1;
  }
  audioOn = !audioOn;
  const b = document.getElementById('btnA');
  b.textContent = audioOn ? '(OFF) DISABLE AUDIO \U0001f507' : '(ON) ENABLE AUDIO \U0001f509';
  b.classList.toggle('on', audioOn);
  log('Audio '+(audioOn?'ON':'OFF'));
}
function playPCM(ab){
  if(!audioOn||!actx) return;
  const n = ab.byteLength>>1;
  if(!n) return;
  const buf = actx.createBuffer(1,n,actx.sampleRate);
  const ch  = buf.getChannelData(0);
  const dv  = new DataView(ab);
  for(let i=0;i<n;i++) ch[i]=dv.getInt16(i<<1,true)*3.0517578125e-5; // /32768
  const s=actx.createBufferSource(); s.buffer=buf; s.connect(actx.destination);
  if(isRecording && audioDest) s.connect(audioDest);
  const now=actx.currentTime;
  if(nextT<now+0.02) nextT=now+0.08;
  s.start(nextT); nextT+=buf.duration;
}
function toggleWebRec() {
    if (!isRecording) {
        startWebRec();
    } else {
        stopWebRec();
    }
}
function startWebRec() {
    recordedChunks = [];
    const isPortrait = (rot === 90 || rot === 270);
    const nw = img.naturalWidth || 1920;
    const nh = img.naturalHeight || 1080;
    recCanvas.width = isPortrait ? nh : nw;
    recCanvas.height = isPortrait ? nw : nh;
    const videoStream = recCanvas.captureStream(30);
    let tracks = [videoStream.getVideoTracks()[0]];
    if (actx) {
        audioDest = actx.createMediaStreamDestination();
        tracks.push(audioDest.stream.getAudioTracks()[0]);
    }
    const combinedStream = new MediaStream(tracks);
    mediaRecorder = new MediaRecorder(combinedStream, { 
        mimeType: 'video/webm;codecs=vp8,opus', 
        bitsPerSecond: 5000000 
    });
    mediaRecorder.ondataavailable = (e) => { if (e.data.size > 0) recordedChunks.push(e.data); };
    mediaRecorder.onstop = exportRec;
    mediaRecorder.start();
    isRecording = true;
    document.getElementById('btnRec').textContent = "⏹ STOP & SAVE REC";
    document.getElementById('btnRec').classList.add('on');
    log("Web Recording started (AV)...");
}
function stopWebRec() {
    mediaRecorder.stop();
    isRecording = false;
    document.getElementById('btnRec').textContent = "🔴 START WEB REC";
    document.getElementById('btnRec').classList.remove('on');
}
function exportRec() {
    const blob = new Blob(recordedChunks, { type: 'video/webm' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `Synchro_WebRec_${new Date().getTime()}.webm`;
    a.click();
    URL.revokeObjectURL(url);
    log("Recording saved to Downloads");
}
function connect(){
  ws = new WebSocket(WS);
  ws.binaryType='arraybuffer';
  ws.onopen  = ()=>{ log('WSS connected'); setS('warn','WAITING FOR APP...'); };
  ws.onclose = ()=>{ log('WSS closed — retrying...'); setS('err','DISCONNECTED'); setTimeout(connect,2000); };
  ws.onerror = ()=>{ log('WSS error'); };
  ws.onmessage = onMsg;
}
function onMsg(e){
  if(typeof e.data==='string'){
    try{ const d=JSON.parse(e.data); if(d.log) log(d.log); }catch(_){}
    return;
  }
  const ab = e.data;
  if(ab.byteLength<2) return;
  const type = new DataView(ab,0,1).getUint8(0);
  const payload = ab.slice(1);
  if(type===1){
    if (img.loading === true) return; 
    img.loading = true;
    const prev = img.src;
    const url = URL.createObjectURL(new Blob([payload],{type:'image/jpeg'}));
    img.onload = function(){
      img.loading = false;
      if(prev.startsWith('blob:')) URL.revokeObjectURL(prev);
      ovl.textContent='FPS: '+fps+' | '+img.naturalWidth+'\xd7'+img.naturalHeight;
      if(isRecording) {
          const cw = recCanvas.width, ch = recCanvas.height;
          const nw = img.naturalWidth, nh = img.naturalHeight;
          recCtx.save();
          recCtx.clearRect(0, 0, cw, ch);
          recCtx.translate(cw / 2, ch / 2);
          recCtx.rotate(rot * Math.PI / 180);
          recCtx.scale(mh ? -1 : 1, mv ? -1 : 1);
          recCtx.drawImage(img, -nw / 2, -nh / 2);
          recCtx.restore();
      }
    };
    img.src = url;
    fpsCnt++;
    const now=performance.now();
    if(now-fpsT>=1000){ fps=Math.round(fpsCnt*1000/(now-fpsT)); fpsCnt=0; fpsT=now; }
    setS('ok','APP CONNECTED - '+fps+' FPS');
  } else if(type===2){
    playPCM(payload);
  } else if(type===255){
    log(new TextDecoder().decode(payload));
  }
}
function send(o){ if(ws&&ws.readyState===1) ws.send(JSON.stringify(o)); }
function sendQuality(){ const q=+document.getElementById('qs').value; send({cmd:'SET_QUALITY',value:q}); log('SET_QUALITY '+q); }
function sendVibrate(){ send({cmd:'VIBRATE'}); log('VIBRATE sent'); }
function syncRate(){
  const r=+document.getElementById('sr').value;
  send({cmd:'SET_RATE',value:r});
  if(actx){ actx.close(); actx=null; audioOn=false;
    document.getElementById('btnA').textContent='(ON) ENABLE AUDIO';
    document.getElementById('btnA').classList.remove('on'); }
  log('SET_RATE '+r);
}
function setRot(d){
  rot=d;
  ['r0','r90','r180','r270'].forEach(id=>document.getElementById(id).classList.remove('on'));
  document.getElementById('r'+d).classList.add('on');
  applyXform();
}
function toggleMirror(a){
  if(a==='h'){ mh=!mh; document.getElementById('mh').classList.toggle('on',mh); }
  else        { mv=!mv; document.getElementById('mv').classList.toggle('on',mv); }
  applyXform();
}
function applyXform(){
  img.style.transform='rotate('+rot+'deg) scale('+(mh?-1:1)+','+(mv?-1:1)+')';
}
function setS(c,t){ st.className=c; st.textContent=t; }
function log(m){
  const p=document.createElement('p');
  p.textContent='['+new Date().toLocaleTimeString()+'] '+m;
  logEl.appendChild(p);
  while(logEl.childElementCount>200) logEl.removeChild(logEl.firstChild);
  logEl.scrollTop=logEl.scrollHeight;
}
let micOn=false, micStream=null, micProc=null, micSrc=null, micCtx=null;
function toggleMic(){
  if(!micOn){ startMic(); } else { stopMic(); }
}
function startMic(){
  navigator.mediaDevices.getUserMedia({audio:{sampleRate:16000,channelCount:1,echoCancellation:true,noiseSuppression:true},video:false})
  .then(function(stream){
    micStream = stream;
    micCtx = new (window.AudioContext||window.webkitAudioContext)({sampleRate:16000});
    micSrc  = micCtx.createMediaStreamSource(stream);
    micProc = micCtx.createScriptProcessor(4096,1,1);
    micProc.onaudioprocess = function(ev){
      if(!micOn||!ws||ws.readyState!==1) return;
      const input = ev.inputBuffer.getChannelData(0);
      const pcm = new Int16Array(input.length);
      for(var i=0;i<input.length;i++){
        var s = Math.max(-1, Math.min(1, input[i]));
        pcm[i] = s < 0 ? s*32768 : s*32767;
      }
      const pkt = new Uint8Array(1 + pcm.byteLength);
      pkt[0] = 2;
      pkt.set(new Uint8Array(pcm.buffer), 1);
      ws.send(pkt.buffer);
    };
    micSrc.connect(micProc);
    micProc.connect(micCtx.destination);
    micOn = true;
    const b=document.getElementById('btnM');
    b.textContent='\U0001f3a4 DISABLE MIC'; b.classList.add('on');
    log('Mic ON');
  })
  .catch(function(err){ log('Mic error: '+err.message); });
}
function stopMic(){
  micOn = false;
  try{ micProc.disconnect(); micSrc.disconnect(); micCtx.close(); }catch(_){}
  try{ micStream.getTracks().forEach(function(t){t.stop();}); }catch(_){}
  micStream=null; micProc=null; micSrc=null; micCtx=null;
  const b=document.getElementById('btnM');
  b.textContent='\U0001f3a4 ENABLE MIC'; b.classList.remove('on');
  log('Mic OFF');
}
setInterval(function(){
  if(ws && ws.readyState===1) ws.send(JSON.stringify({cmd:'PING'}));
}, 15000);
connect();
log('UI ready');
</script>
<script type="text/javascript" id="sec-script" data-hash="">
async function securityCheck() {
    const serverHash = document.getElementById('sec-script').getAttribute('data-hash');
    if (!serverHash || serverHash.length < 64) {
        document.body.innerHTML = "<h1 style='color:red;text-align:center;margin-top:20%'>🚨 SECURITY BREACH: INVALID FINGERPRINT</h1>";
        if(ws) ws.close();
        return;
    }
    log("🔒 Session Fingerprint: " + serverHash.substring(0, 16) + "...");
}
securityCheck();
</script>
</body>
</html>"""
HTML      = HTML.encode("utf-8")
_HTML_LEN = str(len(HTML)).encode()
def check_or_create_password():
    file_path = "pasw.txt"
    if os.path.exists(file_path):
        with open(file_path, "r") as f:
            lines = f.read().splitlines()
            if len(lines) >= 2:
                return lines[0], lines[1]
    print(f"\n{MAGENTA}{BOLD}╔══════════════════════════════════════╗{RESET}")
    print(f"{MAGENTA}{BOLD}║         🔐 SERVER SECURITY SETUP      ║{RESET}")
    print(f"{MAGENTA}{BOLD}╚══════════════════════════════════════╝{RESET}\n")
    print(f"{CYAN}{BOLD}[SETUP]{RESET} Welcome! Configure your admin credentials.\n")
    while True:
        user = input(f"{YELLOW}➜ Enter new Username: {RESET}").strip()
        if not user:
            print(f"{RED}{BOLD}✖ Username cannot be empty!{RESET}\n")
            continue
        break
    while True:
        pwd = getpass.getpass(f"{YELLOW}➜ Enter new Password: {RESET}")
        confirm_pwd = getpass.getpass(f"{YELLOW}➜ Confirm Password: {RESET}")
        if not pwd:
            print(f"{RED}{BOLD}✖ Password cannot be empty!{RESET}\n")
            continue
        if pwd != confirm_pwd:
            print(f"{RED}{BOLD}✖ Passwords do not match. Try again.{RESET}\n")
            continue
        break
    salt = os.urandom(16).hex()
    user_hashed = hashlib.sha256(user.encode()).hexdigest()
    pass_with_salt = salt + pwd
    pass_hashed = hashlib.sha256(pass_with_salt.encode()).hexdigest()
    with open(file_path, "w") as f:
        f.write(f"{user_hashed}\n{salt}:{pass_hashed}")
    print(f"\n{GREEN}{BOLD}✔ Credentials successfully saved!{RESET}")
    print(f"{CYAN}[INFO]{RESET} Stored securely in {file_path} (Salted SHA-256).")
    print(f"{CYAN}[INFO]{RESET} You must use these credentials to access the Web UI.\n")
    return user_hashed, f"{salt}:{pass_hashed}"
class _Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        global _EXPECTED_USER_HASH, _EXPECTED_PASS_HASH
        auth_header = self.headers.get('Authorization')
        client_address = self.client_address[0]
        now = time.time()
        if client_address in _BANNED_IPS:
            attempts, last_time = _BANNED_IPS[client_address]
            if attempts >= 3:
                if now - last_time < 60:
                    self.send_response(403)
                    self.end_headers()
                    self.wfile.write(
                b"<!DOCTYPE html>"
                b"<html lang='en'>"
                b"<head>"
                b"<meta charset='UTF-8'>"
                b"<link rel='shortcut icon' href='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAkAAAAJCAYAAADgkQYQAAAALklEQVR42mOAgf+MvP/RMQMyQBLAECeoAEaDCVwKcCnCysfUBQeYiih3PFHhBACeLztdzfuCpwAAAABJRU5ErkJggg=='>"
                b"<title>IP BANNED!</title>"
                b"</head>"
                b"<body bgcolor='#000' style='margin:0;padding:0;height:100vh;display:flex;align-items:center;justify-content:center;background:radial-gradient(circle at center,#1a0000,#000000 70%);font-family:Arial,sans-serif;'>"
                b"<div style='background:#0a0a0a;padding:50px;border-radius:8px;text-align:center;color:#bbbbbb;box-shadow:0 0 40px rgba(255,0,0,0.6);border:2px solid #550000;'>"
                b"<h1 style='margin:0 0 20px 0;font-size:38px;color:#ff0000;text-transform:uppercase;letter-spacing:3px;text-shadow:0 0 15px rgba(255,0,0,0.9);'><h1>IP TEMPORARILY BANNED</h1><p>Too many attempts. <h1>Try again in a minute.</h1>"
                b"<p style='margin:0;font-size:18px;color:#888888;'>Invalid credentials or access denied.<br>Reload and try again.</p>"
                b"</div>"
                b"</body>"
                b"</html>"
                    )
                    return
                else:
                    _BANNED_IPS.pop(client_address, None)
                    print(f"\033[91m{EVENT_TIME} >>> [AUTH] Failure {attempts}/3 for {client_address}\033[0m")
        authorized = False
        print(f"\n{EVENT_TIME} >>> [WEB] Incoming connection attempt from {client_address}")
        if auth_header and auth_header.startswith('Basic '):
            try:
                encoded_credentials = auth_header.split(' ')[1]
                decoded = base64.b64decode(encoded_credentials).decode('utf-8')
                input_user, input_pass = decoded.split(':', 1)
                input_user_hash = hashlib.sha256(input_user.encode()).hexdigest()
                saved_salt, saved_hash = _EXPECTED_PASS_HASH.split(":")
                test_pass_hash = hashlib.sha256((saved_salt + input_pass).encode()).hexdigest()
                if (input_user_hash == _EXPECTED_USER_HASH and test_pass_hash == saved_hash):
                    authorized = True
                    _BANNED_IPS.pop(client_address, None)
                    print(f"\033[92m{EVENT_TIME} >>> [AUTH] Access GRANTED for user: {input_user}\033[0m")
                else:
                    attempts = _BANNED_IPS.get(client_address, [0, 0])[0] + 1
                    _BANNED_IPS[client_address] = [attempts, time.time()]
                    print(f"\033[91m{EVENT_TIME} >>> [AUTH] Access DENIED: Wrong credentials\033[0m")
            except Exception as e:
                print(f"{EVENT_TIME} >>> [AUTH] Error decoding credentials: {e}")
        else:
            print(f"{EVENT_TIME} >>> [WEB] Request from {client_address} without Authorization header.")
        if authorized:
            try:
                html_dinamico = HTML.decode("utf-8").replace(
                    'data-hash=""', 
                    f'data-hash="{_CURRENT_FINGERPRINT}"'
                )
                payload_final = html_dinamico.encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("X-SHA256-Fingerprint", _CURRENT_FINGERPRINT)
                self.send_header("X-Frame-Options", "DENY")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
                self.send_header("Content-Security-Policy", "upgrade-insecure-requests")
                self.send_header("Content-Length", str(len(payload_final)))
                self.send_header("X-Server-Fingerprint", _CURRENT_FINGERPRINT)
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(payload_final)
            except BrokenPipeError:
                pass
        else:
            time.sleep(1)
            body = (
                b"<!DOCTYPE html>"
                b"<html lang='en'>"
                b"<head>"
                b"<meta charset='UTF-8'>"
                b"<link rel='shortcut icon' href='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAkAAAAJCAYAAADgkQYQAAAALklEQVR42mOAgf+MvP/RMQMyQBLAECeoAEaDCVwKcCnCysfUBQeYiih3PFHhBACeLztdzfuCpwAAAABJRU5ErkJggg=='>"
                b"<title>ACCESS DENIED!</title>"
                b"</head>"
                b"<body bgcolor='#000' style='margin:0;padding:0;height:100vh;display:flex;align-items:center;justify-content:center;background:radial-gradient(circle at center,#1a0000,#000000 70%);font-family:Arial,sans-serif;'>"
                b"<div style='background:#0a0a0a;padding:50px;border-radius:8px;text-align:center;color:#bbbbbb;box-shadow:0 0 40px rgba(255,0,0,0.6);border:2px solid #550000;'>"
                b"<h1 style='margin:0 0 20px 0;font-size:38px;color:#ff0000;text-transform:uppercase;letter-spacing:3px;text-shadow:0 0 15px rgba(255,0,0,0.9);'>401 - ACCESS DENIED</h1>"
                b"<p style='margin:0;font-size:18px;color:#888888;'>Invalid credentials or access denied.<br>Reload and try again.</p>"
                b"</div>"
                b"</body>"
                b"</html>"
            )
            try:
                self.send_response(401)
                self.send_header('WWW-Authenticate', 'Basic realm="SynchroGaea Secure Login"')
                self.send_header('Content-Type', 'text/html; charset=utf-8')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except BrokenPipeError:
                pass
    def log_message(self, *_):
        pass
_EXPECTED_USER_HASH, _EXPECTED_PASS_HASH = check_or_create_password()
async def _broadcast_raw(data: bytes):
    if not _ws_clients:
        return
    clients = tuple(_ws_clients)
    results = await asyncio.gather(
        *[c.send(data) for c in clients],
        return_exceptions=True
    )
    for c, r in zip(clients, results):
        if isinstance(r, Exception):
            _ws_clients.discard(c)

async def _log(msg: str):
    print(msg, flush=True)
    await _broadcast_raw(_LOG_HDR + msg.encode("utf-8", "replace"))
async def _ws_handler(websocket):
    _ws_clients.add(websocket)
    await _log(f"{EVENT_TIME} >>> [WSS] Browser connected   {websocket.remote_address}")
    try:
        async for raw in websocket:
            if isinstance(raw, str):
                try:
                    d = json.loads(raw)
                    if d.get("cmd") == "PING":
                        pass
                    else:
                        await _handle_cmd(d)
                except Exception as ex:
                    await _log(f"{EVENT_TIME} >>> [CMD] Error: {ex}")
            elif isinstance(raw, bytes) and len(raw) > 1:
                ptype = raw[0]
                if ptype == 2:
                    w = _android_writer
                    if w:
                        payload = raw[1:]
                        await _to_app(w, 2, payload)
    except Exception:
        pass
    finally:
        _ws_clients.discard(websocket)
        await _log(f"{EVENT_TIME} >>> [WSS] Browser disconnected")
async def _handle_cmd(d: dict):
    w = _android_writer
    if w is None:
        await _log(f"{EVENT_TIME} >>> [CMD] No app connected")
        return
    cmd = d.get("cmd", "")
    if   cmd == "SET_QUALITY": await _to_app(w, 3, f"SET_QUALITY:{d['value']}".encode())
    elif cmd == "VIBRATE":     await _to_app(w, 3, b"VIBRATE")
    elif cmd == "SET_RATE":    await _to_app(w, 3, f"SET_RATE:{d['value']}".encode())
async def _to_app(writer: asyncio.StreamWriter, ptype: int, payload: bytes):
    try:
        writer.write(bytes([ptype]) + struct.pack(">I", len(payload)) + payload)
        if writer.transport.get_write_buffer_size() > 65536:
            await writer.drain()
    except Exception as ex:
        await _log(f"{EVENT_TIME} >>> [APP] Send error: {ex}")
def get_fingerprint(crt_path):
    with open(crt_path, "rb") as f:
        cert_pem = f.read().decode()
        b64_data = "".join(cert_pem.splitlines()[1:-1])
        der_data = base64.b64decode(b64_data)
        return hashlib.sha256(der_data).hexdigest().lower()
def _build_ssl_ctx():
    try:
        from cryptography import x509
        from cryptography.x509.oid import NameOID
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec 
        from cryptography.hazmat.primitives.asymmetric import rsa
        import datetime
        key = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, u"synchrogaea")])
        now  = datetime.datetime.now(datetime.timezone.utc)
        cert = (x509.CertificateBuilder()
                .subject_name(name).issuer_name(name)
                .public_key(key.public_key())
                .serial_number(x509.random_serial_number())
                .not_valid_before(now)
                .not_valid_after(now + datetime.timedelta(days=3650))
                .sign(key, hashes.SHA256()))
        tmp      = tempfile.mkdtemp()
        crt_path = os.path.join(tmp, "srv.crt")
        key_path = os.path.join(tmp, "srv.key")
        with open(crt_path, "wb") as f:
            f.write(cert.public_bytes(serialization.Encoding.PEM))
        with open(key_path, "wb") as f:
            f.write(key.private_bytes(serialization.Encoding.PEM,
                    serialization.PrivateFormat.TraditionalOpenSSL,
                    serialization.NoEncryption()))
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_3
        ctx.load_cert_chain(certfile=crt_path, keyfile=key_path)
        ctx.options |= ssl.OP_SINGLE_ECDH_USE
        ctx.options |= ssl.OP_NO_COMPRESSION
        return ctx, crt_path
    except Exception:
        return None, None #AnticrashSSL
_auth_failures = {}
async def _packet_loop(reader: asyncio.StreamReader,
                       writer: asyncio.StreamWriter, addr, mode: str):
    addr_ip = addr[0]
    now = time.time()
    if addr_ip in _auth_failures:
        fallimenti, ultimo_timestamp = _auth_failures[addr_ip]
        if fallimenti >= 3:
            if now - ultimo_timestamp < 60:
                print(f"{EVENT_TIME} >>> [SECURITY] IP {addr_ip} BANNED (Brute-force protection)")
                writer.close()
                return
            else:
                del _auth_failures[addr_ip]
    global _android_writer
    try:
        auth_hdr = await reader.readexactly(5)
        auth_type = auth_hdr[0]
        auth_len = struct.unpack_from(">I", auth_hdr, 1)[0]
        auth_payload = await reader.readexactly(auth_len)
        try:
            creds = auth_payload.decode('utf-8').split(':', 1)
            if len(creds) != 2: raise ValueError()
            u, p = creds
            u_hash = hashlib.sha256(u.encode()).hexdigest()
            s_salt, s_hash = _EXPECTED_PASS_HASH.split(":")
            p_hash = hashlib.sha256((s_salt + p).encode()).hexdigest()
            if u_hash != _EXPECTED_USER_HASH or p_hash != s_hash:
                fallimenti, _ = _auth_failures.get(addr_ip, (0, 0))
                _auth_failures[addr_ip] = [fallimenti + 1, time.time()]
                print(f"\033[91m{EVENT_TIME} >>> [APP] AUTH FAILED ({fallimenti + 1}/3): Invalid credentials from {addr}\033[0m")
                writer.write(bytes([255]))
                await writer.drain()
                return
        except Exception:
            print(f"\033[91m{EVENT_TIME} >>> [APP] AUTH ERROR: Malformed packet from {addr}\033[0m")
            return
        if addr_ip in _auth_failures: del _auth_failures[addr_ip]
        writer.write(b'\x00')
        await writer.drain()
        _android_writer = writer
        await _log(f"{EVENT_TIME} >>> [APP] Connected & Authorized ({mode}) {addr}")
        while True:
            hdr    = await reader.readexactly(5)
            ptype  = hdr[0]
            length = struct.unpack_from(">I", hdr, 1)[0]
            if length > MAX_PAYLOAD:
                await _log(f"{EVENT_TIME} >>> [APP] Oversized packet {length}B — dropping")
                break
            payload = await reader.readexactly(length) if length else b""
            if   ptype == 1 and payload: await _broadcast_raw(_FRAME_HDR + payload)
            elif ptype == 2 and payload: await _broadcast_raw(_AUDIO_HDR + payload)
            elif ptype == 3 and payload: await _log(f"{EVENT_TIME} >>> [APP] CMD: " + payload.decode("utf-8", "replace"))
            elif ptype == 4 and payload:
                global _video_counter
                _video_counter += 1
                import random
                mk_id = random.randint(1000000, 9999999)
                client_id = f"{addr[0]}_{addr[1]}"
                timestamp = time.strftime("%Y%m%d_%H%M%S")
                filename = f"GaeaSavedREC/REC_MK{mk_id}_{client_id}_{timestamp}_{_video_counter}.mp4"
                def _write_file(data, name):
                    with open(name, "wb") as f:
                        f.write(data)
                asyncio.create_task(asyncio.to_thread(_write_file, payload, filename))
                await _log(f"{EVENT_TIME} >>> [APP] BLACK_BOX: Video saved -> {filename} ({len(payload)} bytes)")
            elif ptype == 0:
                writer.write(b"\x00\x00\x00\x00\x05ALIVE")
                await writer.drain()
    except asyncio.IncompleteReadError:
        pass
    except Exception as ex:
        await _log(f"{EVENT_TIME} >>> [APP] Error: {ex}")
    finally:
        if _android_writer is writer:
            _android_writer = None
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass
        await _log(f"{EVENT_TIME} >>> [APP] Disconnected {addr}")
async def _handler_tls(reader, writer):
    await _packet_loop(reader, writer, writer.get_extra_info("peername"), "TLS")
async def _handler_plain(reader, writer):
    await _packet_loop(reader, writer, writer.get_extra_info("peername"), "plain")
async def _main():
    try:
        from websockets.server import serve as ws_serve
    except ImportError:
        sys.exit("ERROR: run  pip install websockets  first")
    ssl_ctx, crt_path = _build_ssl_ctx()
    global _CURRENT_FINGERPRINT
    if crt_path:
        _CURRENT_FINGERPRINT = get_fingerprint(crt_path)
    else:
        _CURRENT_FINGERPRINT = "NO_TLS"
    print(f"  ")
    print(f"{MAGENTA}[SECURITY] Valid Fingerprint: {_CURRENT_FINGERPRINT}{RESET}")
    print(f"  ")
    os.makedirs("GaeaSavedREC", exist_ok=True)
    global _video_counter
    _video_counter = 0
    tls_port   = TCP_PORT
    plain_port = TCP_PORT + 1
    ws_srv = await ws_serve(_ws_handler, "0.0.0.0", WS_PORT,
                            ssl=ssl_ctx,
                            max_size=MAX_PAYLOAD, compression=None,
                            ping_interval=20, ping_timeout=60)
    httpd = HTTPServer(("0.0.0.0", HTTP_PORT), _Handler)
    web_proto = "http"
    if ssl_ctx:
        httpd.socket = ssl_ctx.wrap_socket(httpd.socket, server_side=True)
        web_proto = "https"
    threading.Thread(target=httpd.serve_forever, daemon=True, name="http").start()
    servers = []
    if ssl_ctx:
        tls_srv = await asyncio.start_server(
            _handler_tls, "0.0.0.0", tls_port,
            ssl=ssl_ctx, limit=TCP_READER_LIMIT, backlog=8
        )
        servers.append(tls_srv)
        tls_info = f"TLS  :9999  plain :10000"
    else:
        tls_info = "TLS disabled (pip install cryptography)"
    plain_srv = await asyncio.start_server(
        _handler_plain, "0.0.0.0", plain_port if ssl_ctx else tls_port,
        limit=TCP_READER_LIMIT, backlog=8
    )
    servers.append(plain_srv)
    import socket
    for srv in servers:
        for sock in srv.sockets:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 65536)
    print("   ")
    print(f"{GREEN}{BOLD}╔══════════════════════════════════════════════════╗{RESET}")
    print(f"{GREEN}{BOLD}║           SynchroGaea Server  v4.4               ║{RESET}")
    print(f"{GREEN}{BOLD}╠══════════════════════════════════════════════════╣{RESET}")
    if ssl_ctx:
        print(f"{MAGENTA}║  Android TLS   →  0.0.0.0:{tls_port}  (try first)      ║{RESET}")
        print(f"{CYAN}║  Android plain →  0.0.0.0:{plain_port} (fallback)       ║{RESET}")
        print(f"{YELLOW}║  App field: YOUR_IP:{tls_port};YOUR_IP:{plain_port}           ║{RESET}")
    else:
        print(f"{CYAN}║  Android plain →  0.0.0.0:{tls_port}                   ║{RESET}")
    print(f"{CYAN}║  Web UI        →  https://0.0.0.0:{HTTP_PORT}           ║{RESET}")
    print(f"{CYAN}║  WebSocket     →  0.0.0.0:{WS_PORT}                   ║{RESET}")
    print(f"{GREEN}{BOLD}╠══════════════════════════════════════════════════╣{RESET}")
    print(f"{YELLOW}║  Open browser: https://127.0.0.1:{HTTP_PORT}            ║{RESET}")
    print(f"{GREEN}{BOLD}╚══════════════════════════════════════════════════╝{RESET}")
    print("   ")
    all_srv = [ws_srv] + servers
    for s in servers:
        await s.__aenter__()
    async with ws_srv:
        await asyncio.gather(
            ws_srv.serve_forever(),
            *[s.serve_forever() for s in servers]
        )
if __name__ == "__main__":
    try:
        asyncio.run(_main())
    except KeyboardInterrupt:
        print(f"\n{EVENT_TIME} >>> Server stopped.")