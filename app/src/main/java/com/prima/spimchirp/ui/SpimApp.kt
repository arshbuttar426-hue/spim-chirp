package com.prima.spimchirp.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prima.spimchirp.audio.ChirpPlayer
import com.prima.spimchirp.audio.ChirpRecorder
import com.prima.spimchirp.logic.StreamingChirpDecoder
import com.prima.spimchirp.not_as.MicrochirpAtoms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.time.Duration.Companion.milliseconds

class SpimViewModel(application: Application) : AndroidViewModel(application) {
  private val player = ChirpPlayer()
  private val recorder = ChirpRecorder()

  private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  // --- Settings, persisted across launches ---
  var bandIndex by mutableIntStateOf(prefs.getInt(KEY_BAND, MicrochirpAtoms.DEFAULT_BAND_INDEX))
    private set

  var keepScreenOn by mutableStateOf(prefs.getBoolean(KEY_KEEP_SCREEN_ON, true))
    private set

  /** Display name of the user's local drop sound, or null for the built-in chime. */
  var dropSoundName by mutableStateOf(prefs.getString(KEY_DROP_SOUND_NAME, null))
    private set

  private var dropPlayer: MediaPlayer? = null

  init {
    MicrochirpAtoms.applyBand(bandIndex)
  }

  fun setBand(index: Int) {
    bandIndex = index
    prefs.edit().putInt(KEY_BAND, index).apply()
    MicrochirpAtoms.applyBand(index)
  }

  fun updateKeepScreenOn(enabled: Boolean) {
    keepScreenOn = enabled
    prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
  }

  fun setDropSound(context: Context, uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val name = displayName(context, uri) ?: "Drop sound"
        val dest = File(context.filesDir, DROP_SOUND_FILE)
        context.contentResolver.openInputStream(uri)?.use { input ->
          dest.outputStream().use { output -> input.copyTo(output) }
        } ?: return@launch
        prefs.edit()
          .putString(KEY_DROP_SOUND_PATH, dest.absolutePath)
          .putString(KEY_DROP_SOUND_NAME, name)
          .apply()
        viewModelScope.launch(Dispatchers.Main.immediate) { dropSoundName = name }
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
  }

  fun clearDropSound() {
    prefs.edit().remove(KEY_DROP_SOUND_PATH).remove(KEY_DROP_SOUND_NAME).apply()
    dropSoundName = null
    try {
      getApplication<Application>().getFileStreamPath(DROP_SOUND_FILE).delete()
    } catch (_: Exception) {}
  }

  private fun displayName(context: Context, uri: Uri): String? {
    return try {
      var name: String? = null
      context.contentResolver.query(uri, null, null, null, null)?.use {
        if (it.moveToFirst()) {
          val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (idx >= 0) name = it.getString(idx)
        }
      }
      name ?: uri.lastPathSegment ?: "Drop sound"
    } catch (_: Exception) {
      uri.lastPathSegment ?: "Drop sound"
    }
  }

  fun restoreDefaultSettings() {
    setBand(MicrochirpAtoms.DEFAULT_BAND_INDEX)
    updateKeepScreenOn(true)
    clearDropSound()
  }

  override fun onCleared() {
    super.onCleared()
    try { dropPlayer?.release() } catch (_: Exception) {}
    dropPlayer = null
    try { recorder.stop() } catch (_: Exception) {}
  }

  companion object {
    const val PREFS_NAME = "spim_settings"
    const val KEY_BAND = "band_index"
    const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    const val KEY_DROP_SOUND_PATH = "drop_sound_path"
    const val KEY_DROP_SOUND_NAME = "drop_sound_name"
    const val DROP_SOUND_FILE = "drop_sound"
  }

  var isBeaming by mutableStateOf(false)
    private set
  var beamProgress by mutableFloatStateOf(0f)
    private set
  var beamFinished by mutableStateOf(false)
    private set

  // Data processing animation state before beaming
  var isProcessingData by mutableStateOf(false)
    private set

  // Flashy Red Button state after encoding is complete
  var isEncodingReady by mutableStateOf(false)
    private set

  // Selected Image for Preview Screen
  var selectedImageBitmap by mutableStateOf<Bitmap?>(null)
    private set

  // 1. UI Visual Animation Trigger State
  var isListeningUI by mutableStateOf(false)
    private set

  // 2. Microphone Sound Intensity State
  var micAmplitude by mutableFloatStateOf(0f)
    private set

  // 3. Tuned Signal Caught Indicator
  var isSignalCaught by mutableStateOf(false)
    private set

  var receivedText by mutableStateOf("")
    private set
  var receivedBitmap by mutableStateOf<Bitmap?>(null)
    private set
  private var lastReceivedBytes: ByteArray? = null

  var noteText by mutableStateOf("")
  var attachedFileName by mutableStateOf<String?>(null)
  var attachedFileSize by mutableIntStateOf(0)
  var payloadSize by mutableIntStateOf(0)
  private var cachedSamples: FloatArray? = null

  private var micJob: Job? = null
  private var decodeJob: Job? = null

  /** Chunks flow recorder thread -> decoder coroutine. Bounded so a stalled
   *  decoder cannot grow memory without limit. */
  private val decodeQueue = LinkedBlockingQueue<FloatArray>(512)

  // 16-Color Palette for fallback
  private val paletteColors = intArrayOf(
    0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(),
    0xFF0000FF.toInt(), 0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(), 0xFFFF00FF.toInt(),
    0xFFFF8000.toInt(), 0xFFFF80C0.toInt(), 0xFF804000.toInt(), 0xFF404040.toInt(),
    0xFFC0C0C0.toInt(), 0xFFFFCC99.toInt(), 0xFF000080.toInt(), 0xFF808000.toInt()
  )

  fun clearAttachment() {
    attachedFileName = null
    attachedFileSize = 0
    noteText = ""
    cachedSamples = null
    payloadSize = 0
    beamFinished = false
    isEncodingReady = false
  }

  fun setSelectedImage(context: Context, uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val bmp = BitmapFactory.decodeStream(inputStream)
        inputStream?.close()

        viewModelScope.launch(Dispatchers.Main) {
          selectedImageBitmap = bmp
          isEncodingReady = false
        }
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
  }

  fun clearSelectedImage() {
    selectedImageBitmap = null
    isProcessingData = false
    isEncodingReady = false
  }

  fun beamImage() {
    val bmp = selectedImageBitmap ?: return
    if (isBeaming || isProcessingData) return

    if (isEncodingReady && cachedSamples != null) {
      // Trigger audio transmission
      isEncodingReady = false
      beam()
      return
    }

    viewModelScope.launch(Dispatchers.IO) {
      try {
        // Step 1: Visual processing data animation
        viewModelScope.launch(Dispatchers.Main) {
          isProcessingData = true
          isEncodingReady = false
        }

        // Step 2: Encode high-definition JPEG photo payload
        val payload = preparePayloadForImage(bmp)

        delay(600.milliseconds) // Simulated processing animation

        val samples = MicrochirpAtoms.encodeBytes(payload)

        viewModelScope.launch(Dispatchers.Main) {
          isProcessingData = false
          cachedSamples = samples
          payloadSize = payload.size
          isEncodingReady = true // Flashy Red button ready!
        }
      } catch (e: Exception) {
        e.printStackTrace()
        viewModelScope.launch(Dispatchers.Main) {
          isProcessingData = false
          isEncodingReady = false
        }
      }
    }
  }

  private fun preparePayloadForImage(bitmap: Bitmap): ByteArray {
    try {
      var scaleDim = 128
      while (scaleDim >= 40) {
        val scale = minOf(scaleDim.toFloat() / bitmap.width, scaleDim.toFloat() / bitmap.height, 1f)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 42, bos)
        val bytes = bos.toByteArray()
        if (bytes.size <= 600) {
          return bytes
        }
        scaleDim -= 8
      }
      val bos = ByteArrayOutputStream()
      val scaled = Bitmap.createScaledBitmap(bitmap, 40, 40, true)
      scaled.compress(Bitmap.CompressFormat.JPEG, 35, bos)
      val bytes = bos.toByteArray()
      return if (bytes.size > MicrochirpAtoms.MAX_PAYLOAD) bytes.copyOf(MicrochirpAtoms.MAX_PAYLOAD) else bytes
    } catch (e: Exception) {
      e.printStackTrace()
      return "IMG".toByteArray()
    }
  }

  fun setFile(context: Context, uri: Uri, onReady: (() -> Unit)? = null) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        var name = uri.lastPathSegment ?: "file"
        context.contentResolver.query(uri, null, null, null, null)?.use {
          if (it.moveToFirst()) {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) name = it.getString(idx)
          }
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch

        val payload = preparePayloadForFile(bytes, name)
        val samples = MicrochirpAtoms.encodeBytes(payload)

        viewModelScope.launch(Dispatchers.Main) {
          noteText = ""
          attachedFileName = name
          attachedFileSize = bytes.size
          payloadSize = payload.size
          cachedSamples = samples
          beamFinished = false
          isEncodingReady = true
          // The payload is cached now, so it is safe to transmit.
          onReady?.invoke()
        }
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
  }

  private fun preparePayloadForFile(bytes: ByteArray, name: String): ByteArray {
    if (bytes.size <= MicrochirpAtoms.MAX_PAYLOAD) return bytes
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext in listOf("png", "jpg", "jpeg", "webp", "gif", "bmp")) {
      try {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap != null) {
          return preparePayloadForImage(bitmap)
        }
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
    try {
      val bos = ByteArrayOutputStream()
      GZIPOutputStream(bos).use { it.write(bytes) }
      val gzipped = bos.toByteArray()
      if (gzipped.size <= MicrochirpAtoms.MAX_PAYLOAD) return gzipped
    } catch (_: Exception) {}
    return bytes.copyOf(MicrochirpAtoms.MAX_PAYLOAD)
  }

  fun beam() {
    val samples = cachedSamples ?: return
    if (isBeaming) return
    isBeaming = true
    beamFinished = false
    beamProgress = 0f

    playCue(ToneGenerator.TONE_PROP_BEEP)

    viewModelScope.launch(Dispatchers.IO) {
      val durationMs = (samples.size.toFloat() / MicrochirpAtoms.SAMPLE_RATE * 1000f).toLong().coerceAtLeast(500L)
      val startTime = System.currentTimeMillis()

      val progressJob = launch {
        while (isBeaming) {
          val elapsed = System.currentTimeMillis() - startTime
          beamProgress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
          delay(16.milliseconds)
        }
      }

      player.play(samples) {
        viewModelScope.launch(Dispatchers.Main) {
          isBeaming = false
          beamProgress = 1f
          beamFinished = true
          progressJob.cancel()
          playCue(ToneGenerator.TONE_PROP_ACK)
          delay(3000.milliseconds)
          beamFinished = false
        }
      }
    }
  }

  fun replayAudio() {
    beam()
  }

  // --- MODULAR CATCH FUNCTIONS ---

  fun setVisualListeningActive(active: Boolean) {
    isListeningUI = active
    if (!active) {
      micAmplitude = 0f
    }
  }

  fun startCatchPipeline(hasRecordPermission: Boolean) {
    setVisualListeningActive(true)
    isSignalCaught = false
    receivedText = ""
    receivedBitmap = null
    decodeQueue.clear()

    if (!hasRecordPermission) {
      return
    }

    startMicRecording()
    startSignalDecoder()
  }

  fun stopCatchPipeline() {
    decodeJob?.cancel()
    decodeJob = null
    decodeQueue.clear()
    stopMicRecording()
    setVisualListeningActive(false)
  }

  private fun startMicRecording() {
    micJob?.cancel()
    micJob = viewModelScope.launch(Dispatchers.Default) {
      recorder.startStreaming { chunk ->
        // Hand every chunk straight to the streaming decoder. This is what
        // makes decoding automatic: no snapshot, and no need to stop.
        if (!decodeQueue.offer(chunk)) {
          decodeQueue.poll()
          decodeQueue.offer(chunk)
        }

        var sum = 0f
        for (v in chunk) {
          sum += v * v
        }
        val rms = kotlin.math.sqrt(sum / chunk.size)
        val targetAmp = (rms * 6f).coerceIn(0.15f, 1f)
        viewModelScope.launch(Dispatchers.Main.immediate) {
          if (isListeningUI) {
            micAmplitude = targetAmp
          }
        }
      }

      viewModelScope.launch(Dispatchers.Main) {
        delay(250.milliseconds)
        if (isListeningUI && !recorder.isRecording()) {
          stopCatchPipeline()
        }
      }
    }
  }

  private fun stopMicRecording() {
    micJob?.cancel()
    micJob = null
    recorder.stop()
  }

  private fun startSignalDecoder() {
    decodeJob?.cancel()
    decodeQueue.clear()
    val decoder = StreamingChirpDecoder()
    decodeJob = viewModelScope.launch(Dispatchers.Default) {
      while (isListeningUI) {
        val chunk = try {
          decodeQueue.poll(120, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
          null
        } ?: continue

        val bytes = try {
          decoder.feed(chunk)
        } catch (e: Exception) {
          e.printStackTrace()
          null
        }

        if (bytes != null && isListeningUI) {
          viewModelScope.launch(Dispatchers.Main.immediate) {
            isSignalCaught = true
            handleReceivedBytes(bytes)
            playDropSound()
          }
          // Continuous by design: the mic and decoder stay armed, so the next
          // drop needs no tap. The signalled state is only a visual pulse.
          viewModelScope.launch(Dispatchers.Main.immediate) {
            delay(1400.milliseconds)
            if (isListeningUI) {
              isSignalCaught = false
            }
          }
        }
      }
    }
  }

  /** Play the user's local drop sound, falling back to the built-in chime. */
  private fun playDropSound() {
    val path = prefs.getString(KEY_DROP_SOUND_PATH, null)
    if (path != null && File(path).exists()) {
      try {
        dropPlayer?.release()
        dropPlayer = MediaPlayer().apply {
          setDataSource(path)
          setOnCompletionListener { mp ->
            try { mp.release() } catch (_: Exception) {}
            if (dropPlayer === mp) dropPlayer = null
          }
          prepare()
          start()
        }
        return
      } catch (e: Exception) {
        e.printStackTrace()
        try { dropPlayer?.release() } catch (_: Exception) {}
        dropPlayer = null
      }
    }
    playCue(ToneGenerator.TONE_PROP_ACK)
  }

  private fun handleReceivedBytes(bytes: ByteArray) {
    lastReceivedBytes = bytes
    var uncompressed = bytes
    if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B) {
      try {
        val gis = GZIPInputStream(ByteArrayInputStream(bytes))
        uncompressed = gis.readBytes()
      } catch (_: Exception) {}
    }

    // 1. Decode standard high-quality 24-bit JPEG/PNG photo drop
    try {
      val bmp = BitmapFactory.decodeByteArray(uncompressed, 0, uncompressed.size)
      if (bmp != null) {
        receivedBitmap = bmp
        receivedText = "[Photo Received]"
        return
      }
    } catch (_: Exception) {}

    // 2. Fallback SPIM Micro-Palette Photo Drop
    try {
      if (uncompressed.size >= 3) {
        val w = uncompressed[0].toInt() and 0xFF
        val h = uncompressed[1].toInt() and 0xFF
        val expectedBytes = 2 + (w * h + 1) / 2
        if (w in 4..16 && h in 4..16 && uncompressed.size == expectedBytes) {
          val colors = IntArray(w * h)
          var byteIdx = 2
          var pixelIdx = 0
          while (pixelIdx < w * h) {
            val b = uncompressed[byteIdx++].toInt() and 0xFF
            val c1 = (b shr 4) and 0x0F
            val c2 = b and 0x0F
            colors[pixelIdx++] = paletteColors[c1]
            if (pixelIdx < w * h) {
              colors[pixelIdx++] = paletteColors[c2]
            }
          }
          val microBmp = Bitmap.createBitmap(colors, w, h, Bitmap.Config.ARGB_8888)
          val displayBmp = Bitmap.createScaledBitmap(microBmp, 200, 200, true)
          receivedBitmap = displayBmp
          receivedText = "[Photo Received]"
          return
        }
      }
    } catch (_: Exception) {}

    receivedBitmap = null
    val isAscii = uncompressed.isNotEmpty() && uncompressed.all { b -> b in 32..126 || b == 10.toByte() || b == 13.toByte() || b == 9.toByte() }
    receivedText = if (isAscii) {
      String(uncompressed, Charsets.UTF_8)
    } else {
      "Binary File (${uncompressed.size} bytes)"
    }
  }

  fun copyToClipboard(context: Context) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("spim", receivedText))
  }

  private fun playCue(tone: Int) {
    try {
      val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
      tg.startTone(tone, 150)
      viewModelScope.launch { delay(250.milliseconds); try { tg.release() } catch (_: Exception) {} }
    } catch (_: Exception) {}
  }
}

// Minimal Dark Palette & Gradients
private val MinimalBlack = Color(0xFF141414)

// Outer Screen Dark Black Gradient
private val ScreenBlackGradient = Brush.verticalGradient(
  colors = listOf(
    Color(0xFF1E1E22), // Soft dark charcoal
    Color(0xFF08080A)  // Deep onyx black
  )
)

// Beam Gradient (Pink to Yellow)
private val BeamZoneGradient = Brush.verticalGradient(
  colors = listOf(
    Color(0xFFFF90E8),
    Color(0xFFFFC700)
  )
)

// Catch Zone Green Gradient
private val CatchZoneGreenGradient = Brush.verticalGradient(
  colors = listOf(
    Color(0xFF32CC6C),
    Color(0xFF1EA850)
  )
)

// --- Motion scale: one easing, a few durations, two springs, used everywhere ---
private object Motion {
  // press and release feedback
  val press = tween<Float>(140, easing = FastOutSlowInEasing)
  // values settling (progress fill, colour flips)
  val settle = tween<Float>(260, easing = FastOutSlowInEasing)
  // screens arriving and leaving
  val screenIn = tween<Float>(300, easing = FastOutSlowInEasing)
  val screenOut = tween<Float>(170, easing = FastOutLinearInEasing)
  // slide distance for screens and banners coming in and going out
  val screenInOffset = tween<IntOffset>(300, easing = FastOutSlowInEasing)
  val screenOutOffset = tween<IntOffset>(170, easing = FastOutLinearInEasing)
  // a slow, shallow breath for the ready-to-beam state
  val breath = tween<Float>(1100, easing = FastOutSlowInEasing)
  // layout shifts (the Beam tile expanding)
  val layout = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMediumLow
  )
  // continuous, physically driven values (mic level)
  val signal = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessLow
  )
}

@Composable
fun SpimApp(viewModel: SpimViewModel = viewModel()) {
  val context = LocalContext.current

  val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      // setFile encodes on a background dispatcher; beam only once the payload is cached,
      // otherwise beam() returns early and the file is never transmitted.
      viewModel.setFile(context, uri) { viewModel.beam() }
    }
  }

  val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    if (uri != null) {
      viewModel.setSelectedImage(context, uri)
    }
  }

  val dropSoundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      viewModel.setDropSound(context, uri)
    }
  }

  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { isGranted ->
    if (isGranted) {
      viewModel.startCatchPipeline(hasRecordPermission = true)
    } else {
      viewModel.stopCatchPipeline()
    }
  }

  SpimAppContent(
    isBeaming = viewModel.isBeaming,
    beamProgress = viewModel.beamProgress,
    beamFinished = viewModel.beamFinished,
    isProcessingData = viewModel.isProcessingData,
    isEncodingReady = viewModel.isEncodingReady,
    selectedImageBitmap = viewModel.selectedImageBitmap,
    attachedFileName = viewModel.attachedFileName,
    isListening = viewModel.isListeningUI,
    isSignalCaught = viewModel.isSignalCaught,
    micAmplitude = viewModel.micAmplitude,
    receivedText = viewModel.receivedText,
    receivedBitmap = viewModel.receivedBitmap,
    onPickImageClick = {
      imageLauncher.launch("image/*")
    },
    onPickFileClick = {
      fileLauncher.launch(arrayOf("*/*"))
    },
    onBeamImageClick = {
      viewModel.beamImage()
    },
    onClearImageClick = {
      viewModel.clearSelectedImage()
    },
    onReplayAudioClick = {
      viewModel.replayAudio()
    },
    onCatchClick = {
      val hasPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
      ) == PackageManager.PERMISSION_GRANTED

      if (viewModel.isListeningUI) {
        viewModel.stopCatchPipeline()
      } else {
        viewModel.startCatchPipeline(hasRecordPermission = hasPermission)
        if (!hasPermission) {
          permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
      }
    },
    onCopyClick = { viewModel.copyToClipboard(context) },
    bandIndex = viewModel.bandIndex,
    keepScreenOn = viewModel.keepScreenOn,
    dropSoundName = viewModel.dropSoundName,
    onChooseDropSound = { dropSoundLauncher.launch(arrayOf("audio/*")) },
    onResetDropSound = { viewModel.clearDropSound() },
    onBandSelected = { viewModel.setBand(it) },
    onKeepScreenOnChange = { viewModel.updateKeepScreenOn(it) },
    onRestoreDefaultSettings = { viewModel.restoreDefaultSettings() }
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpimAppContent(
  isBeaming: Boolean,
  beamProgress: Float,
  beamFinished: Boolean,
  isProcessingData: Boolean = false,
  isEncodingReady: Boolean = false,
  selectedImageBitmap: Bitmap? = null,
  attachedFileName: String?,
  isListening: Boolean,
  isSignalCaught: Boolean = false,
  micAmplitude: Float = 0f,
  receivedText: String,
  receivedBitmap: Bitmap? = null,
  initialBeamExpanded: Boolean = false,
  onPickImageClick: () -> Unit,
  onPickFileClick: () -> Unit,
  onBeamImageClick: () -> Unit = { },
  onClearImageClick: () -> Unit = { },
  onReplayAudioClick: () -> Unit = { },
  onCatchClick: () -> Unit,
  onCopyClick: () -> Unit,
  bandIndex: Int = MicrochirpAtoms.DEFAULT_BAND_INDEX,
  keepScreenOn: Boolean = true,
  dropSoundName: String? = null,
  onChooseDropSound: () -> Unit = { },
  onResetDropSound: () -> Unit = { },
  onBandSelected: (Int) -> Unit = { },
  onKeepScreenOnChange: (Boolean) -> Unit = { },
  onRestoreDefaultSettings: () -> Unit = { }
) {
  var isBeamExpanded by remember { mutableStateOf(initialBeamExpanded) }
  var showSettings by remember { mutableStateOf(false) }
  // Held on to so the preview's exit animation still has a frame to fade out
  var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
  if (selectedImageBitmap != null) previewBitmap = selectedImageBitmap

  // Ready-to-beam: a slow, shallow breath instead of a strobe
  val infiniteTransition = rememberInfiniteTransition(label = "readyPulse")
  val redPulseScale by infiniteTransition.animateFloat(
    initialValue = 1f,
    targetValue = 1.02f,
    animationSpec = infiniteRepeatable(
      animation = Motion.breath,
      repeatMode = RepeatMode.Reverse
    ),
    label = "redPulse"
  )

  val expansionProgress by animateFloatAsState(
    targetValue = if (isBeamExpanded) 1f else 0f,
    animationSpec = Motion.layout,
    label = "beamExpansion"
  )

  val animatedAmp by animateFloatAsState(
    targetValue = if (isListening) micAmplitude.coerceIn(0.15f, 1f) else 1f,
    animationSpec = Motion.signal,
    label = "micFill"
  )

  // Hero tiles: a small press-and-settle so taps feel physical
  val beamPressSource = remember { MutableInteractionSource() }
  val beamPressed by beamPressSource.collectIsPressedAsState()
  val beamPressScale by animateFloatAsState(
    targetValue = if (beamPressed) 0.985f else 1f,
    animationSpec = Motion.press,
    label = "beamPress"
  )

  val catchPressSource = remember { MutableInteractionSource() }
  val catchPressed by catchPressSource.collectIsPressedAsState()
  val catchPressScale by animateFloatAsState(
    targetValue = if (catchPressed) 0.985f else 1f,
    animationSpec = Motion.press,
    label = "catchPress"
  )

  // The gear turns as Settings opens, so the header acknowledges the tap
  val gearRotation by animateFloatAsState(
    targetValue = if (showSettings) 90f else 0f,
    animationSpec = Motion.settle,
    label = "gearRotation"
  )

  val catchTextColor = if (isListening) Color.White else MinimalBlack
  val catchSubtextColor = if (isListening) Color.White.copy(alpha = 0.85f) else MinimalBlack.copy(alpha = 0.7f)
  val catchIconBg = if (isListening) Color.White else MinimalBlack
  val catchIconTint = if (isListening) MinimalBlack else Color.White

  MaterialTheme(
    colorScheme = darkColorScheme(
      background = Color.Black,
      surface = Color.Black,
      primary = Color.White,
      secondary = Color.White,
      onBackground = Color.White,
      onSurface = Color.White
    )
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(ScreenBlackGradient)
    ) {
      Scaffold(
        containerColor = Color.Transparent
      ) { padding ->
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          // Branding row: centred wordmark with the settings gear pinned to the end
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = "spim",
              fontSize = 44.sp,
              fontWeight = FontWeight.Black,
              color = Color.White,
              letterSpacing = (-2).sp,
              textAlign = TextAlign.Center
            )
            IconButton(
              onClick = { showSettings = true },
              modifier = Modifier.align(Alignment.CenterEnd)
            ) {
              Icon(
                Icons.Default.Settings,
                contentDescription = "Settings",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.rotate(gearRotation)
              )
            }
          }

          // Main Interactive Area: Beam Tile Expands Right & Pushes Catch Tile Offscreen
          BoxWithConstraints(
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f)
          ) {
            val totalWidth = this.maxWidth
            val spacing = 16.dp

            val beamWidth = (totalWidth - spacing) / 2 + ((totalWidth - ((totalWidth - spacing) / 2)) * expansionProgress)
            val catchWidth = (totalWidth - spacing) / 2
            val catchTranslationX = (totalWidth / 2 + spacing) * expansionProgress

            Box(modifier = Modifier.fillMaxSize()) {
              // 1. BEAM TILE
              Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.14f)),
                modifier = Modifier
                  .width(beamWidth)
                  .fillMaxHeight()
                  .graphicsLayer {
                    scaleX = beamPressScale
                    scaleY = beamPressScale
                  }
                  .clip(RoundedCornerShape(24.dp))
                  .background(BeamZoneGradient)
                  .clickable(
                    interactionSource = beamPressSource,
                    indication = LocalIndication.current,
                    enabled = !isBeamExpanded
                  ) {
                    isBeamExpanded = true
                  }
              ) {
                if (expansionProgress < 0.75f) {
                  // Collapsed Beam Content
                  Column(
                    modifier = Modifier
                      .fillMaxSize()
                      .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                  ) {
                    Box(
                      modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MinimalBlack),
                      contentAlignment = Alignment.Center
                    ) {
                      Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Beam",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                      )
                    }

                    Spacer(Modifier.height(20.dp))

                    Text(
                      text = "Beam",
                      fontSize = 28.sp,
                      fontWeight = FontWeight.Black,
                      color = MinimalBlack,
                      textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(8.dp))

                    val beamStatus = when {
                      beamFinished -> "Complete ✓"
                      isBeaming -> "${(beamProgress * 100).toInt()}%"
                      attachedFileName != null -> attachedFileName
                      else -> "Tap to send"
                    }
                    Text(
                      text = beamStatus,
                      fontSize = 13.sp,
                      fontWeight = FontWeight.Bold,
                      color = MinimalBlack.copy(alpha = 0.7f),
                      textAlign = TextAlign.Center
                    )
                  }
                } else {
                  // Expanded Beam Content (Image / File options)
                  Column(
                    modifier = Modifier
                      .fillMaxSize()
                      .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                  ) {
                    Text(
                      text = "Beam",
                      fontSize = 28.sp,
                      fontWeight = FontWeight.Black,
                      color = MinimalBlack,
                      modifier = Modifier.padding(top = 8.dp)
                    )

                    Row(
                      modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 16.dp),
                      horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                      // Option 1: Image (Gallery)
                      Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MinimalBlack),
                        modifier = Modifier
                          .weight(1f)
                          .fillMaxHeight()
                          .clickable {
                            isBeamExpanded = false
                            onPickImageClick()
                          }
                      ) {
                        Column(
                          modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                          horizontalAlignment = Alignment.CenterHorizontally,
                          verticalArrangement = Arrangement.Center
                        ) {
                          Box(
                            modifier = Modifier
                              .size(56.dp)
                              .clip(CircleShape)
                              .background(Color.White.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                          ) {
                            Icon(
                            Icons.Default.Image,
                            contentDescription = "Image",
                              tint = Color.White,
                              modifier = Modifier.size(28.dp)
                            )
                          }
                          Spacer(Modifier.height(16.dp))
                          Text(
                            text = "Image",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                          )
                          Text(
                            text = "Gallery",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.7f)
                          )
                        }
                      }

                      // Option 2: File (Recent Files)
                      Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MinimalBlack),
                        modifier = Modifier
                          .weight(1f)
                          .fillMaxHeight()
                          .clickable {
                            isBeamExpanded = false
                            onPickFileClick()
                          }
                      ) {
                        Column(
                          modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                          horizontalAlignment = Alignment.CenterHorizontally,
                          verticalArrangement = Arrangement.Center
                        ) {
                          Box(
                            modifier = Modifier
                              .size(56.dp)
                              .clip(CircleShape)
                              .background(Color.White.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                          ) {
                            Icon(
                            Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = "File",
                              tint = Color.White,
                              modifier = Modifier.size(28.dp)
                            )
                          }
                          Spacer(Modifier.height(16.dp))
                          Text(
                            text = "File",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                          )
                          Text(
                            text = "Recent files",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.7f)
                          )
                        }
                      }
                    }

                    // Close (X) Icon
                    Box(
                      modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MinimalBlack)
                        .clickable {
                          isBeamExpanded = false
                        },
                      contentAlignment = Alignment.Center
                    ) {
                      Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                      )
                    }
                  }
                }
              }

              // 2. CATCH TILE
              Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                modifier = Modifier
                  .align(Alignment.TopEnd)
                  .graphicsLayer {
                    translationX = catchTranslationX.toPx()
                    scaleX = catchPressScale
                    scaleY = catchPressScale
                  }
                  .width(catchWidth)
                  .fillMaxHeight()
                  .clip(RoundedCornerShape(24.dp))
                  .background(if (isListening) Color(0xFF101012) else Color.Transparent)
                  .clickable(
                    interactionSource = catchPressSource,
                    indication = LocalIndication.current,
                    enabled = !isBeamExpanded
                  ) { onCatchClick() }
              ) {
                Box(modifier = Modifier.fillMaxSize()) {
                  Box(
                    modifier = Modifier
                      .fillMaxWidth()
                      .fillMaxHeight(animatedAmp)
                      .align(Alignment.BottomCenter)
                      .background(CatchZoneGreenGradient)
                  )

                  Column(
                    modifier = Modifier
                      .fillMaxSize()
                      .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                  ) {
                    Box(
                      modifier = Modifier
                        .size(64.dp)
                        .scale(if (isListening) 1f + (animatedAmp * 0.15f) else 1f)
                        .clip(CircleShape)
                        .background(catchIconBg)
                        .border(1.5.dp, if (isListening) MinimalBlack else Color.Transparent, CircleShape),
                      contentAlignment = Alignment.Center
                    ) {
                      Icon(
                        Icons.Default.Mic,
                        contentDescription = "Catch",
                        tint = catchIconTint,
                        modifier = Modifier.size(32.dp)
                      )
                    }

                    Spacer(Modifier.height(20.dp))

                    Text(
                      text = "Catch",
                      fontSize = 28.sp,
                      fontWeight = FontWeight.Black,
                      color = catchTextColor,
                      textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(8.dp))

                    val catchStatusLabel = when {
                      isSignalCaught -> "Signal caught ✓"
                      isListening -> "Listening..."
                      else -> "Tap to listen"
                    }

                    Text(
                      text = catchStatusLabel,
                      fontSize = 13.sp,
                      fontWeight = FontWeight.Bold,
                      color = catchSubtextColor,
                      textAlign = TextAlign.Center
                    )
                  }
                }
              }
            }
          }

          // Received payload banner
          AnimatedVisibility(
            visible = receivedBitmap != null || receivedText.isNotEmpty(),
            enter = fadeIn(Motion.screenIn) + slideInVertically(Motion.screenInOffset) { it / 8 },
            exit = fadeOut(Motion.screenOut) + slideOutVertically(Motion.screenOutOffset) { it / 8 }
          ) {
          if (receivedBitmap != null) {
            Spacer(Modifier.height(16.dp))
            Card(
              shape = RoundedCornerShape(20.dp),
              colors = CardDefaults.cardColors(containerColor = Color.White),
              border = BorderStroke(1.dp, Color(0xFF32CC6C)),
              modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clickable { onCopyClick() }
            ) {
              Column(
                modifier = Modifier
                  .fillMaxSize()
                  .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
              ) {
                Text(
                  text = "Received photo",
                  color = MinimalBlack,
                  fontSize = 15.sp,
                  fontWeight = FontWeight.Black,
                  modifier = Modifier.padding(bottom = 8.dp)
                )
                Image(
                  bitmap = receivedBitmap.asImageBitmap(),
                  contentDescription = "Received Drop",
                  contentScale = ContentScale.Fit,
                  modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                )
              }
            }
          } else if (receivedText.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Surface(
              shape = RoundedCornerShape(16.dp),
              color = Color.White,
              modifier = Modifier
                .fillMaxWidth()
                .clickable { onCopyClick() }
            ) {
              Text(
                text = "Received: $receivedText (Tap to copy)",
                color = MinimalBlack,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
              )
            }
          }
          }
        }
      }

      // OVERLAY 1: Image Preview Screen (Shown when image is selected from Gallery)
      AnimatedVisibility(
        visible = selectedImageBitmap != null,
        enter = fadeIn(Motion.screenIn) + slideInVertically(Motion.screenInOffset) { it / 12 },
        exit = fadeOut(Motion.screenOut) + slideOutVertically(Motion.screenOutOffset) { it / 12 }
      ) {
        val bmp = previewBitmap
        if (bmp != null) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(ScreenBlackGradient)
            .padding(16.dp)
        ) {
          Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
          ) {
            // Header with Close
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "spim",
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                color = Color.White
              )
              Box(
                modifier = Modifier
                  .size(40.dp)
                  .clip(CircleShape)
                  .background(Color.White.copy(alpha = 0.15f))
                  .clickable { onClearImageClick() },
                contentAlignment = Alignment.Center
              ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
              }
            }

            // Image Preview Covering the Screen Area
            Card(
              shape = RoundedCornerShape(24.dp),
              border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
              colors = CardDefaults.cardColors(containerColor = MinimalBlack),
              modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 12.dp)
            ) {
              Box(modifier = Modifier.fillMaxSize()) {
                Image(
                  bitmap = bmp.asImageBitmap(),
                  contentDescription = "Selected Image",
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.fillMaxSize()
                )
              }
            }

            // Just ONE Option Under It: BEAM (Becomes Flashy Red after encoding!)
            val liquidFillLevel by animateFloatAsState(
              targetValue = when {
                beamFinished -> 1f
                isBeaming -> beamProgress.coerceIn(0.08f, 1f)
                isProcessingData -> 0.25f
                else -> 0f
              },
              animationSpec = tween(durationMillis = 100, easing = LinearEasing),
              label = "beamJarWaterFill"
            )

            val isFlashyRed = isEncodingReady || (isBeaming && beamProgress > 0f)
            val buttonBgBrush = if (isFlashyRed && !isBeaming) {
              Brush.horizontalGradient(listOf(Color(0xFFFF2D55), Color(0xFFFF3B30)))
            } else {
              Brush.horizontalGradient(listOf(Color(0xFF141414), Color(0xFF141414)))
            }

            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.fillMaxWidth()
            ) {
              Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(1.5.dp, if (isFlashyRed) Color(0xFFFF2D55) else Color.White.copy(alpha = 0.5f)),
                modifier = Modifier
                  .fillMaxWidth()
                  .height(64.dp)
                  .scale(if (isFlashyRed && !isBeaming) redPulseScale else 1f)
                  .clip(RoundedCornerShape(20.dp))
                  .background(buttonBgBrush)
                  .clickable(enabled = !isProcessingData && !isBeaming) {
                    onBeamImageClick()
                  }
              ) {
                Box(modifier = Modifier.fillMaxSize()) {
                  // Liquid Water Fill Level rising from bottom to top!
                  if (liquidFillLevel > 0f && !isFlashyRed) {
                    Box(
                      modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(liquidFillLevel)
                        .align(Alignment.BottomCenter)
                        .background(BeamZoneGradient)
                    )
                  }

                  Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                  ) {
                    val buttonText = when {
                      beamFinished -> "Beam complete ✓"
                      isBeaming -> "Sending ${(beamProgress * 100).toInt()}%"
                      isProcessingData -> "Processing"
                      isFlashyRed -> "Beam now"
                      else -> "Beam"
                    }
                    val textColor = Color.White

                    Text(
                      text = buttonText,
                      fontSize = 20.sp,
                      fontWeight = FontWeight.Black,
                      color = textColor
                    )
                  }
                }
              }

              // Trigger 3: Showcase round retry arrow icon button to replay sound if someone missed
              if (beamFinished || isBeaming || isFlashyRed) {
                Spacer(Modifier.height(12.dp))
                Box(
                  modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, Color(0xFFFF2D55), CircleShape)
                    .clickable {
                      onReplayAudioClick()
                    },
                  contentAlignment = Alignment.Center
                ) {
                  Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Replay Sound",
                    tint = Color(0xFFFF2D55),
                    modifier = Modifier.size(28.dp)
                  )
                }
              }
            }
          }
        }
      }
      }

      // OVERLAY 2: Settings, opened from the gear in the header
      AnimatedVisibility(
        visible = showSettings,
        enter = fadeIn(Motion.screenIn) + slideInVertically(Motion.screenInOffset) { it / 12 },
        exit = fadeOut(Motion.screenOut) + slideOutVertically(Motion.screenOutOffset) { it / 12 }
      ) {
        SettingsScreen(
          bandIndex = bandIndex,
          keepScreenOn = keepScreenOn,
          dropSoundName = dropSoundName,
          onChooseDropSound = onChooseDropSound,
          onResetDropSound = onResetDropSound,
          onBandSelected = onBandSelected,
          onKeepScreenOnChange = onKeepScreenOnChange,
          onRestoreDefaultSettings = onRestoreDefaultSettings,
          onClose = { showSettings = false }
        )
      }
    }
  }
}

@Preview
@Composable
fun SpimAppPreview() {
  SpimAppContent(
    isBeaming = false,
    beamProgress = 0f,
    beamFinished = false,
    attachedFileName = null,
    isListening = false,
    micAmplitude = 0.4f,
    receivedText = "",
    onPickImageClick = { },
    onPickFileClick = { },
    onCatchClick = { },
    onCopyClick = { }
  )
}

@Preview
@Composable
fun SpimAppExpandedPreview() {
  SpimAppContent(
    isBeaming = false,
    beamProgress = 0f,
    beamFinished = false,
    attachedFileName = null,
    isListening = false,
    micAmplitude = 0.4f,
    receivedText = "",
    initialBeamExpanded = true,
    onPickImageClick = { },
    onPickFileClick = { },
    onCatchClick = { },
    onCopyClick = { }
  )
}

@Composable
private fun SettingsSectionTitle(text: String) {
  Text(
    text = text.uppercase(),
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 1.sp,
    color = Color.White.copy(alpha = 0.5f),
    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp, start = 4.dp)
  )
}

@Composable
fun SettingsScreen(
  bandIndex: Int,
  keepScreenOn: Boolean,
  dropSoundName: String? = null,
  onChooseDropSound: () -> Unit = { },
  onResetDropSound: () -> Unit = { },
  onBandSelected: (Int) -> Unit,
  onKeepScreenOnChange: (Boolean) -> Unit,
  onRestoreDefaultSettings: () -> Unit,
  onClose: () -> Unit
) {
  val context = LocalContext.current
  val activity = context as? Activity
  val hasMicPermission = ContextCompat.checkSelfPermission(
    context,
    Manifest.permission.RECORD_AUDIO
  ) == PackageManager.PERMISSION_GRANTED
  val versionLabel = remember(context) {
    try {
      val info = context.packageManager.getPackageInfo(context.packageName, 0)
      "${info.versionName} (${info.longVersionCode})"
    } catch (e: Exception) {
      "unknown"
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(ScreenBlackGradient)
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)
        .verticalScroll(rememberScrollState())
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        IconButton(onClick = onClose) {
          Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = Color.White
          )
        }
        Spacer(Modifier.width(8.dp))
        Text(
          text = "Settings",
          fontSize = 24.sp,
          fontWeight = FontWeight.Bold,
          color = Color.White
        )
      }

      SettingsSectionTitle("Signal")

      MicrochirpAtoms.BANDS.forEachIndexed { index, band ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { onBandSelected(index) }
            .padding(vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          RadioButton(
            selected = index == bandIndex,
            onClick = { onBandSelected(index) },
            colors = RadioButtonDefaults.colors(
              selectedColor = Color.White,
              unselectedColor = Color.Gray
            )
          )
          Spacer(Modifier.width(4.dp))
          Column {
            Text(
              text = band.name,
              fontSize = 15.sp,
              fontWeight = FontWeight.SemiBold,
              color = Color.White
            )
            Text(
              text = band.detail,
              fontSize = 12.sp,
              color = Color.White.copy(alpha = 0.6f)
            )
          }
        }
      }

      Text(
        text = "Both devices must be on the same band to decode each other.",
        fontSize = 12.sp,
        color = Color.White.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 48.dp, top = 4.dp)
      )

      SettingsSectionTitle("Display")

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Keep screen on",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
          )
          Text(
            text = "Handy during a long beam or while catching",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.6f)
          )
        }
        Switch(
          checked = keepScreenOn,
          onCheckedChange = { enabled ->
            onKeepScreenOnChange(enabled)
            activity?.let {
              if (enabled) {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
              } else {
                it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
              }
            }
          },
          colors = SwitchDefaults.colors(
            checkedTrackColor = Color(0xFF32CC6C),
            checkedThumbColor = Color.White,
            uncheckedTrackColor = Color(0xFF3A3A3E),
            uncheckedThumbColor = Color.White
          )
        )
      }

      SettingsSectionTitle("App")

      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 4.dp)
      ) {
        Text(
          text = "Drop sound",
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
          color = Color.White
        )
        Text(
          text = dropSoundName ?: "Built-in chime",
          fontSize = 12.sp,
          color = Color.White.copy(alpha = 0.6f),
          modifier = Modifier.padding(top = 2.dp)
        )
        Row(
          modifier = Modifier.padding(top = 10.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          OutlinedButton(
            onClick = onChooseDropSound,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
          ) {
            Text("Choose audio file", fontSize = 13.sp)
          }
          if (dropSoundName != null) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onResetDropSound) {
              Text("Reset", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
            }
          }
        }
        Text(
          text = "Plays from this device when a drop is caught. Nothing is uploaded.",
          fontSize = 12.sp,
          color = Color.White.copy(alpha = 0.45f),
          modifier = Modifier.padding(top = 6.dp)
        )
      }

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Microphone",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
          )
          Text(
            text = if (hasMicPermission) {
              "Allowed"
            } else {
              "Blocked - catching signals needs it"
            },
            fontSize = 12.sp,
            color = if (hasMicPermission) {
              Color(0xFF32CC6C)
            } else {
              Color(0xFFFF6B6B)
            }
          )
        }
        if (!hasMicPermission) {
          TextButton(onClick = {
            context.startActivity(
              Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
              )
            )
          }) {
            Text("Open", fontWeight = FontWeight.Bold)
          }
        }
      }

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text(
            text = "Version",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
          )
          Text(
            text = versionLabel,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.6f)
          )
        }
      }

      Spacer(Modifier.height(28.dp))

      TextButton(
        onClick = onRestoreDefaultSettings,
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(
          text = "Restore defaults",
          fontWeight = FontWeight.Bold,
          color = Color.White.copy(alpha = 0.7f)
        )
      }

      Spacer(Modifier.height(24.dp))
    }
  }
}

@Preview
@Composable
fun SpimAppImagePreview() {
  val bmp = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
  SpimAppContent(
    isBeaming = false,
    beamProgress = 0f,
    beamFinished = false,
    isEncodingReady = true,
    selectedImageBitmap = bmp,
    attachedFileName = null,
    isListening = false,
    micAmplitude = 0f,
    receivedText = "",
    onPickImageClick = { },
    onPickFileClick = { },
    onCatchClick = { },
    onCopyClick = { }
  )
}
