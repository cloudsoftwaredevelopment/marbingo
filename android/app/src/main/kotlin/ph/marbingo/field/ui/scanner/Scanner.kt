package ph.marbingo.field.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import ph.marbingo.field.ui.*
import ph.marbingo.field.ui.theme.Mb
import ph.marbingo.field.ui.theme.MbShapes
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Accepts a code only after it has been read on `needed` consecutive frames. */
class StableCodeDetector(private val needed: Int = 2) {
    private var last: String? = null
    private var count = 0
    fun accept(code: String?): String? {
        if (code.isNullOrBlank()) { last = null; count = 0; return null }
        if (code == last) count++ else { last = code; count = 1 }
        return if (count >= needed) code else null
    }
}

private class BarcodeAnalyzer(private val onStable: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_QR_CODE,
            ).build(),
    )
    private val detector = StableCodeDetector()

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) { proxy.close(); return }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { codes ->
                detector.accept(codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue)?.let(onStable)
            }
            .addOnCompleteListener { proxy.close() }
    }

    fun close() = scanner.close()
}

@Composable
fun ScannerScreen(nav: NavController) {
    val context = LocalContext.current
    fun finish(code: String?) {
        if (code != null) nav.previousBackStackEntry?.savedStateHandle?.set(Routes.SCAN_RESULT_KEY, code)
        nav.popBackStack()
    }

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    if (granted) CameraView(onCode = { finish(it) }, onCancel = { finish(null) })
    else PermissionDenied(
        asked = asked,
        onRetry = { launcher.launch(Manifest.permission.CAMERA) },
        onTyped = { finish(it) },
        onCancel = { finish(null) },
    )
}

@Composable
private fun CameraView(onCode: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
    val delivered = remember { AtomicBoolean(false) }
    val currentOnCode by rememberUpdatedState(onCode)

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val analyzer = BarcodeAnalyzer { code ->
            if (delivered.compareAndSet(false, true)) {
                ContextCompat.getMainExecutor(context).execute { currentOnCode(code) }
            }
        }
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = future.get()
            provider = p
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(executor, analyzer) }
            try {
                p.unbindAll()
                camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {
                camera = null
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
            analyzer.close()
        }
    }

    Box(Modifier.fillMaxSize().background(Mb.Ink)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // Aiming frame
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth(0.7f).widthIn(max = 420.dp).aspectRatio(1.6f)
                .border(3.dp, Mb.SidebarAccent, MbShapes.Card),
        )
        Text(
            "Point the camera at the item's barcode",
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(24.dp)
                .background(Mb.Ink.copy(alpha = 0.7f), MbShapes.Control).padding(horizontal = 14.dp, vertical = 8.dp),
            color = Mb.Ground, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        )
        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasFlash) {
                FilledTonalIconButton(
                    onClick = { torch = !torch; camera?.cameraControl?.enableTorch(torch) },
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription = if (torch) "Turn torch off" else "Turn torch on",
                    )
                }
            }
            Button(
                onClick = onCancel,
                modifier = Modifier.heightIn(min = 56.dp),
                shape = MbShapes.Control,
                colors = ButtonDefaults.buttonColors(containerColor = Mb.Surface, contentColor = Mb.Ink),
            ) { Text("Cancel") }
        }
    }
}

@Composable
private fun PermissionDenied(asked: Boolean, onRetry: () -> Unit, onTyped: (String) -> Unit, onCancel: () -> Unit) {
    var sku by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Mb.Ground).safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 440.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Camera access needed", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (asked) "Camera permission was denied, so barcodes can't be scanned. You can allow it in Android settings, or type the code instead."
                else "Marbingo Field uses the camera only to read item barcodes. You can also type the code instead.",
                style = MaterialTheme.typography.bodyMedium, color = Mb.Muted,
            )
            SecondaryButton("Allow camera", onRetry, Modifier.fillMaxWidth())
            MbField(
                sku, { sku = it }, "Type the SKU / barcode",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (sku.isNotBlank()) onTyped(sku.trim()) }),
            )
            PrimaryButton("Use this code", { onTyped(sku.trim()) }, Modifier.fillMaxWidth(), enabled = sku.isNotBlank())
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).align(Alignment.CenterHorizontally)) { Text("Cancel") }
        }
    }
}
