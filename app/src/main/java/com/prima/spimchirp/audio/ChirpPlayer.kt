package com.prima.spimchirp.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.prima.spimchirp.not_as.MicrochirpAtoms

class ChirpPlayer {
  @Volatile
  private var isPlaying = false
  private var currentTrack: AudioTrack? = null

  fun play(samples: FloatArray, onComplete: (() -> Unit)? = null): Boolean {
    stop()
    return try {
      val sampleRate = MicrochirpAtoms.SAMPLE_RATE.toInt()
      val minBuf = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_FLOAT
      )
      val bufSize = if (minBuf > 0) minBuf * 4 else 16384

      val track = AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        )
        .setBufferSizeInBytes(bufSize)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

      currentTrack = track
      isPlaying = true
      track.play()

      Thread {
        try {
          val chunkSize = 4096
          var offset = 0
          while (isPlaying && offset < samples.size) {
            val count = minOf(chunkSize, samples.size - offset)
            val written = track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
          }
          if (isPlaying) {
            val durationMs = ((samples.size.toFloat() / MicrochirpAtoms.SAMPLE_RATE) * 1000).toLong()
            Thread.sleep(minOf(durationMs, 500L))
          }
        } catch (e: Exception) {
          e.printStackTrace()
        } finally {
          isPlaying = false
          try {
            track.stop()
            track.release()
          } catch (e: Exception) {
            e.printStackTrace()
          }
          if (currentTrack == track) currentTrack = null
          onComplete?.invoke()
        }
      }.start()

      true
    } catch (e: Exception) {
      e.printStackTrace()
      isPlaying = false
      false
    }
  }

  fun stop() {
    isPlaying = false
    try {
      currentTrack?.stop()
      currentTrack?.release()
    } catch (e: Exception) {
      e.printStackTrace()
    }
    currentTrack = null
  }
}
