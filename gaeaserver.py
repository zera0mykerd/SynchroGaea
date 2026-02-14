import socket
import struct
import threading
import tkinter as tk
from PIL import Image, ImageTk
import pyaudio
import time
import gc
from io import BytesIO

IP = '0.0.0.0'
PORT = 9999
SAMPLE_RATE = 44100
CHUNK = 1024

class GaeaServer:
    def __init__(self, root):
        self.root = root
        self.root.title("SynchroGaea - Server")
        self.root.geometry("1200x900")
        self.root.configure(bg="#121212")
        self.conn = None
        self.mic_active = False
        self.rotation_state = 0
        self.flip_horizontal = False
        self.latest_frame = None
        self.frame_lock = threading.Lock()
        self.last_img_tk = None
        self.p = pyaudio.PyAudio()
        self.audio_devices = self.get_input_devices()
        self.device_var = tk.StringVar(root)
        if self.audio_devices:
            self.device_var.set(list(self.audio_devices.keys())[0])
        self.status_var = tk.StringVar(value="OFFLINE")
        self.setup_param_inputs()
        self.video_frame = tk.Frame(root, bg="black")
        self.video_frame.pack(expand=True, fill=tk.BOTH)
        self.video_frame.pack_propagate(0)
        self.video_label = tk.Label(self.video_frame, text="SYSTEM READY - AWAITING UPLINK...", 
                                    bg="black", fg="#00FF00", font=("Courier", 16))
        self.video_label.pack(expand=True, fill=tk.BOTH)
        self.setup_controls()
            
    def get_input_devices(self):
        devices = {}
        for i in range(self.p.get_device_count()):
            dev_info = self.p.get_device_info_by_index(i)
            if dev_info.get('maxInputChannels') > 0:
                name = f"{i}: {dev_info.get('name')}"
                devices[name] = i
        return devices

    def setup_param_inputs(self):
        param_frame = tk.Frame(self.root, bg="#121212")
        param_frame.pack(side=tk.TOP, fill=tk.X, pady=10)
        tk.Label(param_frame, text="Mic:", bg="#121212", fg="white").pack(side=tk.LEFT, padx=5)
        self.device_menu = tk.OptionMenu(param_frame, self.device_var, *self.audio_devices.keys())
        self.device_menu.config(bg="#333", fg="white", width=20)
        self.device_menu.pack(side=tk.LEFT, padx=5)
        tk.Label(param_frame, text="Porta:", bg="#121212", fg="white").pack(side=tk.LEFT, padx=5)
        self.port_entry = tk.Entry(param_frame, width=6)
        self.port_entry.insert(0, str(PORT))
        self.port_entry.pack(side=tk.LEFT, padx=5)
        tk.Label(param_frame, text="Sample Rate:", bg="#121212", fg="white").pack(side=tk.LEFT, padx=5)
        self.sample_entry = tk.Entry(param_frame, width=6)
        self.sample_entry.insert(0, str(SAMPLE_RATE))
        self.sample_entry.pack(side=tk.LEFT, padx=5)
        tk.Label(param_frame, text="Chunk:", bg="#121212", fg="white").pack(side=tk.LEFT, padx=5)
        self.chunk_entry = tk.Entry(param_frame, width=6)
        self.chunk_entry.insert(0, str(CHUNK))
        self.chunk_entry.pack(side=tk.LEFT, padx=5)
        tk.Button(param_frame, text="Apply and connect", command=self.apply_params, bg="#27ae60", fg="white", font=("Arial", 10, "bold")).pack(side=tk.LEFT, padx=10)

    def list_audio_devices(self):
        info = self.p.get_host_api_info_by_index(0)
        numdevices = info.get('deviceCount')
        for i in range(0, numdevices):
            if (self.p.get_device_info_by_host_api_device_index(0, i).get('maxInputChannels')) > 0:
                print(f"Input Device id {i} - {self.p.get_device_info_by_host_api_device_index(0, i).get('name')}")

    def apply_params(self):
        global PORT, SAMPLE_RATE, CHUNK
        try:
            PORT = int(self.port_entry.get())
            CHUNK = int(self.chunk_entry.get())
            selected_device_name = self.device_var.get()
            input_device_id = self.audio_devices[selected_device_name]
            if hasattr(self, 'p'): self.p.terminate()
            self.p = pyaudio.PyAudio()
            rates_to_test = [44100, 48000, 16000, 32000, 8000]
            final_rate = None
            
            for rate in rates_to_test:
                try:
                    if self.p.is_format_supported(rate, input_device=input_device_id,
                                                 input_channels=1, input_format=pyaudio.paInt16):
                        final_rate = rate
                        break
                except: continue

            if not final_rate: raise Exception("None sample rate supported!")
            SAMPLE_RATE = final_rate
            self.output_stream = self.p.open(format=pyaudio.paInt16, channels=1, rate=SAMPLE_RATE, output=True)
            self.input_stream = self.p.open(format=pyaudio.paInt16, channels=1, rate=SAMPLE_RATE, 
                                           input=True, input_device_index=input_device_id, frames_per_buffer=CHUNK)
            self.status_var.set(f"SYNC OK: {SAMPLE_RATE}Hz")
            if not hasattr(self, 'threads_active'):
                self.socket_lock = threading.Lock()
                threading.Thread(target=self.video_render_loop, daemon=True).start()
                threading.Thread(target=self.start_socket, daemon=True).start()
                threading.Thread(target=self.system_cleaner_loop, daemon=True).start()
                self.threads_active = True
        except Exception as e:
            self.status_var.set(f"ERROR: {e}")

    def setup_controls(self):
        ctrl_frame = tk.Frame(self.root, bg="#121212")
        ctrl_frame.pack(side=tk.BOTTOM, fill=tk.X, pady=20)
        btn_style = {"font": ("Arial", 10, "bold"), "fg": "white", "width": 15, "height": 2}
        self.vibrate_btn = tk.Button(ctrl_frame, text="📳 VIBRATE", command=self.send_vibration, state=tk.DISABLED, bg="#e67e22", **btn_style)
        self.vibrate_btn.pack(side=tk.LEFT, padx=10, expand=True)
        self.mic_btn = tk.Button(ctrl_frame, text="🎙 MIC: OFF", command=self.toggle_mic, state=tk.DISABLED, bg="#c0392b", **btn_style)
        self.mic_btn.pack(side=tk.LEFT, padx=10, expand=True)
        tk.Button(ctrl_frame, text="↻ ROTATE", command=self.rotate_video, bg="#3498db", **btn_style).pack(side=tk.LEFT, padx=10, expand=True)
        tk.Button(ctrl_frame, text="⇄ MIRROR", command=self.toggle_mirror, bg="#9b59b6", **btn_style).pack(side=tk.LEFT, padx=10, expand=True)
        self.status_var = tk.StringVar(value="OFFLINE")
        self.status_bar = tk.Label(self.root, textvariable=self.status_var, bg="#333", fg="white", font=("Arial", 10))
        self.status_bar.pack(side=tk.BOTTOM, fill=tk.X)

    def system_cleaner_loop(self):
        while True:
            time.sleep(10)
            gc.collect()
            print("[SYSTEM] Deep clean executed.")

    def rotate_video(self): self.rotation_state = (self.rotation_state + 1) % 4
    def toggle_mirror(self): self.flip_horizontal = not self.flip_horizontal

    def recv_exact(self, n):
        data = bytearray()
        try:
            while len(data) < n:
                packet = self.conn.recv(n - len(data))
                if not packet: return None
                data.extend(packet)
            return data
        except:
            return None

    def start_socket(self):
        while True:
            try:
                with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                    s.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack('ii', 1, 0))
                    s.bind((IP, PORT))
                    s.listen(5)
                    while True:
                        self.conn, addr = s.accept()
                        self.conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                        self.conn.settimeout(5.0)
                        self.send_packet(3, f"SET_RATE:{SAMPLE_RATE}".encode())
                        self.status_var.set(f"CONNECTED TO: {addr[0]}")
                        self.root.after(0, self.enable_buttons)
                        try:
                            self.handle_client()
                        except: break
                        finally:
                            if self.conn: self.conn.close()
            except: time.sleep(2)

    def handle_client(self):
        self.conn.settimeout(0.1)
        last_heartbeat = time.time()
        while self.conn:
            try:
                if time.time() - last_heartbeat > 2.0:
                    self.send_packet(0, b"ALIVE")
                    last_heartbeat = time.time()
                header = self.recv_exact(5)
                if not header: 
                    continue
                msg_type = header[0]
                msg_len = struct.unpack('>I', header[1:5])[0]
                payload = self.recv_exact(msg_len)
                if payload is None: 
                    break
                if msg_type == 1:
                    with self.frame_lock: 
                        self.latest_frame = payload
                elif msg_type == 2:
                    try:
                        self.output_stream.write(bytes(payload))
                    except:
                        pass
            except socket.timeout:
                continue
            except Exception as e:
                print(f"Client handler error: {e}")
                break

    def video_render_loop(self):
        while True:
            data = None
            with self.frame_lock:
                if self.latest_frame:
                    data = self.latest_frame
                    self.latest_frame = None
            if data:
                try:
                    img = Image.open(BytesIO(data))
                    if self.rotation_state == 1: img = img.rotate(-90, expand=True)
                    elif self.rotation_state == 2: img = img.rotate(180)
                    elif self.rotation_state == 3: img = img.rotate(90, expand=True)
                    if self.flip_horizontal: img = img.transpose(Image.FLIP_LEFT_RIGHT)
                    w, h = self.video_label.winfo_width(), self.video_label.winfo_height()
                    if w > 10 and h > 10:
                        img = img.resize((w, h), Image.Resampling.BILINEAR) #LANCZOS for quality #BILINEAR for performance
                    img_tk = ImageTk.PhotoImage(image=img)
                    self.root.after(0, self.update_ui_image, img_tk)
                except: pass
            time.sleep(0.01)

    def update_ui_image(self, img_tk):
        self.video_label.config(image=img_tk, text="")
        self.last_img_tk = img_tk
        self.video_label.image = img_tk

    def send_packet(self, p_type, payload):
        if self.conn:
            try:
                with self.socket_lock:
                    header = struct.pack('>BI', p_type, len(payload))
                    self.conn.sendall(header + payload)
            except:
                self.conn = None

    def send_vibration(self): self.send_packet(3, b"VIBRATE")

    def toggle_mic(self):
        if not self.conn: return
        self.mic_active = not self.mic_active
        self.mic_btn.config(text="🎙 MIC: ON" if self.mic_active else "🎙 MIC: OFF",
                            bg="#2ecc71" if self.mic_active else "#c0392b")
        if self.mic_active:
            t = threading.Thread(target=self.audio_sender, daemon=True)
            t.start()

    def audio_sender(self):
        while self.mic_active and self.conn:
            try:
                data = self.input_stream.read(CHUNK, exception_on_overflow=False)
                self.send_packet(2, data)
            except: 
                break

    def enable_buttons(self):
        for btn in [self.vibrate_btn, self.mic_btn]: btn.config(state=tk.NORMAL)

    def disable_buttons(self):
        for btn in [self.vibrate_btn, self.mic_btn]: 
            btn.config(state=tk.DISABLED)
        self.mic_active = False
        self.mic_btn.config(text="🎙 MIC: OFF", bg="#c0392b")
        self.video_label.config(image='', text="UPLINK LOST - RECONNECTING...")
        
    def on_closing(self):
        print("\n[SYSTEM] Initiating clean shutdown...")
        self.mic_active = False
        try:
            if self.conn:
                print("[NETWORK] Notifying client and closing socket...")
                self.send_packet(3, b"SERVER_SHUTDOWN")
                self.conn.close()
            print("[AUDIO] Terminating PyAudio instance...")
            if hasattr(self, 'input_stream'): self.input_stream.stop_stream()
            if hasattr(self, 'output_stream'): self.output_stream.stop_stream()
            self.p.terminate()
        except Exception as e:
            print(f"[ERROR] Error during shutdown: {e}")
        print("[SYSTEM] Resources released. Goodbye!")
        self.root.destroy()

if __name__ == "__main__":
    gc.enable()
    root = tk.Tk()
    app = GaeaServer(root)
    root.protocol("WM_DELETE_WINDOW", app.on_closing)
    root.mainloop()
