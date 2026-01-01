# Changelog

All notable changes to deutsia radio will be documented in this file.


## [1.5.0]

### Added
- **DNS protection system**: Force Tor and Force Custom Proxy modes now route all DNS queries through the proxy instead of leaking to system/ISP DNS
- **Comprehensive DNS leak tests**: Added 47 unit and integration tests to verify DNS protection and leak prevention across all proxy modes
- **Enhanced toast notifications**: Added informative messages for Force Tor/Proxy blocks, I2P proxy availability, and connection failures
- **SOCKS4 DNS warning**: Added UI warning in proxy configuration explaining SOCKS4 DNS limitations and recommending SOCKS5

### Fixed
- **Cover art privacy leak**: SecureImageLoader now properly blocks image loading from mid-session disconnection clearnet fallback and checks proxy availability before each request to catch mid-session disconnections
- **Playback UI sync**: Play/pause button now syncs when playback stops via headphone disconnect, audio focus loss, or notification actions

### UI/UX Improvements
- Replaced misleading "Leak Warning" with "Tor Required" (orange) when Force Tor mode/Force custom proxy mode blocks streams
- Improved search experience with 800ms debounce, "Searching..." indicator, and instant search on Enter
- Added more user-friendly toast messages
- Added Toast Disable option in Settings (important toast messages still display)

### Localization
- Updated all translation strings
    
    

## [1.4.5]

### Added
- **Auto-pause on Bluetooth disconnect**: Radio now automatically pauses when Bluetooth audio devices disconnect

### Fixed
- **API server resolution**: Fixed intermittent "Unable to resolve host" errors when browsing stations. The app now dynamically discovers available Radio Browser API servers instead of relying on a static list that could become stale.

### Other
- Updated I2P station list with additional station
## [1.4.4]
- **Fix Tor/I2P Loading in forceCustomProxyExceptTorI2P mode**
- **Updated Tor station**: Updated Tor address in bundled Tor radio station list
- **UI/UX Improvements**
## [1.4.3]

### Added
- **Android 7 fallback icons support**: Android 7.x (API 24-25) now uses PNG files from mipmap-{density}/ folders

## [1.4.2]

### Fixed
- **Tor state UI sync**: Auto-enable Tor preference when Tor connectivity is detected
- **Search & browse filtering**: Multiple filters now properly combine in API requests
- **UI layout**: Record and volume buttons no longer cut off in landscape mode

### Other
- Added privacy policy for Play Store compliance
- Updated assets and UI graphics

## [1.4.1] 

### Fixed
- Disable decryption bug

## [1.4.0]

### Added
- Dynamic station counts in import dialog (no more hardcoded values)
- Privacy notice translations for Tor and custom proxy settings
- Import UX improvements for I2P/Tor stations with privacy notices

### Changed
- Switched from Orbot to InviZible Pro for Tor integration as Invizible Pro proxy mode is easier to set up. 
- Simplified now playing loading animations with spinners
- Removed background color from station count bar for cleaner UI
- API now uses hidebroken=true to only show working stations in counts

### Fixed
- InviZible Pro Play Store link and package name
- All stations not showing full list
- Station loading limit (checks API response size before filtering)
- Pause between songs on radio streams

## [1.3.0]

### Added
- Skeleton screen loading animation to browse tab
- Content-Aware Loading design to Now Playing screen
- I2P and Tor genre chips to browse screen
- EDM to hardcoded genre chips
- All Stations category to show all filtered results
- Intelligent filter logic so filters respect each other
- Hero card carousels replacing country/language chips
- Browse tab redesign with discovery/results modes
- Intelligent horizontal swipe for tab navigation
- I2P and Tor tags to curated station cards

### Changed
- Dynamic miniplayer padding across all screens
- Improved horizontal swipe detection with velocity threshold
- Carousel-style touch handling for genre chips

### Fixed
- Metadata and title spacing consistency in Now Playing screen
- Tor status not syncing in settings when returning from other tabs
- Unlike button not working on browse cards
- Unlike button to update UI immediately
- Library station order and filter button visibility
- Hip Hop and other genre chips showing 0 stations
- I2P and Tor chips showing random unrelated stations
- Genre chips touch handling
- Category filter chip showing genre name instead of sort option
- Duplicate genre filter chips in browse results
- Search text display and cutoff in results mode
- Search bar cropping in browse results mode
- Browse screen scroll issues
- Unresolved reference errors in BrowseStationsFragment
- Heart and menu icons centering in library screen

### Localization
- Complete translations across 16 languages
- Localized carousel titles and browse strings
- Localized all hardcoded English text throughout the app

## [1.2.0]

### Added
- JSON import support for various playlist formats

### Fixed
- Signed APK crash by adding SQLCipher ProGuard rules
- App lock requiring re-authentication on UI theme changes
- ANR with database encryption
- Browse search+filter, app lock speed, streaming, and HTTP images
- Listening connection drops when phone is idle

## [1.1.0]

### Added
- Database encryption with SQLCipher (password-derived)
- App lock support with biometric authentication
- Global proxy support with Digest authentication
- Language switcher setting option
- Classic theme matching app icon colors as new default
- 16 language translations:
  - Arabic, Burmese, Chinese (Simplified), Farsi
  - German, Japanese, Korean, Portuguese
  - Russian, Spanish, Turkish, Ukrainian, Vietnamese
  - Hindi, Italian, French

### Changed
- Optimized app lock performance (skip SQLCipher overhead when encryption disabled)

### Fixed
- Database encryption crashes (multiple fixes for Room LiveData queries, SQLCipher)
- Background state loss (persist currently playing station)
- Critical memory leaks and security issues in proxy/database
- Theme color selection (added Classic as default, restored Blue option)
- Tor icon display and Force Tor mode UX
- Genre search translations in Browse and Library tabs
- Equalizer in SettingsFragment
- Tor status translations and contentDescription accessibility
- Nullable String type mismatches

## [1.0.0]

Initial release of deutsia radio.

### Features
- Stream internet radio stations via clearnet, I2P, and Tor networks
- Browse and search thousands of radio stations
- Save favorite stations to library
- Now Playing screen with station artwork and metadata
- Background playback with media controls
- Multiple theme color options
- Proxy configuration for I2P and Tor
