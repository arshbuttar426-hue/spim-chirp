package com.prima.spimchirp.logic

import com.prima.spimchirp.audio.ChirpPlayer
import com.prima.spimchirp.model.ChirpPhase
import com.prima.spimchirp.model.ChirpState
import com.prima.spimchirp.not_as.MicrochirpAtoms

data class PipelineResult(
  val state: ChirpState,
  val samplesToPlay: FloatArray? = null,
  val action: String = "none"
)

object Pipeline {
  private val player = ChirpPlayer()

  fun run(frame: Frame, state: ChirpState, onComplete: (() -> Unit)? = null): PipelineResult {
    if (frame.name == "menu") {
      return PipelineResult(state.copy(status = "Select a file to transmit"))
    }
    if (frame.name == "prepare") {
      val bytes = state.fileBytes
      if (bytes == null || bytes.isEmpty()) {
        return PipelineResult(state.copy(status = "No file selected - select a file first"))
      }
      val samples = MicrochirpAtoms.encodeBytes(bytes)
      val label = state.fileName ?: "file"
      return PipelineResult(
        state.copy(
          phase = ChirpPhase.READY,
          cachedSamples = samples,
          lastSentBytes = bytes.size,
          status = "READY TO PLAY - $label (${bytes.size} bytes). Tap PLAY to transmit."
        )
      )
    }
    if (frame.name == "transmit") {
      val samples = state.cachedSamples
      if (samples == null || samples.isEmpty()) {
        return PipelineResult(state.copy(status = "No encoded chirp ready - select a file first"))
      }
      val ok = player.play(samples, onComplete)
      val label = state.fileName ?: "file"
      return PipelineResult(
        state.copy(
          phase = ChirpPhase.TRANSMIT,
          lastSentBytes = state.lastSentBytes,
          status = if (ok) "PLAYING CHIRP - $label (${state.lastSentBytes} bytes). Replayable." else "Playback failed"
        ),
        samplesToPlay = samples,
        action = "transmit"
      )
    }
    if (frame.name == "receive") {
      return PipelineResult(
        state.copy(phase = ChirpPhase.RECEIVE, status = "listening..."),
        action = "arm_mic"
      )
    }
    return PipelineResult(state.copy(status = "idle"))
  }

  fun stopPlayer() {
    player.stop()
  }
}
