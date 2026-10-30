package com.prima.spimchirp.model

enum class ChirpPhase { MENU, TRANSMIT, RECEIVE, LISTENING, PREAMBLE_LOCKED, READING, DONE, READY }

data class ChirpState(
  val phase: ChirpPhase = ChirpPhase.MENU,
  val payload: String = "",
  val fileBytes: ByteArray? = null,
  val fileName: String? = null,
  val cachedSamples: FloatArray? = null,
  val lastSentBytes: Int = 0,
  val lastRecvBytes: Int = 0,
  val status: String = "TYPE A SECRET NOTE & BEAM IT",
  val txProgress: Float = 0f,
  val rxP0: Float = 0f,
  val rxP1: Float = 0f,
  val decodedText: String = "",
  val decodedHex: String = ""
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    other as ChirpState
    if (phase != other.phase) return false
    if (payload != other.payload) return false
    if (fileBytes != null) {
      if (other.fileBytes == null) return false
      if (!fileBytes.contentEquals(other.fileBytes)) return false
    } else if (other.fileBytes != null) return false
    if (fileName != other.fileName) return false
    if (cachedSamples != null) {
      if (other.cachedSamples == null) return false
      if (!cachedSamples.contentEquals(other.cachedSamples)) return false
    } else if (other.cachedSamples != null) return false
    if (lastSentBytes != other.lastSentBytes) return false
    if (lastRecvBytes != other.lastRecvBytes) return false
    if (status != other.status) return false
    if (txProgress != other.txProgress) return false
    if (rxP0 != other.rxP0) return false
    if (rxP1 != other.rxP1) return false
    if (decodedText != other.decodedText) return false
    if (decodedHex != other.decodedHex) return false
    return true
  }

  override fun hashCode(): Int {
    var result = phase.hashCode()
    result = 31 * result + payload.hashCode()
    result = 31 * result + (fileBytes?.contentHashCode() ?: 0)
    result = 31 * result + (fileName?.hashCode() ?: 0)
    result = 31 * result + (cachedSamples?.contentHashCode() ?: 0)
    result = 31 * result + lastSentBytes
    result = 31 * result + lastRecvBytes
    result = 31 * result + status.hashCode()
    result = 31 * result + txProgress.hashCode()
    result = 31 * result + rxP0.hashCode()
    result = 31 * result + rxP1.hashCode()
    result = 31 * result + decodedText.hashCode()
    result = 31 * result + decodedHex.hashCode()
    return result
  }
}
