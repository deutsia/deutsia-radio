# Privacy Policy

**Last Updated:** December 18, 2025

**deutsia radio** ("the App") is an open-source, privacy-focused radio streaming application developed by deutsia. This Privacy Policy explains how the App handles your information.

## Summary

**deutsia radio does not collect, store, or transmit any personal data to us or third parties.** All data remains on your device.

## Information We Do NOT Collect

- Personal identification information
- Location data
- Device identifiers or advertising IDs
- Usage analytics or crash reports
- Email addresses or contact information

## Data Stored Locally on Your Device

The App stores the following data **locally on your device only**:

- **Favorite Stations** - Radio stations you mark as favorites
- **Custom Stations** - Stations you manually add to your library
- **Listening History** - Recently played stations
- **App Settings** - Your preferences (theme, proxy settings, etc.)
- **Recorded Audio** - Radio streams you choose to record (saved to your device storage)

This local data is:
- Never transmitted to us or any third party
- Optionally encrypted using SQLCipher (if you enable database encryption)
- Protected by app lock (biometric/PIN) if enabled
- Completely under your control and can be deleted at any time

## Third-Party Services

### RadioBrowser API

The App connects to [RadioBrowser](https://www.radio-browser.info/) to browse and search for radio stations. RadioBrowser is a free, open-source community database. When you search or browse stations:
- Your search queries are sent to RadioBrowser servers
- No personal information is transmitted
- RadioBrowser's privacy practices are governed by their own policies

### Radio Stream Providers

When you play a radio station, the App connects directly to that station's streaming server. Each station operator has their own privacy practices. The App does not control or monitor these connections beyond what is necessary for playback.

### Proxy Services (Optional)

The App does **not** include or operate any proxy software. If you configure the App to use Tor, I2P, or custom proxies:

- **Tor**: The App connects to external Tor proxy apps (such as InviZible Pro or Orbot) via localhost SOCKS5 (typically port 9050). You must install and run these apps separately.
- **I2P**: The App connects to the I2P Android app's HTTP proxy (typically port 4444). You must install and run the I2P app separately.
- **Custom Proxies**: You can configure any SOCKS4/SOCKS5/HTTP/HTTPS proxy of your choice.

The App simply routes traffic through whichever proxy you configure - it does not operate any proxy infrastructure itself. Each proxy service has their own privacy policies.

## Permissions

The App requests the following Android permissions:

| Permission | Purpose |
|------------|---------|
| Internet | Stream radio stations |
| Foreground Service | Continue playback when app is in background |
| Wake Lock | Prevent device from sleeping during playback |
| Notifications | Show playback controls in notification area |
| Storage/Media | Save recorded audio files to your device |
| Biometric | Optional app lock feature |

## Data Security

- **Local Encryption**: Optional SQLCipher database encryption protects your local data at rest
- **Credential Protection**: Proxy passwords are encrypted using AES-256-GCM via Android Jetpack Security
- **App Lock**: Optional biometric or PIN protection prevents unauthorized access
- **No Cloud Sync**: Your data never leaves your device

## Children's Privacy

The App does not knowingly collect any information from children under 13 years of age. The App does not require any personal information to function.

## Open Source

deutsia radio is open-source software. You can review the complete source code at:
https://github.com/deutsia/deutsia-radio

## Changes to This Policy

We may update this Privacy Policy from time to time. Any changes will be reflected in the "Last Updated" date above. Continued use of the App after changes constitutes acceptance of the updated policy.

## Contact

If you have questions about this Privacy Policy, you can:
- Open an issue on GitHub: https://github.com/deutsia/deutsia-radio/issues
- Email: bb7x89uo at anonaddy dot me

## Your Rights

Since we do not collect any personal data, there is no personal data to access, modify, or delete from our systems. All your data is stored locally on your device and can be managed through:
- Clearing app data in Android settings
- Uninstalling the app
- Using the app's built-in data management features

---

*This privacy policy applies to the deutsia radio application.*
