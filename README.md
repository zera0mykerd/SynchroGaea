# 🛰️ SynchroGaea
**The ultimate high-performance bridge for remote eyes and ears.**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-3DDC84.svg?logo=android&logoColor=white)]()
[![Build: Professional](https://img.shields.io/badge/Build-Production--Ready-blue.svg)]()

**SynchroGaea** is a sophisticated Android background engine designed for real-time security and remote monitoring. It transforms any Android device into a powerful uplink node, streaming high-frequency video and bi-directional audio to a central server with military-grade persistence.

---

## ✨ Core Features

### 🎥 High-Speed Video Uplink
* **CameraX Integration:** Leverages the latest Android CameraX API for stable, lifecycle-aware streaming.
* **Dynamic Compression:** On-the-fly YUV-to-JPEG conversion with adjustable quality (30-60%) to balance bandwidth and clarity.
* **Configurable Resolution:** Supports everything from low-bandwidth 480p to HD 720p/1080p.
* **Intelligent Backpressure:** Uses `STRATEGY_KEEP_ONLY_LATEST` to ensure zero-latency monitoring even on unstable networks.

### 🎙️ Advanced Audio Engine
* **2-Way Communication:** Full-duplex audio. Hear the environment and talk back through the device.
* **Bluetooth SCO Support:** Industry-leading automatic routing. If a Bluetooth headset is connected, SynchroGaea automatically switches to the BT microphone for covert or extended-range listening.
* **Dynamic Sample Rates:** Remote commands can sync audio sample rates (e.g., 8kHz to 44.1kHz) on the fly without restarting the service.

### 🛡️ Ironclad Persistence
* **Foreground Survival:** Runs as a high-priority Foreground Service with a persistent notification to prevent the Android OOM (Out-of-Memory) killer from stopping the stream.
* **Auto-Boot:** Includes a `BootReceiver` to automatically resume the uplink the second the phone is powered on (`ACTION_BOOT_COMPLETED`).
* **Watchdog Protocol:** A built-in network watchdog monitors the socket health and force-reconnects if the data stream freezes for more than 40 seconds.

### 🎮 Remote Command & Control
* **Haptic Feedback:** Trigger device vibration remotely for signaling.
* **Socket-Based Commands:** A custom Type-Length-Value (TLV) protocol allowing for expandable remote control features.

---

## 🛠️ Technical Architecture

SynchroGaea is built for efficiency. Unlike heavy WebRTC implementations, it uses a lightweight, custom TCP Socket protocol optimized for low-latency transmission.

| Component | Technology | Role |
| :--- | :--- | :--- |
| **Video** | CameraX + YuvImage | Frame analysis & JPEG encoding |
| **Audio** | AudioRecord / AudioTrack | PCM 16-bit Raw capturing & playback |
| **Network** | Java Sockets (TCP) | Low-level DataOutputStream uplink |
| **Concurrency** | Kotlin Threads / Executors | Multi-threaded I/O to prevent UI lag |
| **Bluetooth** | AudioManager SCO | Wireless mic/speaker synchronization |

---

## 🚀 Use Cases
* **Security Surveillance:** Repurpose old smartphones as professional-grade IP cameras.
* **Covert Listening:** Utilize Bluetooth SCO for remote monitoring via wireless headsets.
* **Baby Monitor:** High-stability audio/video feed with talk-back capability.
* **Emergency Node:** A persistent uplink that survives reboots and network drops.

---

## 📦 Protocol Overview

The engine uses a simple but effective binary protocol:
* **Type 1:** JPEG Video Frames
* **Type 2:** PCM Audio Data
* **Type 3:** Command Strings (e.g., `VIBRATE`, `SET_RATE:44100`)

```kotlin
// Example Remote Command to sync audio
stream.writeByte(3) // Type: Command
stream.writeInt(cmdData.size)
stream.write("SET_RATE:44100".toByteArray())
```

##📜 License
Distributed under the MIT License. See LICENSE for more information.

##🤝 Contributing
Contributions are what make the open-source community such an amazing place to learn, inspire, and create. Any contributions you make are greatly appreciated.
