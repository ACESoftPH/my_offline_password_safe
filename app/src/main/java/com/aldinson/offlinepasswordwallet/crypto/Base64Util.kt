package com.aldinson.offlinepasswordwallet.crypto

import java.util.Base64

/**
 * Base64 helpers. Uses [java.util.Base64] (available since API 26, which is this
 * app's minSdk) so the same code path runs in local JVM unit tests without
 * Robolectric.
 */
object Base64Util {
    private val encoder: Base64.Encoder = Base64.getEncoder()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    fun decode(text: String): ByteArray = decoder.decode(text)
}
