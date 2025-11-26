# <img src="assets/ic_launcher_monochrome.svg" alt="deutsia radio" height="1.2em" style="vertical-align: -0.15em;" /> deutsia radio
A privacy-focused multinet radio player built with Claude Code. Stream radio stations from clearnet, I2P, and TOR networks with advanced anonymity features.

[Add main screenshot here]

## Features

### 🎧 Radio Streaming
- [x] 50,000+ radio stations via RadioBrowser API
- [x] Custom station support (add any stream URL)
- [x] Equalizer with customizable bands
- [x] Stream recording with automatic file management
- [x] Record across station switches
- [x] Background playback with media controls
- [x] Sleep timer
- [x] Mini player with Now Playing screen

### 🌐 Multi-Network Support
- [x] **Clearnet** - Standard internet radio stations
- [x] **I2P Network** - Anonymous I2P radio streams (.i2p domains)
- [x] **TOR Network** - Onion service support via Orbot integration
- [x] Per-station proxy configuration
- [x] Automatic network detection

### 🔒 Privacy & Security Features

#### TOR Integration
deutsia radio integrates with **Orbot** (the official Tor client for Android) to provide robust anonymity:

- [x] Real-time TOR connection monitoring
- [x] Automatic TOR health checks (every 30 seconds)
- [x] SOCKS5 proxy support (port 9050)
- [x] Connection status indicators
- [x] One-tap TOR start/stop controls

[Add TOR control screenshot here]

#### Force TOR Mode 🛡️

**This is where deutsia radio becomes a privacy powerhouse.** Force TOR mode ensures ALL your traffic goes through TOR, preventing IP leaks:

**Force TOR All:**
- [x] Routes **ALL** traffic through TOR SOCKS5 proxy
- [x] Clearnet streams → TOR
- [x] RadioBrowser API metadata requests → TOR
- [x] Album art downloads → TOR
- [x] Everything encrypted and anonymized

**Force TOR Except I2P:**
- [x] Clearnet streams → TOR
- [x] I2P streams → I2P HTTP proxy (port 4444)
- [x] Best of both worlds: TOR for clearnet, I2P for .i2p domains

[Add Force TOR settings screenshot here]

#### Leak Prevention Architecture

**Multi-Layer Protection System:**

**1. Instant Orbot Broadcasts (Primary Protection)**
- Orbot sends immediate status updates when connecting/disconnecting
- App receives broadcasts in real-time (< 100ms)
- No waiting for health checks

**2. Fail-Safe Proxy Mode (Critical Safety Net)**
- When Force TOR enabled, all connections use explicit SOCKS5 proxy
- If TOR proxy unreachable, connections **FAIL** immediately
- **No clearnet fallback** - traffic never routes around the proxy
- OkHttp enforces proxy-or-nothing policy

**3. Periodic Health Checks (Backup Monitoring)**
- Socket checks every 30 seconds detect silent failures
- Updates UI status if Orbot crashes without broadcasting
- Fast socket response (~1-100ms per check)

**4. Automatic Stream Termination**
- Current streams stopped when proxy settings change
- Prevents old routing from persisting
- Forces re-connection with new security settings

**What happens if TOR disconnects mid-stream?**

```
Scenario 1 (Normal case):
Time 0s:  TOR connected, streaming ✓
Time 5s:  Orbot disconnects
Time 5s:  → Orbot broadcasts STATUS_OFF immediately
Time 5s:  → App updates: TOR STOPPED
Time 5s:  → Stream fails on next chunk request
Result:   No leak, instant detection

Scenario 2 (Orbot crashes silently):
Time 0s:  TOR connected, streaming ✓
Time 5s:  Orbot crashes without broadcast
Time 5s:  → Next stream chunk tries SOCKS proxy
Time 5s:  → Proxy unreachable → Connection FAILS
Time 35s: → Health check detects failure, updates UI
Result:   No leak, connection fails safe

Scenario 3 (You change Force TOR settings):
Time 0s:  Streaming with old proxy settings
Time 1s:  You toggle Force TOR mode
Time 1s:  → App automatically STOPS current stream
Time 1s:  → Must manually restart with new settings
Result:   No leak, forced re-connection
```

**Your traffic NEVER routes around the proxy.**

### 📚 Library Management
- [x] Favorites/liked stations
- [x] Browse by genre, country, language
- [x] Search functionality
- [x] Import/Export stations (CSV, JSON, M3U, PLS formats)
- [x] Curated I2P and TOR station lists (pre-configured)

### 🎨 Customization
- [x] Material You dynamic theming (Android 12+)
- [x] 5 color schemes (Blue, Peach, Green, Purple, Orange)
- [x] Light/Dark/System theme modes
- [x] Customizable equalizer presets

### 🔐 What Makes This App Secure?

**No Tracking or Ads:**
- [x] No analytics
- [x] No telemetry
- [x] No advertisements
- [x] No user tracking

**Privacy by Design:**
- [x] All proxy settings configurable per-station
- [x] Force TOR modes with automatic leak prevention
- [x] Open source - audit the code yourself
- [x] Local database (Room) - no cloud sync

**Attack Surface Protection:**

Our Force TOR implementation protects against:
- ✅ **IP Address Leaks** - Fail-safe proxy enforcement (no clearnet fallback)
- ✅ **Metadata Leaks** - API requests also routed through TOR
- ✅ **DNS Leaks** - SOCKS5 proxy handles DNS resolution
- ✅ **Timing Attacks** - Connection health checks don't reveal listening patterns
- ✅ **Stream Switch Leaks** - Automatic stream stopping on proxy changes
- ✅ **Silent Disconnection** - Instant Orbot broadcasts + fail-safe connections

## Installation

### Requirements
- Android 7.0 (API 24) or higher
- **For TOR stations:** [Orbot](https://guardianproject.info/apps/org.torproject.android/) installed
- **For I2P stations:** I2P router (I2PD or Java I2P) running locally

### Download
[Add download links - F-Droid, GitHub Releases, etc.]

## Usage

### Basic Streaming
1. Browse or search for stations
2. Tap to play
3. Use mini player controls

### Enabling TOR Protection

**Step 1:** Install Orbot from [F-Droid](https://f-droid.org/packages/org.torproject.android/) or Google Play

**Step 2:** In deutsia radio Settings:
- Enable "Orbot Integration"
- Tap "Start" to connect to TOR
- Wait for "Connected" status

**Step 3:** Choose your privacy level:
- **Force TOR All** - Maximum anonymity (all traffic through TOR)
- **Force TOR Except I2P** - Balanced (clearnet through TOR, I2P native)

⚠️ **Important:** When Force TOR is enabled but TOR disconnects, streams will **fail** rather than leak to clearnet. This is intentional security behavior.

[Add usage screenshots here]

### I2P Configuration
1. Install and run I2P router (I2PD recommended for Android)
2. Ensure I2P HTTP proxy is running on 127.0.0.1:4444
3. Import I2P station list from Settings
4. Stations with .i2p domains will automatically use I2P proxy

### Recording
1. Tap record button on Now Playing screen
2. Choose recording directory in Settings
3. Enable "Record Across Stations" for continuous recording

## Tech Stack

- **Language:** Kotlin
- **UI:** Material Design 3
- **Media Playback:** ExoPlayer (Media3)
- **Database:** Room
- **Networking:** OkHttp with SOCKS/HTTP proxy support
- **Image Loading:** Coil (proxy-aware)
- **TOR:** Orbot integration via SOCKS5
- **I2P:** HTTP proxy support

## Proxy Configuration

deutsia radio supports three proxy types:

| Type | Port | Use Case |
|------|------|----------|
| **TOR (SOCKS)** | 9050 | Clearnet anonymization via Orbot |
| **I2P (HTTP)** | 4444 | I2P network (.i2p domains) |
| **Custom** | Any | Your own proxy server |

## Building from Source

```bash
git clone https://github.com/deutsia/deutsia-radio.git
cd deutsia-radio
./gradlew assembleDebug
```

APK will be in `app/build/outputs/apk/debug/`

## Contributing

Contributions welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Submit a pull request

## Security Notice

While deutsia radio implements strong privacy protections:
- Force TOR modes route traffic through TOR with fail-safe enforcement
- Automatic leak prevention on proxy changes
- No built-in tracking or analytics
- Connections fail rather than bypass proxy

**However:**
- You are responsible for configuring Orbot/I2P correctly
- Always verify your TOR connection is active when using Force TOR
- This app does not provide complete anonymity on its own
- Use with proper OpSec practices

## License

[Add license information]

## Acknowledgments

- [RadioBrowser](https://www.radio-browser.info/) for the extensive station database
- [Orbot](https://guardianproject.info/apps/org.torproject.android/) for TOR integration
- [I2P Project](https://geti2p.net/) for anonymous networking
- Built with [Claude Code](https://github.com/anthropics/claude-code)

## Support Development

**Monero (XMR):**
```
83GGx86c6ZePiz8tEcGYtGJYmnjuP8W9cfLx6s98WAu8YkenjLr4zFC4RxcCk3hwFUiv59wS8KRPzNUUUqTrrYXCJAk4nrN
```
