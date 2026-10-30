package com.prima.spimchirp.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.prima.spimchirp.audio.ChirpPlayer
import com.prima.spimchirp.audio.ChirpRecorder
import com.prima.spimchirp.not_as.MicrochirpAtoms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.time.Duration.Companion.milliseconds

class SpimViewModel : ViewModel() {
  private val player = ChirpPlayer()
  private val recorder = ChirpRecorder()

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

  fun setFile(context: Context, uri: Uri) {
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

    if (!hasRecordPermission) {
      return
    }

    startMicRecording()
    startSignalDecoder()
  }

  fun stopCatchPipeline() {
    decodeJob?.cancel()
    decodeJob = null
    stopMicRecording()
    setVisualListeningActive(false)
  }

  private fun startMicRecording() {
    micJob?.cancel()
    micJob = viewModelScope.launch(Dispatchers.Default) {
      recorder.startStreaming { chunk ->
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
    decodeJob = viewModelScope.launch(Dispatchers.IO) {
      while (isListeningUI) {
        if (recorder.isRecording()) {
          val snap = recorder.getSnapshot()
          if (snap.size > MicrochirpAtoms.SYMBOL_SAMPLES * (MicrochirpAtoms.PREAMBLE_LEN + 16)) {
            val tunedPresent = MicrochirpAtoms.isTunedSignalPresent(snap)
            if (tunedPresent) {
              viewModelScope.launch(Dispatchers.Main.immediate) {
                isSignalCaught = true
              }
            }

            val res = MicrochirpAtoms.decodeSamples(snap)
            res.onSuccess { bytes ->
              if (isListeningUI) {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                  isSignalCaught = true
                  delay(200.milliseconds)
                  stopCatchPipeline()
                  handleReceivedBytes(bytes)
                  playCue(ToneGenerator.TONE_PROP_ACK)
                }
                return@launch
              }
            }
          }
        }
        delay(100.milliseconds)
      }
    }
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

@Composable
fun SpimApp(viewModel: SpimViewModel = viewModel()) {
  val context = LocalContext.current

  val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      viewModel.setFile(context, uri)
      viewModel.beam()
    }
  }

  val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    if (uri != null) {
      viewModel.setSelectedImage(context, uri)
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
    onCopyClick = { viewModel.copyToClipboard(context) }
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
  onCopyClick: () -> Unit
) {
  var isBeamExpanded by remember { mutableStateOf(initialBeamExpanded) }
  var showSettingsMenu by remember { mutableStateOf(false) }

  // Flashy Red Pulsing Animation when encoding is ready
  val infiniteTransition = rememberInfiniteTransition(label = "flashyRed")
  val redPulseScale by infiniteTransition.animateFloat(
    initialValue = 1f,
    targetValue = 1.05f,
    animationSpec = infiniteRepeatable(
      animation = tween(500, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "redPulse"
  )

  // Giant Red Explosion Animation State turning screen red
  var isExploding by remember { mutableStateOf(false) }
  val explosionScale by animateFloatAsState(
    targetValue = if (isExploding) 8f else 0f,
    animationSpec = tween(durationMillis = 600, easing = FastOutLinearInEasing),
    label = "explosionScale"
  )
  val explosionAlpha by animateFloatAsState(
    targetValue = if (isExploding) 0.85f else 0f,
    animationSpec = tween(durationMillis = 600, easing = LinearEasing),
    label = "explosionAlpha"
  )

  LaunchedEffect(isExploding) {
    if (isExploding) {
      delay(700.milliseconds)
      isExploding = false
    }
  }

  val expansionProgress by animateFloatAsState(
    targetValue = if (isBeamExpanded) 1f else 0f,
    animationSpec = spring(
      dampingRatio = Spring.DampingRatioLowBouncy,
      stiffness = Spring.StiffnessLow
    ),
    label = "beamExpansion"
  )

  val animatedAmp by animateFloatAsState(
    targetValue = if (isListening) micAmplitude.coerceIn(0.15f, 1f) else 1f,
    animationSpec = spring(
      dampingRatio = Spring.DampingRatioLowBouncy,
      stiffness = Spring.StiffnessLow
    ),
    label = "micFill"
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
          // Centered WHITE "spim" branding on top
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 24.dp),
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
                border = BorderStroke(2.5.dp, Color.White.copy(alpha = 0.9f)),
                modifier = Modifier
                  .width(beamWidth)
                  .fillMaxHeight()
                  .clip(RoundedCornerShape(24.dp))
                  .background(BeamZoneGradient)
                  .clickable(enabled = !isBeamExpanded) {
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
                              Icons.Default.Add,
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
                              Icons.AutoMirrored.Filled.List,
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
                border = BorderStroke(2.5.dp, Color.White.copy(alpha = 0.9f)),
                modifier = Modifier
                  .align(Alignment.TopEnd)
                  .graphicsLayer {
                    translationX = catchTranslationX.toPx()
                  }
                  .width(catchWidth)
                  .fillMaxHeight()
                  .clip(RoundedCornerShape(24.dp))
                  .background(if (isListening) Color(0xFF101012) else Color.Transparent)
                  .clickable(enabled = !isBeamExpanded) { onCatchClick() }
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
                        Icons.Default.Notifications,
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
                      isSignalCaught -> "Signal Caught! ✓"
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

          // Gesture Control: Scroll/Drag Up Handle at Bottom for Hidden Settings Menu
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(36.dp)
              .padding(top = 12.dp)
              .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                  if (dragAmount < -15f) {
                    showSettingsMenu = true
                  }
                }
              }
              .clickable { showSettingsMenu = true },
            contentAlignment = Alignment.Center
          ) {
            Box(
              modifier = Modifier
                .width(48.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.35f))
            )
          }

          // Received Image Drop Banner
          if (receivedBitmap != null) {
            Spacer(Modifier.height(16.dp))
            Card(
              shape = RoundedCornerShape(20.dp),
              colors = CardDefaults.cardColors(containerColor = Color.White),
              border = BorderStroke(2.5.dp, Color(0xFF32CC6C)),
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
                  text = "Received Photo Drop 🎉",
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

      // OVERLAY 1: Image Preview Screen (Shown when image is selected from Gallery)
      if (selectedImageBitmap != null) {
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
              border = BorderStroke(2.5.dp, Color.White),
              colors = CardDefaults.cardColors(containerColor = MinimalBlack),
              modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 12.dp)
            ) {
              Box(modifier = Modifier.fillMaxSize()) {
                Image(
                  bitmap = selectedImageBitmap.asImageBitmap(),
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
                border = BorderStroke(2.5.dp, if (isFlashyRed) Color(0xFFFF2D55) else Color.White),
                modifier = Modifier
                  .fillMaxWidth()
                  .height(64.dp)
                  .scale(if (isFlashyRed && !isBeaming) redPulseScale else 1f)
                  .clip(RoundedCornerShape(20.dp))
                  .background(buttonBgBrush)
                  .clickable(enabled = !isProcessingData && !isBeaming) {
                    if (isFlashyRed) {
                      // Trigger 1: Giant Red Explosion
                      isExploding = true
                      // Trigger 2: Play audio sound
                      onBeamImageClick()
                    } else {
                      // Start processing / encoding
                      onBeamImageClick()
                    }
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
                      beamFinished -> "Beam Complete ✓"
                      isBeaming -> "Beaming (${(beamProgress * 100).toInt()}%)..."
                      isProcessingData -> "Processing Data..."
                      isFlashyRed -> "BEAM NOW 💥"
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
                    Icons.Default.Refresh,
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

      // Giant Red Explosion Animation Overlay turning screen red when Beam button is clicked!
      if (explosionScale > 0f) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
              scaleX = explosionScale
              scaleY = explosionScale
              alpha = explosionAlpha
            }
            .clip(CircleShape)
            .background(Color(0xFFFF2D55))
        )
      }

      // OVERLAY 2: Hidden Settings Bottom Sheet (Gesture Trigger: Scroll Up from Bottom)
      if (showSettingsMenu) {
        ModalBottomSheet(
          onDismissRequest = { showSettingsMenu = false },
          containerColor = Color(0xFF18181A),
          contentColor = Color.White
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(24.dp)
          ) {
            Text(
              text = "Settings",
              fontSize = 24.sp,
              fontWeight = FontWeight.Black,
              color = Color.White
            )

            Spacer(Modifier.height(20.dp))

            Text(
              text = "Audio Selection",
              fontSize = 16.sp,
              fontWeight = FontWeight.Bold,
              color = Color.White.copy(alpha = 0.9f)
            )

            Spacer(Modifier.height(12.dp))

            var selectedAudioOption by remember { mutableStateOf("Ultrasonic Chirp (18.5 - 19.5 kHz)") }
            val options = listOf(
              "Ultrasonic Chirp (18.5 - 19.5 kHz)",
              "Near-Ultrasonic (17.5 - 18.5 kHz)",
              "Audible Chirp (15 - 16 kHz)"
            )

            options.forEach { option ->
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable { selectedAudioOption = option }
                  .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                RadioButton(
                  selected = (option == selectedAudioOption),
                  onClick = { selectedAudioOption = option },
                  colors = RadioButtonDefaults.colors(selectedColor = Color.White, unselectedColor = Color.Gray)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                  text = option,
                  fontSize = 14.sp,
                  fontWeight = FontWeight.Medium,
                  color = Color.White
                )
              }
            }

            Spacer(Modifier.height(24.dp))

            Button(
              onClick = { showSettingsMenu = false },
              modifier = Modifier.fillMaxWidth(),
              colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = MinimalBlack)
            ) {
              Text("Done", fontWeight = FontWeight.Bold)
            }
          }
        }
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
