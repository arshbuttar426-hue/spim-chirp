package com.prima.spimchirp.logic

import com.prima.spimchirp.not_as.MicrochirpAtoms
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The continuous receiver is only useful if it survives the chunk boundaries a
 * real microphone produces. These tests feed the encoder's own output in
 * awkward slice sizes (including sizes that split the preamble) and assert that
 * the payload still comes back exactly, and that the next message in the same
 * stream is found without resetting the decoder.
 */
class StreamingChirpDecoderTest {

  private fun feedAll(
    decoder: StreamingChirpDecoder,
    samples: FloatArray,
    chunkSize: Int,
    expected: Int
  ): List<ByteArray> {
    val out = mutableListOf<ByteArray>()
    var i = 0
    while (i < samples.size) {
      val end = minOf(samples.size, i + chunkSize)
      val got = decoder.feed(samples.copyOfRange(i, end))
      if (got != null) {
        out.add(got)
        if (out.size == expected) return out
      }
      i = end
    }
    return out
  }

  @Test
  fun decodesAcrossArbitraryChunkBoundaries() {
    val payload = "continuous receiver".toByteArray()
    val samples = MicrochirpAtoms.encodeBytes(payload)

    for (chunk in intArrayOf(97, 512, 2048, 4096, 8192)) {
      val decoder = StreamingChirpDecoder()
      val got = feedAll(decoder, samples, chunk, expected = 1)
      assertArrayEquals("chunk=$chunk", payload, got.firstOrNull())
    }
  }

  @Test
  fun decodesTwoMessagesFromOneContinuousStream() {
    val first = "first drop".toByteArray()
    val second = "second drop, still listening".toByteArray()
    val samples = MicrochirpAtoms.encodeBytes(first) + MicrochirpAtoms.encodeBytes(second)

    val decoder = StreamingChirpDecoder()
    val got = feedAll(decoder, samples, chunkSize = 2048, expected = 2)

    assertArrayEquals(first, got.getOrNull(0))
    assertArrayEquals(second, got.getOrNull(1))
  }

  @Test
  fun decodesWhenTheMessageStartsOffTheSymbolGrid() {
    // A real recording does not start on a symbol boundary. Prepend room noise
    // so the first message begins at a phase that is not a multiple of 280.
    val payload = "off grid".toByteArray()
    var seed = 12345
    val noise = FloatArray(1234) {
      seed = seed * 1103515245 + 12345
      ((seed ushr 16) and 0x7FFF) / 32768f * 0.04f - 0.02f
    }
    val samples = noise + MicrochirpAtoms.encodeBytes(payload)

    val decoder = StreamingChirpDecoder()
    val got = feedAll(decoder, samples, chunkSize = 1000, expected = 1)

    assertArrayEquals(payload, got.firstOrNull())
  }

  @Test
  fun keepsListeningAcrossAGapOfSilence() {
    val first = "before the gap".toByteArray()
    val second = "after the gap".toByteArray()
    val gap = FloatArray(48000 * 3 / 2) // 1.5 s of nothing
    val samples = MicrochirpAtoms.encodeBytes(first) + gap + MicrochirpAtoms.encodeBytes(second)

    val decoder = StreamingChirpDecoder()
    val got = feedAll(decoder, samples, chunkSize = 1500, expected = 2)

    assertArrayEquals(first, got.getOrNull(0))
    assertArrayEquals(second, got.getOrNull(1))
  }

  @Test
  fun silenceNeverProducesAPayload() {
    val decoder = StreamingChirpDecoder()
    val silence = FloatArray(48000)
    assertNull(feedAll(decoder, silence, chunkSize = 2048, expected = 1).firstOrNull())
  }
}
