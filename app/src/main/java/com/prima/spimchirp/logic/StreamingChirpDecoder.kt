package com.prima.spimchirp.logic

import com.prima.spimchirp.not_as.MicrochirpAtoms
import kotlin.math.sqrt

/**
 * Continuous 8-FSK receiver.
 *
 * The old catch loop copied the whole 15 s ring buffer every 100 ms and ran the
 * batch decoder over all of it. That is O(buffer) work per tick, so it pinned a
 * core, and the only way to get a decode was to stop the recording. This class
 * consumes the recorder's chunk callback instead, so cost is proportional to
 * the new audio rather than to everything recorded so far:
 *
 *  - while unlocked it keeps only ~26 symbols and searches them for a preamble,
 *    at every 16-sample phase (a message can start on any sample, not just on a
 *    symbol boundary relative to the stream);
 *  - once locked it reads the 4-byte header to learn the payload length, waits
 *    for exactly that many samples, and decodes once;
 *  - on success it drops the message and immediately looks for the next one, so
 *    listening never has to stop in order to decode.
 *
 * A payload is only returned after its CRC16 checks out, so a near-miss symbol
 * clock can never surface as silently corrupted text.
 */
class StreamingChirpDecoder {
  private var buf = FloatArray(INITIAL_CAPACITY)
  private var size = 0
  private var locked = false
  private var sinceSearch = 0

  /** Feed one chunk of microphone samples. Returns the payload when a complete,
   *  CRC-valid frame has been received, otherwise null. */
  fun feed(chunk: FloatArray): ByteArray? {
    if (chunk.isEmpty()) return null
    append(chunk)
    sinceSearch += chunk.size
    return drain()
  }

  fun reset() {
    size = 0
    locked = false
    sinceSearch = 0
  }

  private fun drain(): ByteArray? {
    val sym = MicrochirpAtoms.SYMBOL_SAMPLES
    val pre = MicrochirpAtoms.PREAMBLE_LEN

    if (!locked) {
      if (size < (pre + 1) * sym) return null
      // Searching every chunk is wasteful: a 24-symbol preamble lasts ~140 ms,
      // so one search per ~85 ms of audio still cannot miss a frame.
      if (sinceSearch < SEARCH_STRIDE) return null
      sinceSearch = 0

      if (!hasSignal()) {
        trimUnlocked()
        return null
      }

      val start = findPreambleStart()
      if (start < 0) {
        trimUnlocked()
        return null
      }
      dropFront(start)
      locked = true
    }

    // Header (11 symbols) has to be readable from the latest candidate offset.
    if (size < MAX_LOCK_LEAD + (pre + 11) * sym) return null
    return decodeLocked()
  }

  /** Try every offset the lock could be off by. A shifted read can still pass
   *  the header CRC8 but corrupt payload symbols, so the CRC16 is what decides;
   *  only an offset that reproduces the whole frame is accepted. */
  private fun decodeLocked(): ByteArray? {
    val sym = MicrochirpAtoms.SYMBOL_SAMPLES
    val pre = MicrochirpAtoms.PREAMBLE_LEN
    var waitingForMore = false

    var off = 0
    while (off <= MAX_LOCK_LEAD) {
      val len = MicrochirpAtoms.payloadLengthAt(buf, off)
      if (len > 0) {
        val messageSamples = (pre + symbolsForBytes(4 + len + 2)) * sym
        if (messageSamples > MAX_MESSAGE_SAMPLES) return abandonLock()
        val needSamples = off + messageSamples
        if (size >= needSamples) {
          val payload = MicrochirpAtoms.decodeFrameAt(buf, off)
          if (payload != null) {
            dropFront(needSamples)
            locked = false
            sinceSearch = SEARCH_STRIDE
            return payload
          }
        } else {
          waitingForMore = true
        }
      }
      off += LOCK_LEAD_STEP
    }

    // A declared frame is still short of samples: keep listening, it may be
    // arriving right now.
    if (waitingForMore) return null
    return abandonLock()
  }

  /** Give up on this candidate but keep the tail: a real message may be
   *  starting just behind it. */
  private fun abandonLock(): ByteArray? {
    locked = false
    sinceSearch = SEARCH_STRIDE
    // Step past the candidate symbol by symbol, so a message hidden behind it
    // can still be found.
    dropFront(MicrochirpAtoms.SYMBOL_SAMPLES)
    return null
  }

  /** Sample offset of a preamble match, or -1.
   *
   *  A real acoustic path rarely reproduces all 24 symbols, and the message can
   *  begin on any sample, so every 16-sample phase is tried.
   *
   *  A full 24/24 match is only possible while the 24-symbol window sits inside
   *  the preamble, so among full matches the earliest one is at or before the
   *  true start. Locking there means no message sample is ever cut off; the
   *  remaining sub-symbol error is resolved by [decodeLocked]. */
  private fun findPreambleStart(): Int {
    val sym = MicrochirpAtoms.SYMBOL_SAMPLES
    val preamble = MicrochirpAtoms.preambleSymbols()
    var earliestFull = -1
    var bestOffset = -1
    var bestMatches = 0

    var phase = 0
    while (phase < sym) {
      val count = (size - phase) / sym
      if (count >= preamble.size) {
        // Decode each symbol once per phase instead of once per window.
        val vals = IntArray(count)
        for (i in 0 until count) {
          vals[i] = MicrochirpAtoms.symbolVal(buf, phase + i * sym)
        }
        for (i in 0..count - preamble.size) {
          var matches = 0
          for (k in preamble.indices) {
            if (vals[i + k] == preamble[k]) matches++
          }
          if (matches == preamble.size) {
            val at = phase + i * sym
            if (earliestFull < 0 || at < earliestFull) earliestFull = at
          } else if (matches > bestMatches) {
            bestMatches = matches
            bestOffset = phase + i * sym
          }
        }
      }
      phase += OFFSET_STEP
    }
    if (earliestFull >= 0) return earliestFull
    return if (bestMatches >= PREAMBLE_TOLERANCE) bestOffset else -1
  }

  private fun symbolsForBytes(bytes: Int): Int = (bytes * 8 + 2) / 3

  /** Cheap gate: the detector is the expensive part, and silence or room noise
   *  can never be a preamble. */
  private fun hasSignal(): Boolean {
    val n = minOf(size, 3 * MicrochirpAtoms.SYMBOL_SAMPLES)
    if (n <= 0) return false
    var energy = 0.0
    var i = size - n
    while (i < size) {
      energy += buf[i] * buf[i].toDouble()
      i++
    }
    return sqrt(energy / n) >= ENERGY_FLOOR
  }

  private fun trimUnlocked() {
    val keep = (MicrochirpAtoms.PREAMBLE_LEN + 2) * MicrochirpAtoms.SYMBOL_SAMPLES
    if (size > keep) dropFront(size - keep)
  }

  private fun append(chunk: FloatArray) {
    ensureCapacity(size + chunk.size)
    System.arraycopy(chunk, 0, buf, size, chunk.size)
    size += chunk.size
  }

  private fun dropFront(count: Int) {
    if (count <= 0) return
    if (count >= size) {
      size = 0
      return
    }
    System.arraycopy(buf, count, buf, 0, size - count)
    size -= count
  }

  private fun ensureCapacity(required: Int) {
    if (required <= buf.size) return
    var cap = buf.size
    while (cap < required) cap = (cap * 2).coerceAtMost(MAX_BUFFER)
    if (cap < required) cap = required
    buf = buf.copyOf(cap)
  }

  private companion object {
    const val INITIAL_CAPACITY = 64 * 1024
    const val OFFSET_STEP = 16
    const val PREAMBLE_TOLERANCE = 22
    const val SEARCH_STRIDE = 4096
    const val ENERGY_FLOOR = 0.002
    /** A lock is never late, but can be up to half a symbol early. */
    const val MAX_LOCK_LEAD = MicrochirpAtoms.SYMBOL_SAMPLES / 2
    const val LOCK_LEAD_STEP = 4
    /** Bound the pending buffer: ~40 s of audio, enough for a ~4.4 KB drop. */
    const val MAX_MESSAGE_SAMPLES = 48000 * 40
    const val MAX_BUFFER = MAX_MESSAGE_SAMPLES + 48000
  }
}
