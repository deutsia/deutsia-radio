# Translation Guide for Deutsia Radio

## Current Translation Status

### ✅ Completed
- Base English strings in `app/src/main/res/values/strings.xml` (334 strings)
- Project structure ready for internationalization

### ⚠️ Action Required
- **~70+ hardcoded strings** found in Kotlin source files that need extraction
- Translation files need to be created for target languages

## Hardcoded Strings Requiring Extraction

The following files contain hardcoded UI strings that should be moved to `strings.xml`:

### High Priority (User-Facing UI)

#### 1. TorStatusView.kt (`app/src/main/java/com/opensource/i2pradio/ui/TorStatusView.kt`)
- Lines 88-154: Tor status messages, warnings, and descriptions
  - "Tor Off", "Connecting...", "Tor Connected", "Tor Error", "Install Orbot", "Leak Warning"
  - All status descriptions and tooltip texts

#### 2. TorQuickControlBottomSheet.kt (`app/src/main/java/com/opensource/i2pradio/ui/TorQuickControlBottomSheet.kt`)
- Lines 111-258: Bottom sheet dialog strings
  - Connection status messages
  - Button labels: "Connect to Tor", "Open Orbot", "Disconnect", "Retry Connection", "Install Orbot"
  - State descriptions for all Tor connection states

#### 3. CustomProxyStatusView.kt (`app/src/main/java/com/opensource/i2pradio/ui/CustomProxyStatusView.kt`)
- Lines 106-125: Custom proxy status messages
  - Configuration status descriptions
  - Privacy warnings

#### 4. AddEditRadioDialog.kt (`app/src/main/java/com/opensource/i2pradio/ui/AddEditRadioDialog.kt`)
- Lines 205-266: Proxy type labels
  - "I2P", "Tor", "Custom", "None"
  - Default values like "Other"

#### 5. LibraryFragment.kt (`app/src/main/java/com/opensource/i2pradio/ui/LibraryFragment.kt`)
- Lines 86-90: Complete genre list (32 genres)
  - "All Genres", "Alternative", "Ambient", "Blues", "Christian", "Classical", "Comedy", "Country", "Dance", "EDM", "Electronic", "Folk", "Funk", "Gospel", "Hip Hop", "Indie", "Jazz", "K-Pop", "Latin", "Lo-Fi", "Metal", "News", "Oldies", "Pop", "Punk", "R&B", "Reggae", "Rock", "Soul", "Sports", "Talk", "World", "Other"

### Medium Priority

#### 6. StationImportExport.kt (`app/src/main/java/com/opensource/i2pradio/util/StationImportExport.kt`)
- Line 87: `"deutsia radio"` (export metadata)
- Line 102: `"deutsia radio Station Export"` (M3U comment)

#### 7. TorService.kt (`app/src/main/java/com/opensource/i2pradio/tor/TorService.kt`)
- Line 132: `"SOCKS port: "` (notification text fragment)

### Low Priority (Technical/Non-translatable)

#### 8. RadioBrowserClient.kt
- Line 42: User-Agent string (typically not translated)

## Directory Structure for Translations

Locale-specific resources should be placed in directories following this pattern:
```
app/src/main/res/
├── values/              # Default (English)
│   └── strings.xml
├── values-es/           # Spanish
│   └── strings.xml
├── values-fr/           # French
│   └── strings.xml
├── values-de/           # German
│   └── strings.xml
├── values-pt/           # Portuguese
│   └── strings.xml
├── values-ru/           # Russian
│   └── strings.xml
├── values-zh/           # Chinese (Simplified)
│   └── strings.xml
├── values-ja/           # Japanese
│   └── strings.xml
├── values-ko/           # Korean
│   └── strings.xml
└── values-ar/           # Arabic
    └── strings.xml
```

## Common Language Codes

| Code | Language | Region |
|------|----------|--------|
| `values` | English | Default |
| `values-es` | Spanish | |
| `values-fr` | French | |
| `values-de` | German | |
| `values-it` | Italian | |
| `values-pt` | Portuguese | |
| `values-pt-rBR` | Portuguese | Brazil |
| `values-ru` | Russian | |
| `values-zh` | Chinese | Simplified |
| `values-zh-rTW` | Chinese | Traditional (Taiwan) |
| `values-ja` | Japanese | |
| `values-ko` | Korean | |
| `values-ar` | Arabic | |
| `values-hi` | Hindi | |
| `values-tr` | Turkish | |
| `values-pl` | Polish | |
| `values-nl` | Dutch | |
| `values-sv` | Swedish | |
| `values-no` | Norwegian | |
| `values-da` | Danish | |
| `values-fi` | Finnish | |

## How to Add a New Translation

1. **Create the locale directory:**
   ```bash
   mkdir -p app/src/main/res/values-{locale_code}
   ```

2. **Copy the base strings.xml:**
   ```bash
   cp app/src/main/res/values/strings.xml app/src/main/res/values-{locale_code}/
   ```

3. **Translate string values:**
   - Keep the `name` attributes unchanged (e.g., `name="app_name"`)
   - Only translate the content between the tags
   - Preserve formatting placeholders like `%s`, `%d`, `%1$s`, etc.
   - Keep special characters escaped (e.g., `\'`, `&amp;`)

4. **Example:**
   ```xml
   <!-- English (values/strings.xml) -->
   <string name="station_saved">%s added to library</string>

   <!-- Spanish (values-es/strings.xml) -->
   <string name="station_saved">%s agregada a la biblioteca</string>
   ```

## Translation Best Practices

### 1. Preserve Formatting
- **Placeholders:** `%s` (string), `%d` (number), `%1$s` (positional)
- **Example:** `"Recording saved: %s (%dKB)"` → Keep exact same placeholders

### 2. Escape Special Characters
- Apostrophes: `don\'t` not `don't`
- Ampersands: `&amp;` not `&`
- Quotes: `\"text\"` for embedded quotes

### 3. Maintain HTML Entities
- `&amp;` for ampersands (e.g., "Security &amp; Privacy")
- Keep XML-safe formatting

### 4. Context Matters
- Read surrounding strings for context
- Consider UI space constraints (buttons, tabs)
- Some strings have technical meanings (e.g., "SOCKS port")

### 5. Do Not Translate
- Proxy type names: "SOCKS4", "SOCKS5", "I2P", "Tor"
- Protocol names: "HTTP", "HTTPS"
- Technical terms: "SQLCipher", "Orbot"
- App name: "deutsia radio" (lowercase is intentional branding)
- URLs and domains

### 6. Brand Names and Proper Nouns
- **Orbot** - Keep as-is (official Tor client for Android)
- **Material You** - Keep as-is (Android design system)
- **Monero (XMR)** - Keep as-is (cryptocurrency name)

## Testing Translations

1. **Change device language:**
   - Settings → System → Languages → Add language
   - Android will automatically load the appropriate strings

2. **Test with pseudo-locale:**
   - Use `values-en-rXA` for pseudo-accented English
   - Helps identify hardcoded strings and layout issues

3. **Verify:**
   - All text displays correctly
   - No text overflow or truncation
   - Placeholders render properly
   - RTL languages (Arabic) display correctly

## String Extraction TODO

Before translations can be complete, extract these hardcoded strings to `strings.xml`:

### Tor Status Strings
```xml
<string name="tor_status_off">Tor Off</string>
<string name="tor_status_off_description">Tor is disconnected. Tap to connect.</string>
<string name="tor_status_connecting">Connecting...</string>
<string name="tor_status_connecting_description">Tor is connecting...</string>
<string name="tor_status_connected_title">Tor Connected</string>
<string name="tor_status_connected_description">Tor is connected. Tap to view details.</string>
<string name="tor_status_connected_force_description">Tor is connected (Force Mode). Tap to view details.</string>
<string name="tor_status_error">Tor Error</string>
<string name="tor_status_error_description">Tor connection failed. Tap to retry.</string>
<string name="tor_status_install_orbot">Install Orbot</string>
<string name="tor_status_install_orbot_description">Orbot is not installed. Tap to install.</string>
<string name="tor_status_leak_warning">Leak Warning</string>
<string name="tor_status_leak_warning_description">Force Tor is enabled but not connected. Privacy may be compromised.</string>
```

### Tor Bottom Sheet Strings
```xml
<string name="tor_sheet_disconnected_title">Tor Disconnected</string>
<string name="tor_sheet_disconnected_description">Tap Connect to browse anonymously via Tor</string>
<string name="tor_sheet_connecting_title">Connecting to Tor...</string>
<string name="tor_sheet_connecting_description">Establishing secure connection via Orbot</string>
<string name="tor_sheet_connected_title">Connected to Tor</string>
<string name="tor_sheet_connected_description">Your connection is private and anonymous</string>
<string name="tor_sheet_connected_force_title">Connected to Tor (Force Mode)</string>
<string name="tor_sheet_connected_force_description">Disconnect via Orbot or disable Force Tor in settings</string>
<string name="tor_sheet_failed_title">Connection Failed</string>
<string name="tor_sheet_failed_description">Could not connect to Tor network</string>
<string name="tor_sheet_orbot_required_title">Orbot Required</string>
<string name="tor_sheet_orbot_required_description">Install Orbot to connect to the Tor network</string>
<string name="tor_sheet_action_connect">Connect to Tor</string>
<string name="tor_sheet_action_disconnect">Disconnect</string>
<string name="tor_sheet_action_retry">Retry Connection</string>
<string name="tor_sheet_action_install">Install Orbot</string>
```

### Custom Proxy Strings
```xml
<string name="custom_proxy_configured_description">Custom proxy is configured. Tap to view details.</string>
<string name="custom_proxy_not_configured_description">Custom proxy is not configured. Tap to configure.</string>
<string name="custom_proxy_leak_warning_description">Force custom proxy enabled but not configured. Privacy may be compromised.</string>
```

### Genre List Strings
```xml
<string name="genre_all_genres">All Genres</string>
<string name="genre_alternative">Alternative</string>
<string name="genre_ambient">Ambient</string>
<string name="genre_blues">Blues</string>
<string name="genre_christian">Christian</string>
<string name="genre_classical">Classical</string>
<string name="genre_comedy">Comedy</string>
<string name="genre_country">Country</string>
<string name="genre_dance">Dance</string>
<string name="genre_edm">EDM</string>
<string name="genre_electronic">Electronic</string>
<string name="genre_folk">Folk</string>
<string name="genre_funk">Funk</string>
<string name="genre_gospel">Gospel</string>
<string name="genre_hiphop">Hip Hop</string>
<string name="genre_indie">Indie</string>
<string name="genre_jazz">Jazz</string>
<string name="genre_kpop">K-Pop</string>
<string name="genre_latin">Latin</string>
<string name="genre_lofi">Lo-Fi</string>
<string name="genre_metal">Metal</string>
<string name="genre_news">News</string>
<string name="genre_oldies">Oldies</string>
<string name="genre_pop">Pop</string>
<string name="genre_punk">Punk>
<string name="genre_rnb">R&amp;B</string>
<string name="genre_reggae">Reggae</string>
<string name="genre_rock">Rock</string>
<string name="genre_soul">Soul</string>
<string name="genre_sports">Sports</string>
<string name="genre_talk">Talk</string>
<string name="genre_world">World</string>
```

### Proxy Type Labels
```xml
<string name="proxy_type_i2p">I2P</string>
<string name="proxy_type_tor">Tor</string>
<string name="proxy_type_custom">Custom</string>
<string name="proxy_type_none">None</string>
```

### Export Metadata
```xml
<string name="export_app_name">deutsia radio</string>
<string name="export_title">deutsia radio Station Export</string>
```

### Service Strings
```xml
<string name="tor_service_socks_port_label">SOCKS port: </string>
```

## Contribution Guidelines

If you'd like to contribute translations:

1. Fork the repository
2. Create a new branch: `git checkout -b translation-{locale}`
3. Add your translation in the appropriate `values-{locale}/strings.xml`
4. Ensure all 334+ strings are translated
5. Test on a device with that locale
6. Submit a pull request

## Resources

- [Android Localization Guide](https://developer.android.com/guide/topics/resources/localization)
- [Android Supported Locales](https://developer.android.com/reference/java/util/Locale)
- [String Resources Documentation](https://developer.android.com/guide/topics/resources/string-resource)

## Questions?

For translation questions or issues, please open an issue on the GitHub repository.
