# Deutsia Radio: Hybrid API Architecture Visual Guide

> A comprehensive visual explanation of how we structure and use APIs in Deutsia Radio

---

## Table of Contents
1. [High-Level Architecture](#1-high-level-architecture)
2. [API Data Flow](#2-api-data-flow)
3. [Hybrid Approach Explained](#3-hybrid-approach-explained)
4. [Request Lifecycle](#4-request-lifecycle)
5. [Error Handling & Resilience](#5-error-handling--resilience)
6. [Proxy Routing](#6-proxy-routing)
7. [Database Integration](#7-database-integration)
8. [Component Relationships](#8-component-relationships)

---

## 1. High-Level Architecture

### The Three-Layer API System

```
┌─────────────────────────────────────────────────────────────────────────┐
│                            USER INTERFACE                                │
│  ┌──────────────────┐  ┌──────────────────┐  ┌─────────────────────┐   │
│  │ BrowseStations   │  │ LibraryFragment  │  │ NowPlayingFragment  │   │
│  │ Fragment         │  │                  │  │                     │   │
│  └────────┬─────────┘  └────────┬─────────┘  └─────────┬───────────┘   │
│           │                     │                       │               │
└───────────┼─────────────────────┼───────────────────────┼───────────────┘
            │                     │                       │
            │                     │                       │
┌───────────┼─────────────────────┼───────────────────────┼───────────────┐
│           ▼                     ▼                       ▼               │
│  ┌──────────────────┐  ┌──────────────────┐  ┌─────────────────────┐   │
│  │ BrowseViewModel  │  │ RadioRepository  │  │ RadioViewModel      │   │
│  │                  │  │                  │  │                     │   │
│  │ • Search         │  │ • Local CRUD     │  │ • Playback State    │   │
│  │ • Filters        │  │ • Sorting        │  │ • Current Station   │   │
│  │ • Pagination     │  │ • Genre Mgmt     │  │                     │   │
│  └────────┬─────────┘  └────────┬─────────┘  └─────────────────────┘   │
│           │                     │                                       │
│        VIEWMODEL LAYER                                                  │
└───────────┼─────────────────────┼───────────────────────────────────────┘
            │                     │
            │                     │
┌───────────┼─────────────────────┼───────────────────────────────────────┐
│           ▼                     ▼                                       │
│  ┌──────────────────────────────────────┐   ┌─────────────────────┐    │
│  │  RadioBrowserRepository              │   │  RadioRepository    │    │
│  │                                       │   │                     │    │
│  │  • API Integration                   │   │  • Room DAO Wrapper │    │
│  │  • Caching Logic                     │   │  • LiveData Queries │    │
│  │  • Deduplication                     │   │                     │    │
│  │  • Browse History                    │   │                     │    │
│  └──────────────┬───────────────────────┘   └──────────┬──────────┘    │
│                 │                                       │               │
│        REPOSITORY LAYER                                                 │
└─────────────────┼───────────────────────────────────────┼───────────────┘
                  │                                       │
                  │                                       │
┌─────────────────┼───────────────────────────────────────┼───────────────┐
│                 ▼                                       ▼               │
│  ┌──────────────────────────┐              ┌────────────────────────┐  │
│  │ RadioBrowserClient       │              │ Room Database          │  │
│  │                          │              │                        │  │
│  │ • HTTP Requests          │              │ ┌──────────────────┐   │  │
│  │ • OkHttp3 Client         │              │ │ RadioDao         │   │  │
│  │ • JSON Parsing           │              │ │                  │   │  │
│  │ • Server Failover        │              │ │ • SQL Queries    │   │  │
│  │ • Retry Logic            │              │ │ • CRUD Ops       │   │  │
│  └───────────┬──────────────┘              │ └──────────────────┘   │  │
│              │                              └────────────────────────┘  │
│     CLIENT / DATABASE LAYER                                             │
└──────────────┼──────────────────────────────────────────────────────────┘
               │
               │
┌──────────────┼──────────────────────────────────────────────────────────┐
│              ▼                                                           │
│  ┌────────────────────────────────────────────────────────────┐         │
│  │             EXTERNAL RADIOBROWSER API                       │         │
│  │                                                             │         │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │         │
│  │  │ de1.api...   │  │ nl1.api...   │  │ at1.api...   │     │         │
│  │  │ (Germany)    │  │ (Netherlands)│  │ (Austria)    │     │         │
│  │  └──────────────┘  └──────────────┘  └──────────────┘     │         │
│  │                                                             │         │
│  │           50,000+ Radio Stations Worldwide                 │         │
│  └────────────────────────────────────────────────────────────┘         │
│                                                                          │
│                          REMOTE API LAYER                                │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 2. API Data Flow

### Search Request Flow (Step-by-Step)

```
USER TYPES "Jazz" IN SEARCH BOX
    │
    ▼
┌───────────────────────────────────────────────────────────┐
│ Step 1: UI Layer (BrowseStationsFragment)                 │
│                                                            │
│  • EditText onChange triggered                            │
│  • Debounce timer started (500ms)                         │
│  • After delay, calls: viewModel.search("Jazz")           │
└────────────────────────────┬───────────────────────────────┘
                             │
                             ▼
┌───────────────────────────────────────────────────────────┐
│ Step 2: ViewModel Layer (BrowseViewModel)                 │
│                                                            │
│  • _searchQuery.value = "Jazz"                            │
│  • _currentCategory.value = BrowseCategory.SEARCH         │
│  • _isLoading.value = true                                │
│  • viewModelScope.launch { fetchStations() }              │
└────────────────────────────┬───────────────────────────────┘
                             │
                             ▼
┌───────────────────────────────────────────────────────────┐
│ Step 3: Determine Search Strategy                         │
│                                                            │
│  IF "Jazz" is single word:                                │
│    → searchByName("Jazz", limit=50, offset=0)             │
│                                                            │
│  IF "Jazz Piano" (multi-word):                            │
│    → performIntelligentSearch()                           │
│      • Search word 1: "Jazz"                              │
│      • Search word 2: "Piano"                             │
│      • Combine & deduplicate results                      │
└────────────────────────────┬───────────────────────────────┘
                             │
                             ▼
┌───────────────────────────────────────────────────────────┐
│ Step 4: Repository Layer (RadioBrowserRepository)         │
│                                                            │
│  repository.searchByName("Jazz", limit=50, offset=0)      │
│      │                                                     │
│      └──> Delegates to RadioBrowserClient                 │
└────────────────────────────┬───────────────────────────────┘
                             │
                             ▼
┌───────────────────────────────────────────────────────────┐
│ Step 5: HTTP Client (RadioBrowserClient)                  │
│                                                            │
│  • Build URL:                                             │
│    https://de1.api.radio-browser.info/json/               │
│           stations/byname/Jazz?limit=50&offset=0&         │
│           hidebroken=true&order=votes&reverse=true        │
│                                                            │
│  • Add Headers:                                           │
│    User-Agent: DeutsiaRadio/1.0 (Android; +https://...)   │
│    Accept: application/json                               │
│                                                            │
│  • Check Force Tor Settings:                              │
│    IF enabled → route through 127.0.0.1:9050             │
│    ELSE → direct connection                               │
│                                                            │
│  • Set timeout: 30s (direct) / 60s (proxy)                │
└────────────────────────────┬───────────────────────────────┘
                             │
                             ▼
┌───────────────────────────────────────────────────────────┐
│ Step 6: Network Request                                   │
│                                                            │
│  OkHttp3:                                                  │
│  val request = Request.Builder()                          │
│      .url(fullUrl)                                        │
│      .header("User-Agent", userAgent)                     │
│      .header("Accept", "application/json")                │
│      .build()                                             │
│                                                            │
│  val response = client.newCall(request).execute()         │
└────────────────────────────┬───────────────────────────────┘
                             │
                             ▼
┌───────────────────────────────────────────────────────────┐
│ Step 7: Response Handling                                 │
│                                                            │
│  IF response.isSuccessful:                                │
│    • Get JSON body: response.body?.string()               │
│    • Parse JSON → List<RadioBrowserStation>               │
│    • Return RadioBrowserResult.Success(stations)          │
│                                                            │
│  ELSE:                                                     │
│    • Log error with status code                           │
│    • Cycle to next server (nl1.api...)                   │
│    • Retry request (up to 2 more times)                   │
│    • If all fail → RadioBrowserResult.Error(...)          │
└────────────────────────────┬───────────────────────────────┘
                             │
                             ▼
┌───────────────────────────────────────────────────────────┐
│ Step 8: JSON Parsing (RadioBrowserClient)                 │
│                                                            │
│  fun parseStationsJson(json: String) {                    │
│      val jsonArray = JSONArray(json)                      │
│      val stations = mutableListOf<RadioBrowserStation>()  │
│                                                            │
│      for (i in 0 until jsonArray.length()) {              │
│          val obj = jsonArray.getJSONObject(i)             │
│          val station = RadioBrowserStation(               │
│              stationuuid = obj.getString("stationuuid"),  │
│              name = obj.getString("name"),                │
│              url = obj.getString("url"),                  │
│              urlResolved = obj.getString("url_resolved"), │
│              // ... 25+ more fields                        │
│          )                                                 │
│          if (station.name.isNotBlank()) {                 │
│              stations.add(station)                        │
│          }                                                 │
│      }                                                     │
│      return stations                                      │
│  }                                                         │
└────────────────────────────┬───────────────────────────────┘
                             │
                             ▼
┌───────────────────────────────────────────────────────────┐
│ Step 9: Back to ViewModel - Post-Processing               │
│                                                            │
│  when (result) {                                          │
│      is Success -> {                                      │
│          _stations.value = result.data                    │
│          _isLoading.value = false                         │
│                                                            │
│          // CRITICAL: Check saved/liked status            │
│          checkSavedStatus(result.data)                    │
│      }                                                     │
│      is Error -> {                                        │
│          _errorMessage.value = result.message             │
│          _isLoading.value = false                         │
│      }                                                     │
│  }                                                         │
└────────────────────────────┬───────────────────────────────┘
                             │
                             ▼
┌───────────────────────────────────────────────────────────┐
│ Step 10: Batch Status Check (Avoid N+1 Problem)           │
│                                                            │
│  private suspend fun checkSavedStatus(stations) {         │
│      val uuids = stations.map { it.stationuuid }          │
│                                                            │
│      // SINGLE database query for all stations            │
│      val stationInfoMap = repository                      │
│          .getStationInfoByUuids(uuids)                    │
│          .associateBy { it.uuid }                         │
│                                                            │
│      _savedStationUuids.value = stationInfoMap            │
│          .filter { it.value.isSaved }                     │
│          .keys                                            │
│                                                            │
│      _likedStationUuids.value = stationInfoMap            │
│          .filter { it.value.isLiked }                     │
│          .keys                                            │
│  }                                                         │
│                                                            │
│  // Instead of 50 queries (bad):                          │
│  // for (station in stations) {                           │
│  //     isStationSaved(station.uuid) // N+1 anti-pattern  │
│  // }                                                      │
└────────────────────────────┬───────────────────────────────┘
                             │
                             ▼
┌───────────────────────────────────────────────────────────┐
│ Step 11: UI Update (Observer Pattern)                     │
│                                                            │
│  BrowseStationsFragment observes:                         │
│      viewModel.stations.observe(this) { stationList ->    │
│          adapter.submitList(stationList)                  │
│          recyclerView.scrollToPosition(0)                 │
│      }                                                     │
│                                                            │
│      viewModel.savedStationUuids.observe(this) { uuids -> │
│          adapter.updateSavedStatus(uuids)                 │
│      }                                                     │
│                                                            │
│      viewModel.likedStationUuids.observe(this) { uuids -> │
│          adapter.updateLikedStatus(uuids)                 │
│      }                                                     │
│                                                            │
│      viewModel.isLoading.observe(this) { loading ->       │
│          progressBar.visibility = if (loading)            │
│              View.VISIBLE else View.GONE                  │
│      }                                                     │
└────────────────────────────┬───────────────────────────────┘
                             │
                             ▼
┌───────────────────────────────────────────────────────────┐
│ Step 12: RecyclerView Display                             │
│                                                            │
│  ┌─────────────────────────────────────────────┐          │
│  │ 🎵 Jazz FM (Paris)                 ♥️ 💾    │          │
│  │ Paris, France • Jazz • 128kbps             │          │
│  ├─────────────────────────────────────────────┤          │
│  │ 🎵 Smooth Jazz 24/7            ♥️ 💾       │          │
│  │ USA • Jazz, Smooth Jazz • 96kbps           │          │
│  ├─────────────────────────────────────────────┤          │
│  │ 🎵 Jazz Radio Berlin                  💾   │          │
│  │ Berlin, Germany • Jazz • 192kbps           │          │
│  └─────────────────────────────────────────────┘          │
│                                                            │
│  User can now:                                            │
│  • Tap to play station                                    │
│  • Tap ♥️ to like/unlike                                  │
│  • Tap 💾 to save/remove from library                     │
│  • Long-press for context menu                            │
└────────────────────────────────────────────────────────────┘

TOTAL TIME: ~200-500ms (direct) / 1-3s (via Tor)
```

---

## 3. Hybrid Approach Explained

### Why "Hybrid"?

We use **TWO data sources** working together:

```
┌──────────────────────────────────────────────────────────────────┐
│                    HYBRID ARCHITECTURE                            │
│                                                                   │
│  ┌────────────────────────┐      ┌─────────────────────────┐    │
│  │  EXTERNAL API          │      │  LOCAL DATABASE         │    │
│  │  (RadioBrowser)        │      │  (Room SQLite)          │    │
│  │                        │      │                         │    │
│  │  ✓ 50,000+ stations    │      │  ✓ User's library       │    │
│  │  ✓ Live search         │      │  ✓ Liked stations       │    │
│  │  ✓ Metadata updates    │      │  ✓ Custom stations      │    │
│  │  ✓ Discovery           │      │  ✓ Offline access       │    │
│  │  ✓ Country/genre data  │      │  ✓ Fast queries         │    │
│  │                        │      │  ✓ Browse history       │    │
│  │  ✗ Requires network    │      │  ✓ No network needed    │    │
│  │  ✗ Slower (200-500ms)  │      │  ✓ Instant (<10ms)      │    │
│  │  ✗ Privacy concerns    │      │  ✓ Private              │    │
│  └────────────┬───────────┘      └────────────┬────────────┘    │
│               │                               │                  │
│               └───────────┬───────────────────┘                  │
│                           │                                      │
│                           ▼                                      │
│              ┌─────────────────────────┐                         │
│              │  REPOSITORY LAYER       │                         │
│              │                         │                         │
│              │  • Coordinates both     │                         │
│              │  • Deduplication logic  │                         │
│              │  • Caching strategy     │                         │
│              │  • Sync on save         │                         │
│              └─────────────────────────┘                         │
└──────────────────────────────────────────────────────────────────┘
```

### Data Flow Between Sources

```
┌─────────────────────────────────────────────────────────────────┐
│                  BROWSE MODE (External API)                      │
│                                                                  │
│  User searches "Classical" in Browse tab                         │
│        │                                                         │
│        ▼                                                         │
│  RadioBrowserClient fetches 50 stations                         │
│        │                                                         │
│        ▼                                                         │
│  Check which are already saved:                                 │
│  ┌──────────────────────────────────────────────────┐           │
│  │ SELECT * FROM radio_stations                     │           │
│  │ WHERE radioBrowserUuid IN (uuid1, uuid2, ...)    │           │
│  └──────────────────────────────────────────────────┘           │
│        │                                                         │
│        ▼                                                         │
│  Display with icons:                                            │
│  • 💾 = Already in library                                      │
│  • ♥️ = Liked                                                   │
│  • [empty] = Not saved                                          │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                  LIBRARY MODE (Local Database)                   │
│                                                                  │
│  User opens Library tab                                         │
│        │                                                         │
│        ▼                                                         │
│  Query local database ONLY:                                     │
│  ┌──────────────────────────────────────────────────┐           │
│  │ SELECT * FROM radio_stations                     │           │
│  │ ORDER BY lastPlayedAt DESC                       │           │
│  └──────────────────────────────────────────────────┘           │
│        │                                                         │
│        ▼                                                         │
│  Display instantly (no network needed)                          │
│  • User's saved stations                                        │
│  • Custom added stations                                        │
│  • Bundled preset stations                                      │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│              SAVE ACTION (Hybrid Coordination)                   │
│                                                                  │
│  User taps "Save" on a Browse result                            │
│        │                                                         │
│        ▼                                                         │
│  1. Check if already exists:                                    │
│     ┌──────────────────────────────────────────────┐            │
│     │ SELECT COUNT(*) FROM radio_stations          │            │
│     │ WHERE radioBrowserUuid = 'abc123'            │            │
│     └──────────────────────────────────────────────┘            │
│        │                                                         │
│        ▼                                                         │
│  2. If NOT exists:                                              │
│     Convert RadioBrowserStation → RadioStation                  │
│     ┌──────────────────────────────────────────────┐            │
│     │ INSERT INTO radio_stations (                 │            │
│     │   name, streamUrl, genre, country,           │            │
│     │   radioBrowserUuid, source, bitrate,         │            │
│     │   codec, cachedAt, addedTimestamp, ...       │            │
│     │ ) VALUES (...)                               │            │
│     └──────────────────────────────────────────────┘            │
│        │                                                         │
│        ▼                                                         │
│  3. Update UI state:                                            │
│     _savedStationUuids.value += station.stationuuid             │
│        │                                                         │
│        ▼                                                         │
│  4. Station now appears in:                                     │
│     • Library tab (local query)                                 │
│     • Browse results show 💾 icon                               │
│     • Can be played offline (URL cached)                        │
└─────────────────────────────────────────────────────────────────┘
```

### Deduplication Strategy

```
┌──────────────────────────────────────────────────────────────────┐
│         HOW WE PREVENT DUPLICATE STATIONS                         │
│                                                                   │
│  RadioBrowser Station                  Local Station             │
│  ┌─────────────────────┐              ┌─────────────────────┐    │
│  │ stationuuid: "abc"  │              │ id: 1               │    │
│  │ name: "Jazz FM"     │     LINK     │ radioBrowserUuid:   │    │
│  │ url: "http://..."   │  ◀──────────▶│    "abc"            │    │
│  └─────────────────────┘              │ name: "Jazz FM"     │    │
│                                        │ source: RADIOBROWSER│    │
│                                        └─────────────────────┘    │
│                                                                   │
│  Before saving from Browse:                                      │
│  ┌────────────────────────────────────────────────────┐          │
│  │ val existingCount = dao.countByRadioBrowserUuid(   │          │
│  │     station.stationuuid                            │          │
│  │ )                                                  │          │
│  │                                                    │          │
│  │ if (existingCount > 0) {                           │          │
│  │     // Already saved - show toast                 │          │
│  │     return                                         │          │
│  │ }                                                  │          │
│  │                                                    │          │
│  │ // Safe to insert                                 │          │
│  │ dao.insertStation(convertToLocal(station))        │          │
│  └────────────────────────────────────────────────────┘          │
│                                                                   │
│  This ensures:                                                   │
│  ✓ Same station can't be saved twice                            │
│  ✓ UUID is the unique key (not name, which can change)          │
│  ✓ Fast lookup (indexed column)                                 │
└──────────────────────────────────────────────────────────────────┘
```

---

## 4. Request Lifecycle

### Complete HTTP Request Journey

```
┌─────────────────────────────────────────────────────────────────┐
│ PHASE 1: REQUEST PREPARATION                                    │
└─────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│ RadioBrowserClient.executeRequest()                             │
│                                                                  │
│  1. Select API server:                                          │
│     currentServerIndex = 0                                      │
│     server = servers[0] // "de1.api.radio-browser.info"        │
│                                                                  │
│  2. Build full URL:                                             │
│     https://de1.api.radio-browser.info/json/stations/search?... │
│                                                                  │
│  3. Check Force Tor setting:                                    │
│     ┌─────────────────────────────────────────┐                │
│     │ IF Force Tor enabled:                   │                │
│     │   • Check Tor is connected               │                │
│     │   • IF NOT connected:                    │                │
│     │       return Error("Tor required")       │                │
│     │   • ELSE: proceed with proxy             │                │
│     └─────────────────────────────────────────┘                │
│                                                                  │
│  4. Build HTTP client:                                          │
│     ┌─────────────────────────────────────────┐                │
│     │ val builder = OkHttpClient.Builder()    │                │
│     │                                          │                │
│     │ IF using Tor proxy:                      │                │
│     │   builder.proxy(Proxy(                  │                │
│     │     Proxy.Type.SOCKS,                   │                │
│     │     InetSocketAddress("127.0.0.1", 9050)│                │
│     │   ))                                     │                │
│     │   builder.connectTimeout(60, SECONDS)   │                │
│     │ ELSE:                                    │                │
│     │   builder.connectTimeout(30, SECONDS)   │                │
│     │                                          │                │
│     │ builder.readTimeout(timeout, SECONDS)   │                │
│     │ val client = builder.build()            │                │
│     └─────────────────────────────────────────┘                │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│ PHASE 2: REQUEST EXECUTION                                       │
└─────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│  5. Create request object:                                       │
│     ┌─────────────────────────────────────────┐                │
│     │ val request = Request.Builder()         │                │
│     │   .url(fullUrl)                         │                │
│     │   .header("User-Agent",                 │                │
│     │       "DeutsiaRadio/1.0 (Android; ...)" │                │
│     │   .header("Accept", "application/json") │                │
│     │   .build()                               │                │
│     └─────────────────────────────────────────┘                │
│                                                                  │
│  6. Execute with retry loop:                                    │
│     ┌─────────────────────────────────────────┐                │
│     │ repeat(retries + 1) { attempt ->        │                │
│     │   try {                                 │                │
│     │     val response = client               │                │
│     │       .newCall(request)                 │                │
│     │       .execute()                        │                │
│     │                                          │                │
│     │     // Check response...                │                │
│     │   } catch (e: Exception) {              │                │
│     │     // Handle error...                  │                │
│     │   }                                      │                │
│     │ }                                        │                │
│     └─────────────────────────────────────────┘                │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│ PHASE 3: RESPONSE HANDLING                                       │
└─────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│  7. Check response status:                                       │
│                                                                  │
│     IF response.isSuccessful (200-299):                         │
│     ┌─────────────────────────────────────────┐                │
│     │ val body = response.body?.string()      │                │
│     │                                          │                │
│     │ if (body.isNullOrEmpty()) {             │                │
│     │   Log.w("Empty response from server")   │                │
│     │   // Retry with next server              │                │
│     │   return@repeat                          │                │
│     │ }                                        │                │
│     │                                          │                │
│     │ // Parse JSON                            │                │
│     │ val stations = parseStationsJson(body)  │                │
│     │                                          │                │
│     │ return RadioBrowserResult.Success(      │                │
│     │   stations                               │                │
│     │ )                                        │                │
│     └─────────────────────────────────────────┘                │
│                                                                  │
│     ELSE (4xx, 5xx errors):                                     │
│     ┌─────────────────────────────────────────┐                │
│     │ Log.w("HTTP ${response.code}")          │                │
│     │                                          │                │
│     │ if (attempt < retries) {                │                │
│     │   cycleServer() // Try next server      │                │
│     │   return@repeat // Retry                │                │
│     │ }                                        │                │
│     └─────────────────────────────────────────┘                │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│ PHASE 4: PARSE & TRANSFORM                                       │
└─────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│  8. Parse JSON response:                                         │
│                                                                  │
│     fun parseStationsJson(json: String): List<...> {            │
│       val stations = mutableListOf<RadioBrowserStation>()       │
│       val jsonArray = JSONArray(json)                           │
│                                                                  │
│       for (i in 0 until jsonArray.length()) {                   │
│         try {                                                    │
│           val obj = jsonArray.getJSONObject(i)                  │
│                                                                  │
│           val station = RadioBrowserStation(                    │
│             stationuuid = obj.getString("stationuuid"),         │
│             name = obj.getString("name"),                       │
│             url = obj.optString("url", ""),                     │
│             urlResolved = obj.optString("url_resolved", ""),    │
│             favicon = obj.optString("favicon", ""),             │
│             tags = obj.optString("tags", ""),                   │
│             country = obj.optString("country", ""),             │
│             countrycode = obj.optString("countrycode", ""),     │
│             state = obj.optString("state", ""),                 │
│             language = obj.optString("language", ""),           │
│             votes = obj.optInt("votes", 0),                     │
│             codec = obj.optString("codec", ""),                 │
│             bitrate = obj.optInt("bitrate", 0),                 │
│             // ... 15+ more fields                               │
│           )                                                      │
│                                                                  │
│           // Validation                                          │
│           if (station.name.isNotBlank() &&                      │
│               station.urlResolved.isNotBlank()) {               │
│             stations.add(station)                               │
│           }                                                      │
│                                                                  │
│         } catch (e: JSONException) {                            │
│           // Skip malformed station, continue                   │
│           Log.w("Failed to parse station $i: ${e.message}")     │
│         }                                                        │
│       }                                                          │
│                                                                  │
│       return stations                                           │
│     }                                                            │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│ PHASE 5: RESULT DELIVERY                                         │
└─────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│  9. Wrap in Result type:                                         │
│                                                                  │
│     SUCCESS CASE:                                               │
│     ┌─────────────────────────────────────────┐                │
│     │ RadioBrowserResult.Success(             │                │
│     │   data = List<RadioBrowserStation>      │                │
│     │ )                                        │                │
│     └─────────────────────────────────────────┘                │
│                  │                                               │
│                  ▼                                               │
│     Repository receives ──▶ ViewModel processes                 │
│                  │                                               │
│                  ▼                                               │
│     LiveData updated ──▶ UI observes ──▶ RecyclerView displays  │
│                                                                  │
│     ERROR CASE:                                                 │
│     ┌─────────────────────────────────────────┐                │
│     │ RadioBrowserResult.Error(               │                │
│     │   message = "Failed after 3 attempts",  │                │
│     │   exception = lastException              │                │
│     │ )                                        │                │
│     └─────────────────────────────────────────┘                │
│                  │                                               │
│                  ▼                                               │
│     ViewModel sets error message ──▶ UI shows toast/banner      │
└─────────────────────────────────────────────────────────────────┘

TYPICAL TIMING:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Direct:     50-200ms (DNS) + 100-300ms (HTTP) = 150-500ms
  Via Tor:    500-1500ms (circuit) + 500-2000ms (HTTP) = 1-3.5s
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

## 5. Error Handling & Resilience

### Multi-Level Retry Strategy

```
┌───────────────────────────────────────────────────────────────────┐
│                    RESILIENCE ARCHITECTURE                         │
└───────────────────────────────────────────────────────────────────┘

LEVEL 1: SERVER FAILOVER
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

┌─────────────────────────────────────────────────────────────────┐
│  Three API servers in rotation:                                 │
│                                                                  │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐                │
│  │   Server 1 │  │   Server 2 │  │   Server 3 │                │
│  │  (Germany) │  │(Netherlands)│  │  (Austria) │                │
│  │            │  │            │  │            │                │
│  │  de1.api.  │  │  nl1.api.  │  │  at1.api.  │                │
│  │  radio-    │  │  radio-    │  │  radio-    │                │
│  │  browser   │  │  browser   │  │  browser   │                │
│  │  .info     │  │  .info     │  │  .info     │                │
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘                │
│        │               │               │                         │
│        └───────────────┼───────────────┘                         │
│                        │                                         │
│                        ▼                                         │
│              currentServerIndex = 0                              │
│                                                                  │
│  On failure:                                                     │
│    cycleServer() -> currentServerIndex = (current + 1) % 3      │
└─────────────────────────────────────────────────────────────────┘


LEVEL 2: RETRY MECHANISM
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

┌─────────────────────────────────────────────────────────────────┐
│  Each request gets 3 total attempts (initial + 2 retries):      │
│                                                                  │
│  Attempt 1 (Server 1):                                          │
│    ┌──────────────────────────────────┐                         │
│    │ Try request to de1.api...        │                         │
│    │   ├─ Success? Return data        │                         │
│    │   └─ Fail? ─┐                    │                         │
│    └─────────────┼────────────────────┘                         │
│                  ▼                                               │
│  Attempt 2 (Server 2):                                          │
│    ┌──────────────────────────────────┐                         │
│    │ cycleServer() -> nl1.api...      │                         │
│    │ Try request to nl1.api...        │                         │
│    │   ├─ Success? Return data        │                         │
│    │   └─ Fail? ─┐                    │                         │
│    └─────────────┼────────────────────┘                         │
│                  ▼                                               │
│  Attempt 3 (Server 3):                                          │
│    ┌──────────────────────────────────┐                         │
│    │ cycleServer() -> at1.api...      │                         │
│    │ Try request to at1.api...        │                         │
│    │   ├─ Success? Return data        │                         │
│    │   └─ Fail? Return Error          │                         │
│    └──────────────────────────────────┘                         │
│                                                                  │
│  Total attempts: 3                                              │
│  Total servers tried: Up to 3 different servers                 │
└─────────────────────────────────────────────────────────────────┘


LEVEL 3: TIMEOUT HANDLING
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

┌─────────────────────────────────────────────────────────────────┐
│  Different timeouts based on connection type:                   │
│                                                                  │
│  DIRECT CONNECTION:                                             │
│  ┌─────────────────────────────────────┐                        │
│  │ connectTimeout: 30 seconds          │                        │
│  │ readTimeout:    30 seconds          │                        │
│  │                                      │                        │
│  │ ┌─────────────────────────────┐     │                        │
│  │ │  Request                    │     │                        │
│  │ │    ↓                        │     │                        │
│  │ │  [30s timer starts]         │     │                        │
│  │ │    ↓                        │     │                        │
│  │ │  Response / Timeout         │     │                        │
│  │ └─────────────────────────────┘     │                        │
│  └─────────────────────────────────────┘                        │
│                                                                  │
│  VIA TOR PROXY:                                                 │
│  ┌─────────────────────────────────────┐                        │
│  │ connectTimeout: 60 seconds          │                        │
│  │ readTimeout:    60 seconds          │                        │
│  │                                      │                        │
│  │ ┌─────────────────────────────────┐ │                        │
│  │ │  Request                        │ │                        │
│  │ │    ↓                            │ │                        │
│  │ │  SOCKS proxy (127.0.0.1:9050)  │ │                        │
│  │ │    ↓                            │ │                        │
│  │ │  Tor circuit building...        │ │                        │
│  │ │    ↓                            │ │                        │
│  │ │  [60s timer - allows for slow   │ │                        │
│  │ │   circuit creation]             │ │                        │
│  │ │    ↓                            │ │                        │
│  │ │  Response / Timeout             │ │                        │
│  │ └─────────────────────────────────┘ │                        │
│  └─────────────────────────────────────┘                        │
└─────────────────────────────────────────────────────────────────┘


LEVEL 4: TOR CONNECTION GUARD
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

┌─────────────────────────────────────────────────────────────────┐
│  Force Tor Protection:                                           │
│                                                                  │
│  IF "Force Tor" enabled in settings:                            │
│  ┌────────────────────────────────────────────┐                 │
│  │  1. Check Tor connection status            │                 │
│  │     ├─ TorManager.isConnected()            │                 │
│  │     │                                       │                 │
│  │     ├─ IF NOT connected:                   │                 │
│  │     │  ┌────────────────────────────────┐  │                 │
│  │     │  │ Return Error(                   │  │                 │
│  │     │  │   "Force Tor is enabled but "  │  │                 │
│  │     │  │   "Tor is not connected."      │  │                 │
│  │     │  │   "Please start Orbot first."  │  │                 │
│  │     │  │ )                               │  │                 │
│  │     │  └────────────────────────────────┘  │                 │
│  │     │  🚫 BLOCKS REQUEST - NO API CALL    │                 │
│  │     │                                       │                 │
│  │     └─ ELSE: proceed with proxy            │                 │
│  │                                             │                 │
│  │  2. Socket connectivity test (every 30s):  │                 │
│  │     try {                                   │                 │
│  │       Socket().use {                        │                 │
│  │         it.connect(                         │                 │
│  │           InetSocketAddress(               │                 │
│  │             "127.0.0.1", 9050              │                 │
│  │           ),                                │                 │
│  │           timeout = 5000                   │                 │
│  │         )                                   │                 │
│  │       }                                     │                 │
│  │       // Connected ✓                        │                 │
│  │     } catch {                               │                 │
│  │       // Disconnected - update status       │                 │
│  │     }                                       │                 │
│  │                                             │                 │
│  │  3. Listen for Orbot broadcasts:            │                 │
│  │     ┌───────────────────────────────┐      │                 │
│  │     │ Broadcast: STATUS_OFF         │      │                 │
│  │     │   ↓                           │      │                 │
│  │     │ TorManager.isConnected = false│      │                 │
│  │     │   ↓                           │      │                 │
│  │     │ UI shows warning banner       │      │                 │
│  │     └───────────────────────────────┘      │                 │
│  └────────────────────────────────────────────┘                 │
└─────────────────────────────────────────────────────────────────┘


LEVEL 5: GRACEFUL DEGRADATION
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

┌─────────────────────────────────────────────────────────────────┐
│  Even after all retries fail:                                    │
│                                                                  │
│  ┌────────────────────────────────────────────┐                 │
│  │ API call failed completely                 │                 │
│  │   ↓                                         │                 │
│  │ return RadioBrowserResult.Error(           │                 │
│  │   message = "Network error: Unable to..."  │                 │
│  │   exception = lastException                │                 │
│  │ )                                           │                 │
│  └─────────────────┬───────────────────────────┘                 │
│                    │                                             │
│                    ▼                                             │
│  ┌────────────────────────────────────────────┐                 │
│  │ ViewModel receives error                   │                 │
│  │   ↓                                         │                 │
│  │ _errorMessage.value = result.message       │                 │
│  │ _isLoading.value = false                   │                 │
│  └─────────────────┬───────────────────────────┘                 │
│                    │                                             │
│                    ▼                                             │
│  ┌────────────────────────────────────────────┐                 │
│  │ UI displays friendly error                 │                 │
│  │                                             │                 │
│  │ ┌────────────────────────────────────┐     │                 │
│  │ │ ⚠️  Network Error                  │     │                 │
│  │ │                                    │     │                 │
│  │ │ Unable to connect to RadioBrowser  │     │                 │
│  │ │ API. Please check your connection. │     │                 │
│  │ │                                    │     │                 │
│  │ │ [Retry] [Go to Library]           │     │                 │
│  │ └────────────────────────────────────┘     │                 │
│  └────────────────────────────────────────────┘                 │
│                                                                  │
│  User can still:                                                │
│  ✓ Access locally saved stations                               │
│  ✓ Play from library                                            │
│  ✓ View browse history                                          │
│  ✓ Use cached data (if available)                               │
└─────────────────────────────────────────────────────────────────┘
```

---

## 6. Proxy Routing

### Force Tor Request Flow

```
┌──────────────────────────────────────────────────────────────────┐
│               NORMAL REQUEST (No Tor)                             │
└──────────────────────────────────────────────────────────────────┘

   App                 OkHttp              DNS              API Server
    │                    │                  │                    │
    │  newCall(request)  │                  │                    │
    ├───────────────────▶│                  │                    │
    │                    │ DNS lookup       │                    │
    │                    ├─────────────────▶│                    │
    │                    │ IP: 5.9.18.124   │                    │
    │                    │◀─────────────────┤                    │
    │                    │                  │                    │
    │                    │ TCP connect: 5.9.18.124:443           │
    │                    ├──────────────────────────────────────▶│
    │                    │                  │ TLS Handshake      │
    │                    │◀──────────────────────────────────────┤
    │                    │                  │                    │
    │                    │ GET /json/stations/search...          │
    │                    ├──────────────────────────────────────▶│
    │                    │                  │                    │
    │                    │                  │ JSON Response      │
    │                    │◀──────────────────────────────────────┤
    │  Response          │                  │                    │
    │◀───────────────────┤                  │                    │
    │                    │                  │                    │

Exposed to ISP/Network:
  • Target hostname (de1.api.radio-browser.info)
  • Target IP address
  • Request timing
  • Response size

────────────────────────────────────────────────────────────────────

┌──────────────────────────────────────────────────────────────────┐
│               FORCE TOR REQUEST (Privacy Mode)                    │
└──────────────────────────────────────────────────────────────────┘

   App          OkHttp      Orbot (Tor)    Tor Network     API Server
    │             │              │               │              │
    │ Check Tor   │              │               │              │
    ├────────────▶│              │               │              │
    │ Connected✓  │              │               │              │
    │             │              │               │              │
    │ proxy:      │              │               │              │
    │ SOCKS5      │              │               │              │
    │ 127.0.0.1   │              │               │              │
    │ :9050       │              │               │              │
    ├────────────▶│              │               │              │
    │             │ SOCKS5       │               │              │
    │             │ CONNECT      │               │              │
    │             ├─────────────▶│               │              │
    │             │              │ Build circuit │              │
    │             │              │ (3 hops)      │              │
    │             │              ├──────────────▶│              │
    │             │              │◀──────────────┤              │
    │             │              │ Circuit ready │              │
    │             │              │               │              │
    │             │              │ Encrypted: GET /json/...     │
    │             │              ├─────────────────────────────▶│
    │             │              │               │              │
    │             │              │               │ Response     │
    │             │              │◀─────────────────────────────┤
    │             │ Decrypted    │               │              │
    │             │◀─────────────┤               │              │
    │ Response    │              │               │              │
    │◀────────────┤              │               │              │
    │             │              │               │              │

Exposed to ISP/Network:
  • Connection to localhost:9050 only
  • Tor traffic (encrypted, no content visible)

API Server sees:
  • Tor exit node IP (not your real IP)
  • No correlation to your identity

Tor Circuit Example:
  Your Device → Guard Node (Germany)
              → Middle Node (France)
              → Exit Node (Netherlands)
              → de1.api.radio-browser.info

────────────────────────────────────────────────────────────────────
```

### I2P Proxy (Per-Station)

```
┌──────────────────────────────────────────────────────────────────┐
│               I2P STATION PLAYBACK                                │
└──────────────────────────────────────────────────────────────────┘

  Station Configuration:
  ┌────────────────────────────────────────┐
  │ RadioStation(                          │
  │   name = "I2P Radio",                  │
  │   streamUrl = "http://abc123.i2p/...", │
  │   useProxy = true,                     │
  │   proxyType = "I2P",                   │
  │   proxyHost = "127.0.0.1",             │
  │   proxyPort = 4444                     │
  │ )                                       │
  └────────────────────────────────────────┘

  Playback Flow:

  RadioService       MediaPlayer      I2P Router      I2P Network
      │                  │                │                │
      │ setDataSource(  │                │                │
      │  url, proxy)     │                │                │
      ├─────────────────▶│                │                │
      │                  │ HTTP CONNECT   │                │
      │                  │ 127.0.0.1:4444 │                │
      │                  ├───────────────▶│                │
      │                  │                │ Tunnel lookup  │
      │                  │                │ for abc123.i2p │
      │                  │                ├───────────────▶│
      │                  │                │ Tunnel found   │
      │                  │                │◀───────────────┤
      │                  │ Proxy ready    │                │
      │                  │◀───────────────┤                │
      │                  │                │                │
      │                  │ GET stream     │                │
      │                  ├───────────────────────────────▶ │
      │                  │                │                │
      │                  │ Audio stream (encrypted tunnel) │
      │                  │◀───────────────────────────────┤
      │ playWhenReady    │                │                │
      │◀─────────────────┤                │                │
      │                  │                │                │
```

---

## 7. Database Integration

### How APIs and Database Work Together

```
┌──────────────────────────────────────────────────────────────────┐
│                  BROWSE TAB (API + Database)                      │
└──────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ Step 1: Fetch from API                                          │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │ val result = radioBrowserClient.getTopVoted(limit=50)      │ │
│ │                                                             │ │
│ │ // Returns 50 RadioBrowserStation objects                  │ │
│ │ [Station1, Station2, Station3, ... Station50]              │ │
│ └─────────────────────────────────────────────────────────────┘ │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 2: Extract UUIDs                                           │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │ val uuids = stations.map { it.stationuuid }                │ │
│ │ // ["uuid-1", "uuid-2", "uuid-3", ... "uuid-50"]           │ │
│ └─────────────────────────────────────────────────────────────┘ │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 3: Batch Query Database (Single Query!)                   │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │ @Query("""                                                  │ │
│ │   SELECT radioBrowserUuid as uuid,                          │ │
│ │          (source != 'RADIOBROWSER_CACHE') as isSaved,      │ │
│ │          isLiked                                            │ │
│ │   FROM radio_stations                                       │ │
│ │   WHERE radioBrowserUuid IN (:uuids)                        │ │
│ │ """)                                                        │ │
│ │ suspend fun getStationInfoByUuids(uuids: List<String>)     │ │
│ │   : List<StationInfo>                                       │ │
│ └─────────────────────────────────────────────────────────────┘ │
│                                                                 │
│ Result:                                                         │
│ ┌──────────┬──────────┬──────────┐                             │
│ │   UUID   │ isSaved  │ isLiked  │                             │
│ ├──────────┼──────────┼──────────┤                             │
│ │ uuid-5   │  true    │  false   │  ← In library, not liked    │
│ │ uuid-12  │  true    │  true    │  ← In library, liked        │
│ │ uuid-37  │  false   │  true    │  ← Cached, liked only       │
│ └──────────┴──────────┴──────────┘                             │
│ // 47 other stations not in database (not saved)               │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 4: Update ViewModel State                                  │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │ val savedUuids = stationInfoMap                             │ │
│ │   .filter { it.value.isSaved }                              │ │
│ │   .keys                                                      │ │
│ │ // Set<String>: ["uuid-5", "uuid-12"]                       │ │
│ │                                                              │ │
│ │ val likedUuids = stationInfoMap                             │ │
│ │   .filter { it.value.isLiked }                              │ │
│ │   .keys                                                      │ │
│ │ // Set<String>: ["uuid-12", "uuid-37"]                      │ │
│ │                                                              │ │
│ │ _savedStationUuids.value = savedUuids                       │ │
│ │ _likedStationUuids.value = likedUuids                       │ │
│ └─────────────────────────────────────────────────────────────┘ │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 5: UI Displays with Icons                                  │
│                                                                 │
│ ┌─────────────────────────────────────────────┐                 │
│ │ 🎵 Jazz FM                       ♥️ 💾      │ uuid-12 (saved) │
│ │ ─────────────────────────────────────────   │                 │
│ │ 🎵 Classical Radio                    💾    │ uuid-5  (saved) │
│ │ ─────────────────────────────────────────   │                 │
│ │ 🎵 Rock Station                  ♥️         │ uuid-37 (cached)│
│ │ ─────────────────────────────────────────   │                 │
│ │ 🎵 Pop Hits                                 │ (not saved)     │
│ │ ─────────────────────────────────────────   │                 │
│ │ 🎵 Country Music                            │ (not saved)     │
│ └─────────────────────────────────────────────┘                 │
│                                                                 │
│ Adapter checks:                                                 │
│   if (station.uuid in savedUuids) show💾                       │
│   if (station.uuid in likedUuids) show♥️                       │
└─────────────────────────────────────────────────────────────────┘

────────────────────────────────────────────────────────────────────

┌──────────────────────────────────────────────────────────────────┐
│                  LIBRARY TAB (Database Only)                      │
└──────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ Step 1: Query Local Database                                    │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │ @Query("""                                                  │ │
│ │   SELECT * FROM radio_stations                              │ │
│ │   WHERE source != 'RADIOBROWSER_CACHE'                      │ │
│ │   ORDER BY lastPlayedAt DESC                                │ │
│ │ """)                                                        │ │
│ │ fun getAllStationsSortedByRecentlyPlayed()                  │ │
│ │   : LiveData<List<RadioStation>>                            │ │
│ └─────────────────────────────────────────────────────────────┘ │
│                                                                 │
│ // NO API CALL - instant response                              │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 2: LiveData Auto-Updates UI                                │
│                                                                 │
│ ┌──────────────────────────────────────────────────┐            │
│ │ LibraryFragment:                                 │            │
│ │                                                  │            │
│ │ viewModel.stations.observe(this) { stations ->   │            │
│ │   adapter.submitList(stations)                   │            │
│ │ }                                                │            │
│ │                                                  │            │
│ │ // Automatically updates when:                   │            │
│ │ // - User saves new station                      │            │
│ │ // - User deletes station                        │            │
│ │ // - User likes/unlikes                          │            │
│ │ // - Station is played (updates lastPlayedAt)    │            │
│ └──────────────────────────────────────────────────┘            │
└─────────────────────────────────────────────────────────────────┘

────────────────────────────────────────────────────────────────────

┌──────────────────────────────────────────────────────────────────┐
│                  SAVE ACTION (API → Database)                     │
└──────────────────────────────────────────────────────────────────┘

User taps "Save" on Browse result
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 1: Convert API Model to Database Model                     │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │ fun RadioBrowserStation.toRadioStation(): RadioStation {    │ │
│ │   return RadioStation(                                      │ │
│ │     id = 0, // Auto-generated                               │ │
│ │     name = this.name,                                       │ │
│ │     streamUrl = this.urlResolved.ifEmpty { this.url },      │ │
│ │     genre = this.tags,                                      │ │
│ │     country = this.country,                                 │ │
│ │     countryCode = this.countrycode,                         │ │
│ │     bitrate = this.bitrate,                                 │ │
│ │     codec = this.codec,                                     │ │
│ │     radioBrowserUuid = this.stationuuid, // LINK!           │ │
│ │     source = "RADIOBROWSER", // Tag source                  │ │
│ │     isLiked = false,                                        │ │
│ │     addedTimestamp = System.currentTimeMillis(),            │ │
│ │     // ... other fields                                      │ │
│ │   )                                                          │ │
│ │ }                                                            │ │
│ └─────────────────────────────────────────────────────────────┘ │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 2: Check for Duplicates                                    │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │ val existingCount = dao.countByRadioBrowserUuid(            │ │
│ │   station.stationuuid                                       │ │
│ │ )                                                            │ │
│ │                                                              │ │
│ │ if (existingCount > 0) {                                    │ │
│ │   // Already saved                                          │ │
│ │   showToast("Station already in library")                   │ │
│ │   return                                                     │ │
│ │ }                                                            │ │
│ └─────────────────────────────────────────────────────────────┘ │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 3: Insert into Database                                    │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │ @Insert(onConflict = OnConflictStrategy.REPLACE)            │ │
│ │ suspend fun insertStation(station: RadioStation): Long      │ │
│ │                                                              │ │
│ │ val newId = dao.insertStation(                              │ │
│ │   station.toRadioStation()                                  │ │
│ │ )                                                            │ │
│ └─────────────────────────────────────────────────────────────┘ │
│                                                                 │
│ SQLite executes:                                                │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │ INSERT INTO radio_stations (                                │ │
│ │   name, streamUrl, genre, radioBrowserUuid,                 │ │
│ │   source, isLiked, addedTimestamp, ...                      │ │
│ │ ) VALUES (                                                   │ │
│ │   'Jazz FM', 'http://...', 'Jazz', 'uuid-123',              │ │
│ │   'RADIOBROWSER', 0, 1701234567890, ...                     │ │
│ │ )                                                            │ │
│ └─────────────────────────────────────────────────────────────┘ │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 4: LiveData Triggers UI Updates                            │
│                                                                 │
│ Database change detected                                        │
│         ▼                                                        │
│ getAllStations() LiveData emits new list                        │
│         ▼                                                        │
│ Library tab automatically refreshes                             │
│         ▼                                                        │
│ Browse tab updates saved status (💾 icon appears)               │
└─────────────────────────────────────────────────────────────────┘
```

### Caching Strategy

```
┌──────────────────────────────────────────────────────────────────┐
│                    7-DAY CACHE SYSTEM                             │
└──────────────────────────────────────────────────────────────────┘

Purpose: Store browse results temporarily for offline access

┌─────────────────────────────────────────────────────────────────┐
│ When User Browses "Top Voted":                                  │
│                                                                 │
│ 1. Fetch from API:                                              │
│    RadioBrowserClient.getTopVoted(limit=100)                    │
│         ↓                                                        │
│ 2. Display to user immediately                                  │
│         ↓                                                        │
│ 3. Cache in background:                                         │
│    ┌─────────────────────────────────────────────────────────┐  │
│    │ for (station in stations) {                             │  │
│    │   if (!isStationSaved(station.uuid)) {                  │  │
│    │     insertStation(                                       │  │
│    │       station.toRadioStation().copy(                    │  │
│    │         source = "RADIOBROWSER_CACHE", // Tag as cache  │  │
│    │         cachedAt = System.currentTimeMillis()           │  │
│    │       )                                                  │  │
│    │     )                                                    │  │
│    │   }                                                      │  │
│    │ }                                                        │  │
│    └─────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ Cleanup (runs periodically):                                    │
│                                                                 │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │ @Query("""                                                  │ │
│ │   DELETE FROM radio_stations                                │ │
│ │   WHERE source = 'RADIOBROWSER_CACHE'                       │ │
│ │     AND cachedAt < :olderThan                               │ │
│ │ """)                                                        │ │
│ │ suspend fun deleteStaleCachedStations(olderThan: Long)     │ │
│ │                                                              │ │
│ │ // Called with:                                             │ │
│ │ val sevenDaysAgo = System.currentTimeMillis() -             │ │
│ │                    (7 * 24 * 60 * 60 * 1000)                │ │
│ │ dao.deleteStaleCachedStations(sevenDaysAgo)                 │ │
│ └─────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘

Database State Example:
┌──────┬───────────────┬──────────────────────┬─────────────────┐
│  ID  │     Name      │       Source         │    CachedAt     │
├──────┼───────────────┼──────────────────────┼─────────────────┤
│  1   │ Jazz FM       │ USER                 │ NULL            │
│  2   │ Rock Radio    │ RADIOBROWSER         │ NULL            │
│  3   │ Pop Hits      │ RADIOBROWSER_CACHE   │ 1701000000000   │
│  4   │ Classical     │ RADIOBROWSER_CACHE   │ 1701000000000   │
│  5   │ Custom Stream │ USER                 │ NULL            │
└──────┴───────────────┴──────────────────────┴─────────────────┘

Library Query (excludes cache):
  WHERE source != 'RADIOBROWSER_CACHE'
  → Returns: [Jazz FM, Rock Radio, Custom Stream]

Cache Query (cache only):
  WHERE source = 'RADIOBROWSER_CACHE'
  → Returns: [Pop Hits, Classical]

After 7 days:
  DELETE WHERE cachedAt < sevenDaysAgo
  → Deletes: [Pop Hits, Classical]
```

---

## 8. Component Relationships

### Complete System Map

```
┌──────────────────────────────────────────────────────────────────┐
│                       COMPONENT DIAGRAM                           │
└──────────────────────────────────────────────────────────────────┘

┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ UI LAYER                                                         ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛

  ┌─────────────────────┐      ┌─────────────────────┐
  │ BrowseStations      │      │ LibraryFragment     │
  │ Fragment            │      │                     │
  │                     │      │ • RecyclerView      │
  │ • SearchBar         │      │ • Sort options      │
  │ • FilterChips       │      │ • Genre filter      │
  │ • RecyclerView      │      │ • Add manual        │
  │ • Category tabs     │      │ • Edit/delete       │
  └──────────┬──────────┘      └──────────┬──────────┘
             │                            │
             │ observes                   │ observes
             │                            │
             ▼                            ▼
  ┌──────────────────────────────────────────────────┐
  │ ViewModel Communication:                         │
  │                                                  │
  │  Browse ◄──────shares data──────► Radio         │
  │  ViewModel                         ViewModel     │
  │                                                  │
  │  • When station saved in Browse,                │
  │    Library auto-updates via LiveData            │
  │  • When station played, both ViewModels know    │
  └──────────────────────────────────────────────────┘

┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ VIEWMODEL LAYER                                                  ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛

  ┌─────────────────────┐      ┌─────────────────────┐
  │ BrowseViewModel     │      │ RadioViewModel      │
  │                     │      │                     │
  │ LiveData:           │      │ LiveData:           │
  │ • stations          │      │ • currentStation    │
  │ • isLoading         │      │ • isPlaying         │
  │ • errorMessage      │      │ • playbackState     │
  │ • searchQuery       │      │ • mediaProgress     │
  │ • savedStationUuids │      │                     │
  │ • likedStationUuids │      │ Communicates with:  │
  │                     │      │ • RadioService      │
  │ Uses:               │      │ • MediaController   │
  │ • Coroutines        │      │                     │
  │ • Search debounce   │      │                     │
  └──────────┬──────────┘      └──────────┬──────────┘
             │                            │
             │ uses                       │ uses
             │                            │
             ▼                            ▼

┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ REPOSITORY LAYER                                                 ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛

  ┌──────────────────────────────────┐  ┌─────────────────────┐
  │ RadioBrowserRepository           │  │ RadioRepository     │
  │                                   │  │                     │
  │ API Methods:                     │  │ Database Methods:   │
  │ • searchByName()                 │  │ • getAllStations()  │
  │ • searchStations()               │  │ • insertStation()   │
  │ • getTopVoted()                  │  │ • deleteStation()   │
  │ • getTopClicked()                │  │ • toggleLike()      │
  │ • getByCountryCode()             │  │ • getByGenre()      │
  │ • getByTag()                     │  │ • getLiked()        │
  │ • getCountries()                 │  │                     │
  │ • getTags()                      │  │                     │
  │                                   │  │                     │
  │ Database Integration:            │  │                     │
  │ • saveStation()                  │  │                     │
  │ • isStationSaved()               │  │                     │
  │ • getStationInfoByUuids()        │  │                     │
  │ • cacheStations()                │  │                     │
  │ • cleanupStaleCache()            │  │                     │
  │                                   │  │                     │
  │ Browse History:                  │  │                     │
  │ • getBrowseHistory()             │  │                     │
  │ • addToBrowseHistory()           │  │                     │
  │ • clearBrowseHistory()           │  │                     │
  └────────────┬───────┬─────────────┘  └──────────┬──────────┘
               │       │                           │
               │       └──────uses database────────┤
               │ uses                              │
               ▼                                   ▼

┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ DATA LAYER                                                       ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛

  ┌─────────────────────┐      ┌─────────────────────────────┐
  │ RadioBrowserClient  │      │ Room Database               │
  │                     │      │                             │
  │ • OkHttp3 client    │      │ Tables:                     │
  │ • JSON parsing      │      │ ┌─────────────────────────┐ │
  │ • Server failover   │      │ │ radio_stations          │ │
  │ • Retry logic       │      │ │ • id (PK)               │ │
  │ • Proxy support     │      │ │ • name                  │ │
  │ • Timeout mgmt      │      │ │ • streamUrl             │ │
  │                     │      │ │ • radioBrowserUuid      │ │
  │ Servers:            │      │ │ • source                │ │
  │ • de1.api...        │      │ │ • isLiked               │ │
  │ • nl1.api...        │      │ │ • cachedAt              │ │
  │ • at1.api...        │      │ │ • ... 20+ fields        │ │
  └──────────┬──────────┘      │ └─────────────────────────┘ │
             │                 │                             │
             │                 │ ┌─────────────────────────┐ │
             │                 │ │ browse_history          │ │
             │                 │ │ • id (PK)               │ │
             │                 │ │ • stationUuid           │ │
             │                 │ │ • stationName           │ │
             │                 │ │ • visitedAt             │ │
             │                 │ └─────────────────────────┘ │
             │                 │                             │
             │                 │ DAO:                        │
             │                 │ • RadioDao (SQL queries)    │
             │                 └─────────────────────────────┘
             │
             ▼

┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ EXTERNAL SERVICES                                                ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛

  ┌──────────────────────┐     ┌─────────────┐    ┌──────────┐
  │ RadioBrowser API     │     │ Tor Network │    │ I2P      │
  │                      │     │ (Orbot)     │    │ Router   │
  │ • 50,000+ stations   │     │             │    │          │
  │ • Multiple endpoints │     │ SOCKS5      │    │ HTTP     │
  │ • JSON responses     │     │ :9050       │    │ :4444    │
  │ • Free & open        │     │             │    │          │
  └──────────────────────┘     └─────────────┘    └──────────┘


DATA FLOW EXAMPLE: User searches "Jazz"
═══════════════════════════════════════════════════════════════════

User types "Jazz" in search box
    │
    ▼
BrowseStationsFragment.onSearchQueryChanged()
    │
    ▼
BrowseViewModel.search("Jazz")
    │
    ▼
RadioBrowserRepository.searchByName("Jazz", 50, 0)
    │
    ▼
RadioBrowserClient.executeRequest("/stations/byname/Jazz?...")
    │
    ▼
OkHttp3 → RadioBrowser API (de1.api.radio-browser.info)
    │
    ▼
JSON Response (50 stations)
    │
    ▼
Parse to List<RadioBrowserStation>
    │
    ▼
RadioBrowserResult.Success(stations)
    │
    ▼
BrowseViewModel updates:
  • _stations.value = stations
  • checkSavedStatus(stations) → queries Room DB
    │
    ▼
Room DB query: getStationInfoByUuids(uuids)
    │
    ▼
BrowseViewModel updates:
  • _savedStationUuids.value = saved UUIDs
  • _likedStationUuids.value = liked UUIDs
    │
    ▼
BrowseStationsFragment observes LiveData changes
    │
    ▼
RecyclerView adapter updates with:
  • Station list
  • Saved status (💾 icons)
  • Liked status (♥️ icons)
    │
    ▼
User sees results on screen!
```

---

## Summary

### Key Architectural Decisions

1. **Hybrid Approach**: Combines external API (discovery) with local database (library)
2. **Repository Pattern**: Clean separation between data sources and business logic
3. **Reactive UI**: LiveData ensures UI always reflects current state
4. **Batch Queries**: Avoids N+1 problem with single database queries
5. **Resilient HTTP**: Multi-server failover with automatic retries
6. **Privacy-First**: Force Tor option routes all API calls through Tor
7. **Efficient Caching**: 7-day cache for offline access, automatic cleanup
8. **Sealed Results**: Type-safe API responses (Success/Error/Loading)

### Performance Characteristics

```
┌────────────────────────────┬──────────────┬──────────────┐
│ Operation                  │ Direct       │ Via Tor      │
├────────────────────────────┼──────────────┼──────────────┤
│ Search API                 │ 150-500ms    │ 1-3.5s       │
│ Load Library               │ <10ms        │ N/A          │
│ Save Station               │ <50ms        │ N/A          │
│ Batch Status Check (50)    │ <20ms        │ N/A          │
│ Cache Cleanup              │ <100ms       │ N/A          │
└────────────────────────────┴──────────────┴──────────────┘
```

### File Reference

| Component | File Path |
|-----------|-----------|
| API Client | `app/src/main/java/com/opensource/i2pradio/data/radiobrowser/RadioBrowserClient.kt` |
| API Repository | `app/src/main/java/com/opensource/i2pradio/data/radiobrowser/RadioBrowserRepository.kt` |
| Data Models | `app/src/main/java/com/opensource/i2pradio/data/radiobrowser/RadioBrowserStation.kt` |
| Local Repository | `app/src/main/java/com/opensource/i2pradio/data/RadioRepository.kt` |
| Database | `app/src/main/java/com/opensource/i2pradio/data/RadioDatabase.kt` |
| DAO | `app/src/main/java/com/opensource/i2pradio/data/RadioDao.kt` |
| Browse ViewModel | `app/src/main/java/com/opensource/i2pradio/ui/browse/BrowseViewModel.kt` |
| Browse Fragment | `app/src/main/java/com/opensource/i2pradio/ui/browse/BrowseStationsFragment.kt` |

---

**End of Visual Guide**
