package com.prima.spimchirp.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.prima.spimchirp.not_as.MicrochirpAtoms

class ChirpRecorder {
  @Volatile
  private var recording = false
  private var thread: Thread? = null

  private val maxBufferSize = MicrochirpAtoms.SAMPLE_RATE.toInt() * 15
  private val ringBuffer = FloatArray(maxBufferSize)
  private var ringHead = 0
  private var ringSize = 0

  @Volatile
  private var chunkCallback: ((FloatArray) -> Unit)? = null

  fun isRecording(): Boolean = recording

  fun startStreaming(onChunk: (FloatArray) -> Unit = {}) {
    if (recording) return
    recording = true
    synchronized(ringBuffer) {
      ringHead = 0
      ringSize = 0
    }
    chunkCallback = onChunk
    val sampleRate = MicrochirpAtoms.SAMPLE_RATE.toInt()

    thread = Thread {
      var record: AudioRecord? = null
      var isFloat = false
      try {
        val audioSources = intArrayOf(
          MediaRecorder.AudioSource.VOICE_RECOGNITION,
          MediaRecorder.AudioSource.MIC,
          MediaRecorder.AudioSource.DEFAULT,
        )

        for (source in audioSources) {
          val minBufFloat = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
          if (minBufFloat > 0) {
            try {
              val candidate = AudioRecord(
                source,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                minBufFloat * 2
              )
              if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                record = candidate
                isFloat = true
                break
              } else {
                candidate.release()
              }
            } catch (_: SecurityException) {
            } catch (_: Exception) {}
          }
        }

        if (record == null) {
          for (source in audioSources) {
            val minBuf16 = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf16 > 0) {
              try {
                val candidate = AudioRecord(
                  source,
                  sampleRate,
                  AudioFormat.CHANNEL_IN_MONO,
                  AudioFormat.ENCODING_PCM_16BIT,
                  minBuf16 * 2
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                  record = candidate
                  isFloat = false
                  break
                } else {
                  candidate.release()
                }
              } catch (_: SecurityException) {
              } catch (_: Exception) {}
            }
          }
        }

        val activeRecord = record
        if (activeRecord == null || (activeRecord.state != AudioRecord.STATE_INITIALIZED)) {
          recording = false
          return@Thread
        }

        try {
          activeRecord.startRecording()
          if (activeRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            recording = false
            try { activeRecord.release() } catch (_: Exception) {}
            return@Thread
          }
        } catch (e: Exception) {
          e.printStackTrace()
          recording = false
          try { activeRecord.release() } catch (_: Exception) {}
          return@Thread
        }

        if (isFloat) {
          val floatBuf = FloatArray(2048)
          while (recording) {
            val read = try {
              activeRecord.read(floatBuf, 0, floatBuf.size, AudioRecord.READ_BLOCKING)
            } catch (_: Exception) { -1 }
            if (read > 0) {
              val chunk = floatBuf.copyOf(read)
              appendChunkToRing(chunk)
              try { onChunk(chunk) } catch (_: Exception) {}
            } else if (read < 0) {
              break
            }
          }
        } else {
          val shortBuf = ShortArray(2048)
          while (recording) {
            val read = try {
              activeRecord.read(shortBuf, 0, shortBuf.size)
            } catch (_: Exception) { -1 }
            if (read > 0) {
              val chunk = FloatArray(read)
              for (i in 0 until read) {
                chunk[i] = shortBuf[i].toFloat() / 32768.0f
              }
              appendChunkToRing(chunk)
              try { onChunk(chunk) } catch (_: Exception) {}
            } else if (read < 0) {
              break
            }
          }
        }

        try { activeRecord.stop() } catch (_: Exception) {}
        try { activeRecord.release() } catch (_: Exception) {}
      } catch (e: Exception) {
        e.printStackTrace()
        try { record?.release() } catch (_: Exception) {}
      } finally {
        recording = false
      }
    }.apply { start() }
  }

  private fun appendChunkToRing(chunk: FloatArray) {
    synchronized(ringBuffer) {
      for (v in chunk) {
        ringBuffer[ringHead] = v
        ringHead = (ringHead + 1) % maxBufferSize
        if (ringSize < maxBufferSize) {
          ringSize++
        }
      }
    }
  }

  fun getSnapshot(): FloatArray {
    synchronized(ringBuffer) {
      if (ringSize <= 0) return FloatArray(0)
      val count = minOf(ringSize, maxBufferSize)
      val out = FloatArray(count)
      val start = if (ringSize < maxBufferSize) 0 else ringHead
      for (i in 0 until count) {
        val idx = (start + i) % maxBufferSize
        if (idx in ringBuffer.indices && i in out.indices) {
          out[i] = ringBuffer[idx]
        }
      }
      return out
    }
  }

  fun stop() {
    recording = false
    try {
      thread?.join(300)
    } catch (_: Exception) {}
    thread = null
    chunkCallback = null
  }
}
