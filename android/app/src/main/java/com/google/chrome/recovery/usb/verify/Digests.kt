package com.google.chrome.recovery.usb.verify

import java.security.MessageDigest

/**
 * Small, pure-Kotlin digest helpers for flash verification. No Android or USB
 * types here on purpose: everything in this file is exercised by plain JVM
 * unit tests, while UsbFlasher supplies the bytes.
 */

/**
 * Incrementally computes a digest over a stream of chunks and renders it as
 * lowercase hex — the format the ChromeOS recovery manifest uses for its
 * `sha1`/`md5` fields.
 */
class IncrementalDigest(algorithm: String = "SHA-1") {

    private val digest = MessageDigest.getInstance(algorithm)

    fun update(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
        digest.update(buffer, offset, length)
    }

    /** Finishes the computation. The instance must not be updated afterwards. */
    fun hexDigest(): String = digest.digest().joinToString("") { "%02x".format(it) }

    companion object {
        /**
         * Manifest checksums are lowercase hex, but nothing guarantees casing;
         * compare case-insensitively and reject blank expectations outright.
         */
        fun matches(expectedHex: String?, actualHex: String): Boolean =
            !expectedHex.isNullOrBlank() && expectedHex.equals(actualHex, ignoreCase = true)
    }
}

/**
 * Digests exactly [totalBytes] bytes from the chunks offered to it, ignoring
 * any excess. The USB read-back path works in whole 512-byte sectors, so the
 * final chunk usually carries padding past the true image length — padding
 * that was never part of the source data and must not enter the digest.
 */
class BoundedDigest(private val totalBytes: Long, algorithm: String = "SHA-1") {

    private val digest = IncrementalDigest(algorithm)

    /** Bytes digested so far; stops growing once [totalBytes] is reached. */
    var bytesDigested: Long = 0
        private set

    val isComplete: Boolean
        get() = bytesDigested >= totalBytes

    /**
     * Offers a chunk. Only the portion that fits under the [totalBytes] bound
     * is digested; the rest is ignored. Returns the number of bytes consumed.
     */
    fun offer(buffer: ByteArray, length: Int = buffer.size): Int {
        val remaining = totalBytes - bytesDigested
        if (remaining <= 0) return 0
        val toDigest = minOf(remaining, length.toLong()).toInt()
        digest.update(buffer, 0, toDigest)
        bytesDigested += toDigest
        return toDigest
    }

    fun hexDigest(): String = digest.hexDigest()
}
