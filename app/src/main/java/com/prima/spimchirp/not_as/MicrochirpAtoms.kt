package com.prima.spimchirp.not_as

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

object MicrochirpAtoms {
  const val SAMPLE_RATE: Float = 48000f
  const val SYMBOL_SAMPLES: Int = 280 // 5.8ms symbol duration for fast, robust, high-quality photo drops
  const val PREAMBLE_LEN: Int = 24
  const val MAGIC: Byte = 0x53 // 'S' for SPIM
  const val MAX_PAYLOAD: Int = 16384
  const val TAPER_MS: Int = 2
  const val TAPER_SAMPLES: Int = (SAMPLE_RATE * TAPER_MS / 1000f).toInt()

  // 8-FSK Frequencies (3 bits per symbol, 75 bytes/sec throughput).
  // The active band is state rather than a constant: the encoder, the decoder and the
  // signal detector all read it, so picking a band in Settings retunes the whole modem.
  // Both devices have to be on the same band for a transfer to succeed.
  data class Band(
    val name: String,
    val detail: String,
    val tones: FloatArray,
    val noiseReference: Float
  )

  val BANDS = listOf(
    Band(
      name = "Standard",
      detail = "16.7 - 20.2 kHz - balanced, the shipped default",
      tones = floatArrayOf(16700f, 17200f, 17700f, 18200f, 18700f, 19200f, 19700f, 20200f),
      noiseReference = 14000f
    ),
    Band(
      name = "Audible",
      detail = "10.7 - 14.2 kHz - loudest, works on any speaker",
      tones = floatArrayOf(10700f, 11200f, 11700f, 12200f, 12700f, 13200f, 13700f, 14200f),
      noiseReference = 8000f
    ),
    Band(
      name = "Ultrasonic",
      detail = "18.7 - 22.2 kHz - quietest, needs wide-range hardware",
      tones = floatArrayOf(18700f, 19200f, 19700f, 20200f, 20700f, 21200f, 21700f, 22200f),
      noiseReference = 15500f
    )
  )

  val DEFAULT_BAND_INDEX = 0

  @Volatile
  var FREQUENCIES: FloatArray = BANDS[DEFAULT_BAND_INDEX].tones

  @Volatile
  var FREQ_NOISE: Float = BANDS[DEFAULT_BAND_INDEX].noiseReference

  fun applyBand(index: Int) {
    val band = BANDS[index.coerceIn(0, BANDS.lastIndex)]
    FREQUENCIES = band.tones
    FREQ_NOISE = band.noiseReference
  }

  fun preambleSymbols(): IntArray {
    return IntArray(PREAMBLE_LEN) { i -> if (i % 2 == 0) 0 else 7 }
  }

  fun bytesTo3BitSymbols(bytes: ByteArray): IntArray {
    val totalBits = bytes.size * 8
    val symbolCount = (totalBits + 2) / 3
    val symbols = IntArray(symbolCount)
    var bitIdx = 0
    for (i in 0 until symbolCount) {
      var sym = 0
      for (b in 0 until 3) {
        val currBitIdx = bitIdx + b
        val bitVal = if (currBitIdx < totalBits) {
          val bytePos = currBitIdx / 8
          val bitPos = 7 - (currBitIdx % 8)
          (bytes[bytePos].toInt() shr bitPos) and 1
        } else {
          0
        }
        sym = (sym shl 1) or bitVal
      }
      symbols[i] = sym
      bitIdx += 3
    }
    return symbols
  }

  fun symbolsTo3BitBytes(symbols: IntArray, byteCount: Int): ByteArray {
    val out = ByteArray(byteCount)
    var bitIdx = 0
    val totalBits = byteCount * 8
    for (sym in symbols) {
      for (b in 2 downTo 0) {
        if (bitIdx < totalBits) {
          val bitVal = (sym shr b) and 1
          val bytePos = bitIdx / 8
          val bitPos = 7 - (bitIdx % 8)
          if (bitVal == 1) {
            out[bytePos] = (out[bytePos].toInt() or (1 shl bitPos)).toByte()
          }
          bitIdx++
        }
      }
    }
    return out
  }

  private fun crc8(data: ByteArray): Byte {
    var crc: Int = 0x00
    for (b in data) {
      crc = crc xor (b.toInt() and 0xFF)
      for (i in 0 until 8) {
        if ((crc and 0x80) != 0) {
          crc = ((crc shl 1) xor 0x07) and 0xFF
        } else {
          crc = (crc shl 1) and 0xFF
        }
      }
    }
    return crc.toByte()
  }

  private fun crc16(data: ByteArray): Int {
    var crc = 0xFFFF
    for (b in data) {
      crc = crc xor ((b.toInt() and 0xFF) shl 8)
      for (i in 0 until 8) {
        if ((crc and 0x8000) != 0) {
          crc = ((crc shl 1) xor 0x1021) and 0xFFFF
        } else {
          crc = (crc shl 1) and 0xFFFF
        }
      }
    }
    return crc and 0xFFFF
  }

  fun goertzelPower(samples: FloatArray, start: Int, length: Int, freq: Float): Float {
    if (length <= 0 || start + length > samples.size) return 0f
    val coeff = 2f * cos(2f * PI.toFloat() * freq / SAMPLE_RATE)
    var s1 = 0f
    var s2 = 0f
    val end = start + length
    for (i in start until end) {
      val x = samples[i]
      val s0 = x + coeff * s1 - s2
      s2 = s1
      s1 = s0
    }
    val power = s1 * s1 - coeff * s1 * s2 + s2 * s2
    return abs(power)
  }

  // Bandpass 8-FSK Filter: Detects if audio stream contains tuned 8-FSK carrier energy vs ambient noise
  fun isTunedSignalPresent(samples: FloatArray): Boolean {
    if (samples.size < SYMBOL_SAMPLES * 8) return false
    var detectedCount = 0
    val totalCheckSymbols = minOf(samples.size / SYMBOL_SAMPLES, 30)
    for (s in 0 until totalCheckSymbols) {
      val start = s * SYMBOL_SAMPLES
      var maxPower = 0f
      for (f in FREQUENCIES) {
        val p = goertzelPower(samples, start, SYMBOL_SAMPLES, f)
        if (p > maxPower) maxPower = p
      }
      val pn = goertzelPower(samples, start, SYMBOL_SAMPLES, FREQ_NOISE)
      if (maxPower > 0.0001f && maxPower > (pn * 1.8f)) {
        detectedCount++
      }
    }
    return detectedCount >= 2
  }

  fun symbolVal(samples: FloatArray, start: Int): Int {
    var maxVal = 0
    var maxPower = -1f
    for (i in FREQUENCIES.indices) {
      val p = goertzelPower(samples, start, SYMBOL_SAMPLES, FREQUENCIES[i])
      if (p > maxPower) {
        maxPower = p
        maxVal = i
      }
    }
    return maxVal
  }

  private fun writeSymbolSamples8Fsk(symVal: Int, phaseRef: FloatArray, out: FloatArray, outOffset: Int) {
    val freq = FREQUENCIES[symVal.coerceIn(0, 7)]
    var phase = phaseRef[0]
    val step = (2.0f * Math.PI.toFloat() * freq / SAMPLE_RATE)
    for (i in 0 until SYMBOL_SAMPLES) {
      out[outOffset + i] = Math.sin(phase.toDouble()).toFloat()
      phase += step
    }
    val twoPi = 2.0f * Math.PI.toFloat()
    phaseRef[0] = ((phase % twoPi) + twoPi) % twoPi
  }

  fun encodeBytes(bytes: ByteArray): FloatArray {
    val safeBytes = if (bytes.size > MAX_PAYLOAD) bytes.copyOf(MAX_PAYLOAD) else bytes
    val pre = preambleSymbols()

    val len = safeBytes.size
    val header = ByteArray(4)
    header[0] = MAGIC
    header[1] = ((len shr 8) and 0xFF).toByte()
    header[2] = (len and 0xFF).toByte()
    header[3] = crc8(byteArrayOf(header[0], header[1], header[2]))
    val crc = crc16(safeBytes)
    val crcBytes = ByteArray(2)
    crcBytes[0] = ((crc shr 8) and 0xFF).toByte()
    crcBytes[1] = (crc and 0xFF).toByte()
    val framed = ByteArray(header.size + safeBytes.size + crcBytes.size)
    System.arraycopy(header, 0, framed, 0, header.size)
    System.arraycopy(safeBytes, 0, framed, header.size, safeBytes.size)
    System.arraycopy(crcBytes, 0, framed, header.size + safeBytes.size, crcBytes.size)

    val payloadSymbols = bytesTo3BitSymbols(framed)
    val symbols = IntArray(pre.size + payloadSymbols.size)
    System.arraycopy(pre, 0, symbols, 0, pre.size)
    System.arraycopy(payloadSymbols, 0, symbols, pre.size, payloadSymbols.size)

    val out = FloatArray(symbols.size * SYMBOL_SAMPLES)
    val phaseRef = floatArrayOf(0f)
    var n = 0
    for (s in symbols) {
      writeSymbolSamples8Fsk(s, phaseRef, out, n)
      n += SYMBOL_SAMPLES
    }

    val taper = TAPER_SAMPLES
    for (i in 0 until taper) {
      if (i >= out.size) break
      val t = (Math.PI.toFloat() * i.toFloat() / taper.toFloat())
      val w = (1.0f - Math.cos(t.toDouble()).toFloat()) * 0.5f
      out[i] = out[i] * w
    }
    for (i in 0 until taper) {
      val idx = out.size - 1 - i
      if (idx < 0) break
      val t = (Math.PI.toFloat() * i.toFloat() / taper.toFloat())
      val w = (1.0f - Math.cos(t.toDouble()).toFloat()) * 0.5f
      out[idx] = out[idx] * w
    }
    return out
  }

  fun decodeSamples(samples: FloatArray): Result<ByteArray> {
    val preamble = preambleSymbols()
    val minRequiredSamples = (PREAMBLE_LEN + 12) * SYMBOL_SAMPLES
    if (samples.size < minRequiredSamples) {
      return Result.failure(Exception("too short for microchirp signal: ${samples.size} samples"))
    }

    var bestMatchSymIndex = -1
    var bestMatchCount = -1
    var bestMatchSymbols = IntArray(0)

    val step = 16
    for (offset in 0 until SYMBOL_SAMPLES step step) {
      val nSymbols = (samples.size - offset) / SYMBOL_SAMPLES
      if (nSymbols < PREAMBLE_LEN + 12) continue

      val syms = IntArray(nSymbols)
      for (symIdx in 0 until nSymbols) {
        val start = offset + symIdx * SYMBOL_SAMPLES
        syms[symIdx] = symbolVal(samples, start)
      }

      for (i in 0 until (nSymbols - PREAMBLE_LEN + 1)) {
        var matchCount = 0
        for (k in 0 until PREAMBLE_LEN) {
          if (syms[i + k] == preamble[k]) matchCount++
        }
        if (matchCount == PREAMBLE_LEN) {
          bestMatchSymIndex = i
          bestMatchCount = matchCount
          bestMatchSymbols = syms
          break
        }
        if (matchCount > bestMatchCount) {
          bestMatchSymIndex = i
          bestMatchCount = matchCount
          bestMatchSymbols = syms
        }
      }
      if (bestMatchCount == PREAMBLE_LEN) break
    }

    if (bestMatchCount < 10 || bestMatchSymIndex < 0) {
      return Result.failure(Exception("preamble not detected in audio signal"))
    }

    val payloadStartSym = bestMatchSymIndex + PREAMBLE_LEN
    val totalPayloadSymbols = bestMatchSymbols.size - payloadStartSym

    if (totalPayloadSymbols < 11) {
      return Result.failure(Exception("insufficient framed header data"))
    }

    val headerSymbols = IntArray(11)
    System.arraycopy(bestMatchSymbols, payloadStartSym, headerSymbols, 0, 11)
    val headerBytes = symbolsTo3BitBytes(headerSymbols, 4)

    if (headerBytes.isEmpty() || headerBytes[0] != MAGIC) {
      return Result.failure(Exception("invalid magic byte"))
    }

    val hdrCrcCalc = crc8(byteArrayOf(headerBytes[0], headerBytes[1], headerBytes[2])).toInt() and 0xFF
    val hdrCrcRx = headerBytes[3].toInt() and 0xFF
    if (hdrCrcCalc != hdrCrcRx) {
      return Result.failure(Exception("header CRC8 mismatch"))
    }

    val len = ((headerBytes[1].toInt() and 0xFF) shl 8) or (headerBytes[2].toInt() and 0xFF)
    if (len <= 0 || len > MAX_PAYLOAD) {
      return Result.failure(Exception("invalid length header: $len"))
    }

    val totalFramedBytes = 4 + len + 2
    val requiredSymbols = (totalFramedBytes * 8 + 2) / 3
    if (totalPayloadSymbols < requiredSymbols) {
      return Result.failure(Exception("frame incomplete - waiting for remaining symbols ($totalPayloadSymbols / $requiredSymbols)"))
    }

    val frameSymbols = IntArray(requiredSymbols)
    System.arraycopy(bestMatchSymbols, payloadStartSym, frameSymbols, 0, requiredSymbols)
    val framed = symbolsTo3BitBytes(frameSymbols, totalFramedBytes)

    // Without this, a symbol clock that is a few samples off still produces a
    // payload of the right length with a few wrong bytes, and nothing downstream
    // can tell. The encoder appends crc16 of the payload bytes.
    val expectedCrc = crc16(framed.copyOfRange(4, 4 + len))
    val receivedCrc = ((framed[4 + len].toInt() and 0xFF) shl 8) or
      (framed[4 + len + 1].toInt() and 0xFF)
    if (expectedCrc != receivedCrc) {
      return Result.failure(Exception("payload CRC16 mismatch"))
    }

    val payload = ByteArray(len)
    System.arraycopy(framed, 4, payload, 0, len)
    return Result.success(payload)
  }

  /** Payload length declared by the header if a valid one begins at [start],
   *  else -1. Checks magic and the header CRC8. */
  fun payloadLengthAt(samples: FloatArray, start: Int): Int {
    val pre = PREAMBLE_LEN
    if (start < 0 || samples.size < start + (pre + 11) * SYMBOL_SAMPLES) return -1
    val headerSymbols = IntArray(11)
    for (k in 0 until 11) {
      headerSymbols[k] = symbolVal(samples, start + (pre + k) * SYMBOL_SAMPLES)
    }
    val header = symbolsTo3BitBytes(headerSymbols, 4)
    if (header[0] != MAGIC) return -1
    if (crc8(byteArrayOf(header[0], header[1], header[2])) != header[3]) return -1
    val len = ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
    if (len <= 0 || len > MAX_PAYLOAD) return -1
    return len
  }

  /** Decode the frame whose preamble starts exactly at [start], without
   *  searching for the preamble. Returns null unless both the header CRC8 and
   *  the payload CRC16 match. */
  fun decodeFrameAt(samples: FloatArray, start: Int): ByteArray? {
    val pre = PREAMBLE_LEN
    val sym = SYMBOL_SAMPLES
    val len = payloadLengthAt(samples, start)
    if (len <= 0) return null
    val totalBytes = 4 + len + 2
    val symbolsNeeded = (totalBytes * 8 + 2) / 3
    if (samples.size < start + (pre + symbolsNeeded) * sym) return null

    val frameSymbols = IntArray(symbolsNeeded)
    for (k in 0 until symbolsNeeded) {
      frameSymbols[k] = symbolVal(samples, start + (pre + k) * sym)
    }
    val framed = symbolsTo3BitBytes(frameSymbols, totalBytes)
    val expectedCrc = crc16(framed.copyOfRange(4, 4 + len))
    val receivedCrc = ((framed[4 + len].toInt() and 0xFF) shl 8) or
      (framed[4 + len + 1].toInt() and 0xFF)
    if (expectedCrc != receivedCrc) return null
    return framed.copyOfRange(4, 4 + len)
  }
}
