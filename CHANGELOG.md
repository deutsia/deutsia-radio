# Changelog

All notable changes to deutsia radio will be documented in this file.

## [1.6.4]

### Improved
- **Sleep timer slider UI**: New slider-based sleep timer with preset options (5, 10, 15, 30, 45, 60 minutes) 
### Changed
- **Import stations API**: Import now downloads all stations (including offline/dead) via bulk download endpoint, instead of just online stations
### Fixed
- **Like button race condition**: Fixed sync issues between station list, mini player, and now playing screen where like status could become inconsistent
- **Cover art sizing**: Fixed radio icon shrinking occasionally in now playing screen and miniplayer cover art views

### Localization
- Added sleep timer translations to all 16 languages

## [1.6.3]

### Added
- **Updated Privacy station genre filter**: Genre filtering for Tor/I2P stations now properly uses Radio Registry's genre list
- **Updated Radio Registry-only browse mode**: Full search and filter UI when Radio Browser API is disabled
- **Updated Translations**

### Fixed
- **"All Stations" in privacy mode**: Now correctly loads all privacy stations instead of switching to clearnet
- **Privacy mode refresh**: Refresh action now stays in privacy mode when viewing Tor/I2P stations

### UI/UX Improvements
- Unified search and filter UI across all browse modes for consistent experience
- Added debounced search input with "Searching..." indicator for privacy stations
- Added results count display in privacy station browse mode


## [1.6.2]

### Added
- **Security tests**: Tests verifying privacy and security guarantees
- **API disable options**: Completely disable Radio Browser API + Radio Registry API for maximum privacy
- **Cover art disable option**: Prevent artwork loading to reduce network fingerprinting

### UI/UX Improvements
- Added like option to the Now Playing Screen


## [1.6.1]

### Added
- **Privacy Stations carousel now loads in parallel** Switched from loading Tor stations, then I2p, to both at the same time. 

### Changed
**Radio Registry API URL:** Updated Radio Registry API Url to the current Tor master url. This change exists because the API now utilizes Onionbalance, which is a load-balancing way of hosting Tor services, offering better reliability. 

## [1.6.0]

### Added
- **Dynamic API for I2P/Tor stations**: Privacy station lists now fetch live from Radio Registry API instead of using hardcoded bundled files
- **Online-only station filtering**: Import now only shows currently online stations, eliminating dead/stale streams

### Changed
- **Removed bundled station fallbacks**: Station import no longer falls back to outdated bundled JSON files
- **API-first architecture**: All privacy station imports now use Radio Registry API with proper error handling

### Fixed
- **PLS format detection**: Fixed bug where PLS playlist files were incorrectly identified as JSON format
- **Threading crash**: Fixed crash when loading privacy stations due to incorrect thread context
- **Cover art privacy**: Privacy station cover art now properly routes through Tor proxy when Tor is enabled
- **Station count sync**: Fixed mismatch between settings display and import dialog station counts
- **Import reliability**: Simplified API calls to match documented endpoint format for better stability

### UI/UX Improvements
- Updated playback translations to reflect dynamic API usage
- More accurate station descriptions in import dialog
- Better error messages when API fetch fails

## [1.5.2]

### Fixed
- **Tor/I2P streams loading as clearnet streams:** Fixed a bug where loading I2P/Tor streams in an alternative way (via the browse tab's genre chip list) would load them as clearnet streams. This is not a leak - if you were in Force Tor mode you would actually be able to load the Tor streams, as in Force Tor mode, it routes all streams, including clearnet (which the tor streams were incorrectly labeled as). 

### UI/UX Improvements
- **Improved Toast messages and disable option:** Added debounce for some messages, improved the Toast Diable option
- **Metadata fix:** For the list of Tor/I2P streams via the Browse tab's genre chip list: The stations now have the (genre) · Tor or (genre) · I2P like they do via the import option of Tor/i2p streams when you add them to library


## [1.5.1]

### Fixed
- **Browse tab add/remove buttons not updating after add/delete cycle:** Fixed a bug where the + button and heart icon in the browse tab would stop working correctly after adding and then removing a station. 
- **Browse tab not syncing with library changes:** Fixed another bug where deleting a station from the library tab wouldn't update the browse tab's button states. Now when returning to the browse tab, the saved/liked state is refreshed to reflect any changes made elsewhere.


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
