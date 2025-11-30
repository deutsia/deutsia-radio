package com.opensource.i2pradio.utils

import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest

/**
 * Utility class for HTTP Digest Authentication (RFC 2617)
 * Handles the challenge-response mechanism for proxy authentication
 */
object DigestAuthenticator {

    /**
     * Creates a Digest authentication header from a 407 Proxy Authentication Required response
     *
     * @param response The 407 response containing the WWW-Authenticate challenge
     * @param username The proxy username
     * @param password The proxy password
     * @return A new request with the Proxy-Authorization header, or null if digest parsing fails
     */
    fun authenticate(response: Response, username: String, password: String): Request? {
        // Get the Proxy-Authenticate header
        val challenge = response.header("Proxy-Authenticate")
            ?: response.header("WWW-Authenticate")
            ?: return null

        // Only handle Digest challenges
        if (!challenge.startsWith("Digest", ignoreCase = true)) {
            return null
        }

        // Parse the challenge parameters
        val params = parseDigestChallenge(challenge)
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val qop = params["qop"]
        val opaque = params["opaque"]
        val algorithm = params["algorithm"] ?: "MD5"

        // Extract request details
        val method = response.request.method
        val uri = response.request.url.encodedPath

        // Generate client nonce for qop modes
        val cnonce = if (qop != null) {
            generateNonce()
        } else {
            null
        }

        // Nonce count (always 1 for stateless authentication)
        val nc = "00000001"

        // Calculate response hash
        val responseHash = calculateResponse(
            username = username,
            password = password,
            realm = realm,
            nonce = nonce,
            method = method,
            uri = uri,
            qop = qop,
            cnonce = cnonce,
            nc = nc,
            algorithm = algorithm
        ) ?: return null

        // Build the Authorization header
        val authHeader = buildAuthorizationHeader(
            username = username,
            realm = realm,
            nonce = nonce,
            uri = uri,
            response = responseHash,
            qop = qop,
            nc = nc,
            cnonce = cnonce,
            opaque = opaque,
            algorithm = algorithm
        )

        // Return the request with the authorization header
        return response.request.newBuilder()
            .header("Proxy-Authorization", authHeader)
            .build()
    }

    /**
     * Parses the Digest challenge string into a map of parameters
     */
    private fun parseDigestChallenge(challenge: String): Map<String, String> {
        val params = mutableMapOf<String, String>()

        // Remove "Digest " prefix
        val digestParams = challenge.substring(challenge.indexOf(' ') + 1)

        // Parse key="value" pairs
        val regex = """(\w+)="?([^",]+)"?""".toRegex()
        regex.findAll(digestParams).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            params[key] = value
        }

        return params
    }

    /**
     * Calculates the response hash according to RFC 2617
     */
    private fun calculateResponse(
        username: String,
        password: String,
        realm: String,
        nonce: String,
        method: String,
        uri: String,
        qop: String?,
        cnonce: String?,
        nc: String,
        algorithm: String
    ): String? {
        try {
            val md = MessageDigest.getInstance(algorithm.replace("-sess", ""))

            // A1 = username:realm:password
            var a1 = "$username:$realm:$password"
            if (algorithm.endsWith("-sess", ignoreCase = true)) {
                // A1 = MD5(username:realm:password):nonce:cnonce
                val ha1Base = md5Hash(a1, md)
                a1 = "$ha1Base:$nonce:${cnonce ?: ""}"
            }
            val ha1 = md5Hash(a1, md)

            // A2 = method:uri
            val a2 = "$method:$uri"
            val ha2 = md5Hash(a2, md)

            // Response calculation depends on qop
            val response = when (qop) {
                "auth", "auth-int" -> {
                    // response = MD5(HA1:nonce:nc:cnonce:qop:HA2)
                    md5Hash("$ha1:$nonce:$nc:$cnonce:$qop:$ha2", md)
                }
                else -> {
                    // Legacy mode: response = MD5(HA1:nonce:HA2)
                    md5Hash("$ha1:$nonce:$ha2", md)
                }
            }

            return response
        } catch (e: Exception) {
            android.util.Log.e("DigestAuthenticator", "Error calculating digest response", e)
            return null
        }
    }

    /**
     * Computes MD5 hash of a string
     */
    private fun md5Hash(data: String, md: MessageDigest): String {
        md.reset()
        val bytes = md.digest(data.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generates a random client nonce
     */
    private fun generateNonce(): String {
        val timestamp = System.currentTimeMillis()
        val random = (0..999999).random()
        return md5Hash("$timestamp:$random", MessageDigest.getInstance("MD5"))
    }

    /**
     * Builds the Authorization header string
     */
    private fun buildAuthorizationHeader(
        username: String,
        realm: String,
        nonce: String,
        uri: String,
        response: String,
        qop: String?,
        nc: String,
        cnonce: String?,
        opaque: String?,
        algorithm: String
    ): String {
        val parts = mutableListOf<String>()

        parts.add("Digest username=\"$username\"")
        parts.add("realm=\"$realm\"")
        parts.add("nonce=\"$nonce\"")
        parts.add("uri=\"$uri\"")
        parts.add("response=\"$response\"")

        if (qop != null) {
            parts.add("qop=$qop")
            parts.add("nc=$nc")
            if (cnonce != null) {
                parts.add("cnonce=\"$cnonce\"")
            }
        }

        if (opaque != null) {
            parts.add("opaque=\"$opaque\"")
        }

        if (algorithm != "MD5") {
            parts.add("algorithm=$algorithm")
        }

        return parts.joinToString(", ")
    }
}
