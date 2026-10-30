package com.opensource.i2pradio.util

/**
 * User-Agent sent to audio stream servers: playlist resolution, playback,
 * recording, and Android Auto. Kept in one place so every code path that
 * talks to a stream host identifies itself the same way.
 *
 * SomaFM's ice*.somafm.com servers close the TLS connection with no HTTP
 * response when the User-Agent is "DeutsiaRadio/1.0" (verified with curl on
 * ice1/ice4/ice5/ice6: only the User-Agent differed, and "Deutsia/1.0",
 * "okhttp/4.12.0" and an arbitrary unknown name all streamed normally).
 *
 * Keep this honest and app-identifying. Do NOT replace it with another
 * client's User-Agent to work around a block.
 */
object StreamUserAgent {
    const val VALUE = "Deutsia/1.0"
}
