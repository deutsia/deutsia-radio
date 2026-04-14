# Verifying RTSP playback and recording

This guide walks through how to confirm the app's RTSP code path works end-to-end
on a real device, using either public test streams or a throwaway local RTSP
server.

## What the app does with RTSP

- Playback: handled by Media3's `RtspMediaSource`
  (`app/src/main/java/com/opensource/i2pradio/RadioService.kt:2441`).
- URLs are detected by prefix: `streamUrl.startsWith("rtsp://", ignoreCase = true)`
  (`RadioService.kt:1660`).
- RTSP is intentionally blocked when any proxy (Tor, I2P, or custom) is enabled
  for the station, because RTP does not traverse HTTP/SOCKS proxies cleanly
  (`RadioService.kt:2068-2082`). Test streams below therefore set
  `"useProxy": false` / `"proxyType": "NONE"`.
- Recording for RTSP is intentionally **not supported** and returns the
  `recording_error_rtsp_unsupported` toast
  (`RadioService.kt:577-580`). That means "verifying recording" for RTSP means
  verifying that the record button surfaces that error cleanly — there is no
  file output by design. If you want to record RTSP content, transcode it to
  HLS/ICY first (e.g. with `ffmpeg` or `mediamtx`) and point the app at the
  HTTP URL; HLS/progressive recording is fully implemented.

## Option A: Import the candidate list and try each station

1. Copy `docs/rtsp-test-stations.json` to your device (Downloads is fine).
2. In the app: **Settings → Import stations** → pick the JSON file.
3. Play each new station in turn. Expect:
   - Some will connect and play audio from the video file.
   - Some will fail to connect — public RTSP servers come and go. Delete the
     ones that don't work.
4. On any station that plays, tap **Record**. You should see the
   "Recording isn't supported for RTSP streams" toast. That is the correct,
   by-design behavior.

### Candidate URLs at a glance

| # | URL | Source |
|---|-----|--------|
| 1 | `rtsp://rtspstream.com:554/pattern` | rtsp.stream (test-pattern loop) |
| 2 | `rtsp://rtspstream.com:554/movie` | rtsp.stream (movie loop) |
| 3 | `rtsp://wowzaec2demo.streamlock.net/vod/mp4:BigBuckBunny_115k.mov` | Wowza demo server |
| 4 | `rtsp://mpv.cdn3.bigCDN.com:554/bigCDN/mp4:bigbuckbunnyiphone_400.mp4` | bigCDN public VOD |
| 5 | `rtsp://184.72.239.149/vod/mp4:BigBuckBunny_175k.mov` | Historic public demo |
| 6 | `rtsp://freja.hiof.no:1935/rtplive/definst/hessdalen03.stream` | Hiof live camera |

These are the same streams that tools like VLC and ExoPlayer sample code have
historically used for RTSP smoke tests. None are under our control, so please
don't take their availability as a guarantee — at least one of the six usually
responds.

> Note: These candidates could not be pre-probed from CI because port 554 is
> blocked in the build environment. Verification has to happen on a device or
> developer machine with a normal internet connection.

## Option B: Run a private RTSP server (always works)

If every public URL is down — or you want a deterministic test — stand up your
own RTSP server on the same network as your phone. Two easy options:

### B1. `mediamtx` (formerly `rtsp-simple-server`) + ffmpeg

```bash
# On your dev machine, in one terminal:
docker run --rm -it --network host bluenviron/mediamtx:latest

# In another terminal, push a looping test tone into it:
ffmpeg -re -stream_loop -1 -f lavfi -i "sine=frequency=440:sample_rate=44100" \
       -c:a aac -b:a 128k -f rtsp rtsp://127.0.0.1:8554/tone
```

Then on the phone, add a custom station with:

```
rtsp://<your-dev-machine-LAN-ip>:8554/tone
```

Playback should produce a steady 440 Hz sine. Recording should show the
"not supported" toast as in Option A.

### B2. ffmpeg-only (no server binary)

If you don't want Docker, ffmpeg can serve a single RTSP stream on its own:

```bash
ffmpeg -re -stream_loop -1 -i /path/to/any.mp3 \
       -c:a aac -b:a 128k -rtsp_flags listen \
       -f rtsp rtsp://0.0.0.0:8554/audio
```

Phone URL: `rtsp://<your-dev-machine-LAN-ip>:8554/audio`.

## Manual entry (without importing JSON)

You can also add a single test station by hand:

1. App home → **Add station** (the + button).
2. Name: anything, e.g. `RTSP smoke test`.
3. Stream URL: one of the rtsp:// URLs above.
4. Proxy Type: **None** (required — RTSP over a proxy is blocked by design).
5. Save, then tap the station to play.

## What success looks like

- Playback: audio (or audio track of a video) comes out of the speaker within a
  few seconds.
- Media session: the notification shows the station name and a working
  pause/play control.
- Record button: produces the "Recording isn't supported for RTSP streams"
  toast and does not create an empty file in Downloads.
- Proxy: enabling any proxy on an RTSP station should surface the
  "unsupported codec (proxy mode)" error and refuse to play.

If any of the above doesn't match, file a bug with the stream URL, Android
version, and the relevant `adb logcat RadioService:D` output.
