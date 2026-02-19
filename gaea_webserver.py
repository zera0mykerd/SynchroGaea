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
from http.server import HTTPServer, BaseHTTPRequestHandler

# ── CONFIG ────────────────────────────────────────────────────────────────────
TCP_PORT  = 9999
HTTP_PORT = 8080
WS_PORT   = 8765

# Max accepted payload per packet (16 MB). Protects against malformed/OOM.
MAX_PAYLOAD = 16_000_000
# StreamReader internal buffer cap — prevents unbounded memory growth.
TCP_READER_LIMIT = 8 * 1024 * 1024   # 8 MB

# ── GLOBAL STATE (single asyncio thread — no locks needed) ───────────────────
_ws_clients: set   = set()
_android_writer    = None   # asyncio.StreamWriter | None

# Pre-built envelope bytes so we never re-allocate on the hot path.
# Format: [type:1][len:4][payload:N]
_FRAME_HDR  = bytes([1])   # JPEG  → browser prefix
_AUDIO_HDR  = bytes([2])   # Audio → browser prefix
_LOG_HDR    = bytes([255]) # Log   → browser prefix

# ── HTML PAGE ─────────────────────────────────────────────────────────────────
# Defined as str, encoded to bytes once at module load — avoids per-request encode.
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
    <!-- VIDEO -->
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
    <!-- AUDIO -->
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
    <!-- COMMANDS -->
    <div class="card">
      <h2>COMMANDS</h2>
      <button class="danger" onclick="sendVibrate()">&#9889; VIBRATE</button>
    </div>
    <!-- LOG -->
    <div class="card" style="flex:1;display:flex;flex-direction:column;min-height:100px">
      <h2>LOG</h2>
      <div id="log"></div>
    </div>
  </aside>
</main>
<script type="text/javascript">
'use strict';
const WS = 'ws://'+location.hostname+':8765';
const img = document.getElementById('vid');
const ovl = document.getElementById('ovl');
const st  = document.getElementById('st');
const logEl = document.getElementById('log');

let ws;
let rot=0, mh=false, mv=false;
let fpsCnt=0, fpsT=performance.now(), fps=0;

/* ── Audio (scheduled PCM playback, no glitches) ── */
let actx=null, audioOn=false, nextT=0;

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
  const now=actx.currentTime;
  if(nextT<now+0.02) nextT=now+0.08;
  s.start(nextT); nextT+=buf.duration;
}

/* ── WebSocket ── */
function connect(){
  ws = new WebSocket(WS);
  ws.binaryType='arraybuffer';
  ws.onopen  = ()=>{ log('WS connected'); setS('warn','WAITING FOR APP...'); };
  ws.onclose = ()=>{ log('WS closed — retrying...'); setS('err','DISCONNECTED'); setTimeout(connect,2000); };
  ws.onerror = ()=>{ log('WS error'); };
  ws.onmessage = onMsg;
}

function onMsg(e){
  if(typeof e.data==='string'){
    try{ const d=JSON.parse(e.data); if(d.log) log(d.log); }catch(_){}
    return;
  }
  /* Binary: first byte = type, rest = payload (zero-copy via DataView) */
  const ab = e.data;
  if(ab.byteLength<2) return;
  const type = new DataView(ab,0,1).getUint8(0);
  const payload = ab.slice(1);   /* ArrayBuffer.slice — O(1) view */

  if(type===1){
    /* ── JPEG frame: decode directly into <img>, skip Blob when possible ── */
    const prev = img.src;
    /* Use createObjectURL — single allocation, browser handles decode async */
    const url = URL.createObjectURL(new Blob([payload],{type:'image/jpeg'}));
    img.onload = function(){
      if(prev.startsWith('blob:')) URL.revokeObjectURL(prev);
      /* Update overlay only after real decode — avoids layout thrash */
      ovl.textContent='FPS: '+fps+' | '+img.naturalWidth+'\xd7'+img.naturalHeight;
    };
    img.src = url;
    /* FPS counter */
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

/* ── Commands ── */
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

/* ── Transform ── */
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

/* ── Status / Log ── */
function setS(c,t){ st.className=c; st.textContent=t; }
function log(m){
  const p=document.createElement('p');
  p.textContent='['+new Date().toLocaleTimeString()+'] '+m;
  logEl.appendChild(p);
  /* Cap at 200 entries to prevent unbounded DOM growth */
  while(logEl.childElementCount>200) logEl.removeChild(logEl.firstChild);
  logEl.scrollTop=logEl.scrollHeight;
}

/* ── Mic capture → send PCM to app via server ── */
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
    /* ScriptProcessor: simple, no worker needed for this use-case */
    micProc = micCtx.createScriptProcessor(4096,1,1);
    micProc.onaudioprocess = function(ev){
      if(!micOn||!ws||ws.readyState!==1) return;
      const input = ev.inputBuffer.getChannelData(0);
      /* Convert float32 → int16 PCM */
      const pcm = new Int16Array(input.length);
      for(var i=0;i<input.length;i++){
        var s = Math.max(-1, Math.min(1, input[i]));
        pcm[i] = s < 0 ? s*32768 : s*32767;
      }
      /* Send as binary: type byte 2 + raw PCM */
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

/* ── WS keep-alive: ping every 15 s to prevent idle disconnects ── */
setInterval(function(){
  if(ws && ws.readyState===1) ws.send(JSON.stringify({cmd:'PING'}));
}, 15000);

connect();
log('UI ready');
</script>
</body>
</html>"""

HTML      = HTML.encode("utf-8")   # encode once, reuse bytes on every request
_HTML_LEN = str(len(HTML)).encode()


# ── HTTP (static, thread-safe: only reads immutable HTML bytes) ───────────────
class _Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type",   "text/html; charset=utf-8")
        self.send_header("Content-Length", _HTML_LEN)
        self.send_header("Cache-Control",  "no-store")
        self.end_headers()
        self.wfile.write(HTML)
    def log_message(self, *_): pass


# ── Broadcast helpers ─────────────────────────────────────────────────────────
# Uses asyncio.gather so all clients are written CONCURRENTLY, not serially.
# Dead clients are pruned after each round — no extra set allocation per call.

async def _broadcast_raw(data: bytes):
    if not _ws_clients:
        return
    clients = tuple(_ws_clients)          # snapshot — safe during iteration
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


# ── WebSocket handler (browser side) ─────────────────────────────────────────
async def _ws_handler(websocket):
    _ws_clients.add(websocket)
    await _log(f"[WS] Browser connected   {websocket.remote_address}")
    try:
        async for raw in websocket:
            if isinstance(raw, str):
                try:
                    d = json.loads(raw)
                    if d.get("cmd") == "PING":
                        pass  # keep-alive, nothing to do
                    else:
                        await _handle_cmd(d)
                except Exception as ex:
                    await _log(f"[CMD] Error: {ex}")
            elif isinstance(raw, bytes) and len(raw) > 1:
                # Binary from browser: type byte + payload
                # Type 2 = mic PCM → forward to Android app as-is
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
        await _log("[WS] Browser disconnected")


async def _handle_cmd(d: dict):
    w = _android_writer
    if w is None:
        await _log("[CMD] No app connected")
        return
    cmd = d.get("cmd", "")
    if   cmd == "SET_QUALITY": await _to_app(w, 3, f"SET_QUALITY:{d['value']}".encode())
    elif cmd == "VIBRATE":     await _to_app(w, 3, b"VIBRATE")
    elif cmd == "SET_RATE":    await _to_app(w, 3, f"SET_RATE:{d['value']}".encode())


async def _to_app(writer: asyncio.StreamWriter, ptype: int, payload: bytes):
    try:
        writer.write(bytes([ptype]) + struct.pack(">I", len(payload)) + payload)
        # drain() only if the write buffer is getting large, avoids stalling the loop
        if writer.transport.get_write_buffer_size() > 65536:
            await writer.drain()
    except Exception as ex:
        await _log(f"[APP] Send error: {ex}")


# ── Self-signed cert ─────────────────────────────────────────────────────────
def _build_ssl_ctx():
    """Self-signed cert — app uses trust-all so any cert is accepted."""
    try:
        from cryptography import x509
        from cryptography.x509.oid import NameOID
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec 
        from cryptography.hazmat.primitives.asymmetric import rsa
        import datetime
        #key  = rsa.generate_private_key(public_exponent=65537, key_size=2048)
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
        ctx.check_hostname = False
        ctx.verify_mode    = ssl.CERT_NONE
        ctx.load_cert_chain(certfile=crt_path, keyfile=key_path)
        ctx.set_ciphers('ECDHE-ECDSA-AES128-GCM-SHA256') #for new keys
        return ctx
    except Exception:
        return None


# ── Packet loop (shared by TLS and plain handlers) ───────────────────────────
async def _packet_loop(reader: asyncio.StreamReader,
                       writer: asyncio.StreamWriter, addr, mode: str):
    global _android_writer
    _android_writer = writer
    await _log(f"[APP] Connected ({mode})   {addr}")
    try:
        while True:
            hdr    = await reader.readexactly(5)
            ptype  = hdr[0]
            length = struct.unpack_from(">I", hdr, 1)[0]
            if length > MAX_PAYLOAD:
                await _log(f"[APP] Oversized packet {length}B — dropping")
                break
            payload = await reader.readexactly(length) if length else b""
            if   ptype == 1 and payload: await _broadcast_raw(_FRAME_HDR + payload)
            elif ptype == 2 and payload: await _broadcast_raw(_AUDIO_HDR + payload)
            elif ptype == 3 and payload: await _log("[APP] CMD: " + payload.decode("utf-8", "replace"))
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
                await _log(f"[APP] BLACK_BOX: Video saved -> {filename} ({len(payload)} bytes)")
            elif ptype == 0:
                # Echo ALIVE back — resets the app's 20s watchdog timer
                # Format: [0x00][0x00 0x00 0x00 0x05][A L I V E]
                writer.write(b"\x00\x00\x00\x00\x05ALIVE")
                await writer.drain()
    except asyncio.IncompleteReadError:
        pass
    except Exception as ex:
        await _log(f"[APP] Error: {ex}")
    finally:
        if _android_writer is writer:
            _android_writer = None
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass
        await _log(f"[APP] Disconnected {addr}")


async def _handler_tls(reader, writer):
    await _packet_loop(reader, writer, writer.get_extra_info("peername"), "TLS")

async def _handler_plain(reader, writer):
    await _packet_loop(reader, writer, writer.get_extra_info("peername"), "plain")


# ── Entry point ───────────────────────────────────────────────────────────────
async def _main():
    try:
        from websockets.server import serve as ws_serve
    except ImportError:
        sys.exit("ERROR: run  pip install websockets  first")

    ssl_ctx    = _build_ssl_ctx()
    os.makedirs("GaeaSavedREC", exist_ok=True)
    global _video_counter
    _video_counter = 0
    tls_port   = TCP_PORT        # 9999  — TLS
    plain_port = TCP_PORT + 1    # 10000 — plain fallback

    # ── WebSocket ─────────────────────────────────────────────────────────────
    ws_srv = await ws_serve(_ws_handler, "0.0.0.0", WS_PORT,
                            max_size=MAX_PAYLOAD, compression=None,
                            ping_interval=20, ping_timeout=60)

    # ── HTTP ─────────────────────────────────────────────────────────────────
    httpd = HTTPServer(("0.0.0.0", HTTP_PORT), _Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True, name="http").start()

    # ── TLS server on 9999 ────────────────────────────────────────────────────
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

    # ── Plain server on 10000 (or 9999 if no TLS) ────────────────────────────
    plain_srv = await asyncio.start_server(
        _handler_plain, "0.0.0.0", plain_port if ssl_ctx else tls_port,
        limit=TCP_READER_LIMIT, backlog=8
    )
    servers.append(plain_srv)

    print("   ")
    print("╔══════════════════════════════════════════════════╗")
    print("║           SynchroGaea Server  v4.4               ║")
    print("╠══════════════════════════════════════════════════╣")
    if ssl_ctx:
        print(f"║  Android TLS   →  0.0.0.0:{tls_port}  (try first)      ║")
        print(f"║  Android plain →  0.0.0.0:{plain_port} (fallback)       ║")
        print(f"║  App field: YOUR_IP:{tls_port};YOUR_IP:{plain_port}           ║")
    else:
        print(f"║  Android plain →  0.0.0.0:{tls_port}                   ║")
    print(f"║  Web UI        →  http://0.0.0.0:{HTTP_PORT}            ║")
    print(f"║  WebSocket     →  0.0.0.0:{WS_PORT}                   ║")
    print("╠══════════════════════════════════════════════════╣")
    print(f"║  Open browser: http://127.0.0.1:{HTTP_PORT}             ║")
    print("╚══════════════════════════════════════════════════╝")
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
        print("\nServer stopped.")