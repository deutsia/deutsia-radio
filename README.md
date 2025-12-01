<div align="center">
<img width="192" height="192" src="assets/crop.png" align="center" alt="">

<br></br>

# <img src="assets/readme.png" width="25" align="absmiddle" /> deutsia radio
Privacy-focused, anti-censorship multinet radio player with Material You.

[<img src="https://img.shields.io/badge/Get_it_on_GitHub-181717?style=for-the-badge&logo=github" alt="Get it on GitHub" height="55">](https://github.com/deutsia/deutsia-radio/releases/latest) &nbsp;

---

</div>

<div align="center">
    <img src="assets/5.PNG" width="30%"  alt=""/>
    <img src="assets/2.png" width="30%"  alt=""/>
    <img src="assets/3.png" width="30%"  alt=""/>
</div>

---

## 📢 Updates
- **New Release:** Default theme, 14 language translations, app lock, global proxy support, bandwidth UI, and multi-word search

---

## 🎉 Features
- 🌐 **Multinet Support** - Clearnet, I2P (.i2p), and Tor (.onion)
- 🔒 **Force Tor Modes** - Proxy-or-fail enforcement with instant leak prevention
- 📡 **50,000+ Stations** - Access RadioBrowser API or add custom streams
- 🎨 **Material You** - Dynamic theming along with 5 color schemes
- 📝 **Stream Recording** - Record across multiple stations
- 🔐 **App Lock** - Secure your listening with biometric/PIN protection and optional database encrpytion 
- 🌍 **14 Languages** - Multilingual interface support
- 🌓 **Light/Dark/System** - Adaptive theme modes

---

## 🔐 Privacy & Security

**Optional Database Encryption** via SQLCipher:
- All local data encrypted at rest (stations, settings, history)
- App lock with biometric/PIN protection
- Secure storage for favorites and playback data

**Credential Protection** via Android Jetpack Security:
- Proxy passwords encrypted with AES-256-GCM
- Hardware-backed encryption keys (when available)
- Secure credential storage for custom proxy authentication

**Tor Integration** via Orbot SOCKS5 proxy:
- Instant disconnect detection (< 100ms)
- Fail-safe proxy enforcement - no clearnet fallback
- Automatic stream termination on proxy changes

**Force Tor Modes:**
- **Force Tor All** - Route everything through Tor
- **Force Tor Except I2P** - Tor for clearnet, I2P proxy for .i2p domains

**I2P Integration** via HTTP proxy (port 4444):
- Seamless .i2p domain routing
- Compatible with official I2P Android app

**Custom Proxies**
- Support for Digest and Basic auth
- Support for SOCKS4, SOCKS5, HTTP, and HTTPS proxies

---

## 📦 Requirements
- Android 7.0 (API 24) or higher
- **For Tor:** [Orbot](https://guardianproject.info/apps/org.torproject.android/)
- **For I2P:** [I2P Android](https://geti2p.net/en/download)

---

## 🛠️ Tech Stack
- Kotlin + Material Design 3
- ExoPlayer (Media3) for audio
- OkHttp with SOCKS/HTTP proxy support
- Coil for proxy-aware image loading

---

## 🏗️ Building

```bash
git clone https://github.com/deutsia/deutsia-radio.git
cd deutsia-radio
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/`

---

## 💬 Support Development

**Monero (XMR):**
```
83GGx86c6ZePiz8tEcGYtGJYmnjuP8W9cfLx6s98WAu8YkenjLr4zFC4RxcCk3hwFUiv59wS8KRPzNUUUqTrrYXCJAk4nrN
```

---

## 🙏 Acknowledgments
- [RadioBrowser](https://www.radio-browser.info/) - Station database
- [Orbot](https://guardianproject.info/apps/org.torproject.android/) - Tor integration
- [I2P Project](https://geti2p.net/) - Anonymous networking on Android
- Built with [Claude Code](https://github.com/anthropics/claude-code)
- [SQLCipher](https://www.zetetic.net/sqlcipher/) - Database encryption
---

## ⚠️ License

    deutsia radio

    Copyright (c) 2024 deutsia

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

---

<div align="center">
    <img src="assets/4.png" width="30%"  alt=""/>
    <img src="assets/1.png" width="30%"  alt=""/>
</div>
