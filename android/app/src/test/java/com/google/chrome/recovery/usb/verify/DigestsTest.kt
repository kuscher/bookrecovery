package com.google.chrome.recovery.usb.verify

import java.security.MessageDigest
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalDigestTest {

    @Test
    fun `sha1 of known vector matches`() {
        val digest = IncrementalDigest("SHA-1")
        digest.update("abc".toByteArray())
        // FIPS 180 test vector.
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", digest.hexDigest())
    }

    @Test
    fun `md5 of known vector matches`() {
        val digest = IncrementalDigest("MD5")
        digest.update("abc".toByteArray())
        // RFC 1321 test vector.
        assertEquals("900150983cd24fb0d6963f7d28e17f72", digest.hexDigest())
    }

    @Test
    fun `chunked updates equal a single update`() {
        val data = Random(42).nextBytes(1_000_003) // deliberately not sector-aligned
        val whole = IncrementalDigest().apply { update(data) }

        val chunked = IncrementalDigest()
        var offset = 0
        val chunkSizes = intArrayOf(1, 511, 512, 513, 65536)
        var i = 0
        while (offset < data.size) {
            val len = minOf(chunkSizes[i % chunkSizes.size], data.size - offset)
            chunked.update(data, offset, len)
            offset += len
            i++
        }

        assertEquals(whole.hexDigest(), chunked.hexDigest())
    }

    @Test
    fun `hex output is lowercase and zero padded`() {
        // SHA-1 of the empty input starts with "da39a3ee" and contains bytes < 0x10.
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", IncrementalDigest().hexDigest())
    }

    @Test
    fun `matches is case insensitive and rejects blank or null expectations`() {
        assertTrue(IncrementalDigest.matches("ABCDEF01", "abcdef01"))
        assertTrue(IncrementalDigest.matches("abcdef01", "abcdef01"))
        assertFalse(IncrementalDigest.matches("abcdef01", "abcdef02"))
        assertFalse(IncrementalDigest.matches(null, "abcdef01"))
        assertFalse(IncrementalDigest.matches("", "abcdef01"))
        assertFalse(IncrementalDigest.matches("   ", "abcdef01"))
    }
}

class BoundedDigestTest {

    private fun sha1(data: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(data).joinToString("") { "%02x".format(it) }

    @Test
    fun `ignores sector padding past the bound`() {
        val payload = "hello, recovery image".toByteArray()
        val padded = payload.copyOf(512) // zero-padded to a full sector, like the final write

        val bounded = BoundedDigest(totalBytes = payload.size.toLong())
        bounded.offer(padded)

        assertEquals(sha1(payload), bounded.hexDigest())
        assertTrue(bounded.isComplete)
    }

    @Test
    fun `digests across chunks and stops exactly at the bound`() {
        val data = Random(7).nextBytes(200_000)
        val totalBytes = 130_999L // lands mid-chunk, not sector aligned

        val bounded = BoundedDigest(totalBytes)
        var offset = 0
        val chunk = ByteArray(65536)
        while (offset < data.size) {
            val len = minOf(chunk.size, data.size - offset)
            System.arraycopy(data, offset, chunk, 0, len)
            bounded.offer(chunk, len)
            offset += len
        }

        assertEquals(totalBytes, bounded.bytesDigested)
        assertEquals(sha1(data.copyOf(totalBytes.toInt())), bounded.hexDigest())
    }

    @Test
    fun `offers after completion consume nothing`() {
        val bounded = BoundedDigest(totalBytes = 4)
        assertEquals(4, bounded.offer(byteArrayOf(1, 2, 3, 4, 5)))
        assertEquals(0, bounded.offer(byteArrayOf(6, 7)))
        assertEquals(4, bounded.bytesDigested)
    }

    @Test
    fun `detects a single flipped byte`() {
        val data = Random(13).nextBytes(4096)
        val expected = BoundedDigest(data.size.toLong()).apply { offer(data) }.hexDigest()

        data[2048] = (data[2048] + 1).toByte()
        val corrupted = BoundedDigest(data.size.toLong()).apply { offer(data) }.hexDigest()

        assertFalse(IncrementalDigest.matches(expected, corrupted))
    }
}
