package sh.bentley.transponder

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import uniffi.transponder_core.generateFriendLink
import uniffi.transponder_core.parseFriendLink
import java.util.concurrent.Executors

enum class ExchangeRole {
    SHOW_FIRST,
    SCAN_FIRST
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFriendScreen(
    identityStore: IdentityStore,
    onAddFriend: (String) -> Unit,
    onComplete: () -> Unit,
    onDismiss: () -> Unit
) {
    var role by remember { mutableStateOf(ExchangeRole.SCAN_FIRST) }
    var currentStep by remember { mutableIntStateOf(0) }
    var addedFriendName by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (role to currentStep) {
                            ExchangeRole.SHOW_FIRST to 0 -> "Show Your QR"
                            ExchangeRole.SHOW_FIRST to 1 -> "Scan Their QR"
                            ExchangeRole.SCAN_FIRST to 0 -> "Scan Their QR"
                            ExchangeRole.SCAN_FIRST to 1 -> "Show Your QR"
                            else -> "Add Friend"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Role selector
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SegmentedButton(
                    selected = role == ExchangeRole.SCAN_FIRST,
                    onClick = { if (currentStep == 0) role = ExchangeRole.SCAN_FIRST },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    enabled = currentStep == 0
                ) {
                    Text("I scan first")
                }
                SegmentedButton(
                    selected = role == ExchangeRole.SHOW_FIRST,
                    onClick = { if (currentStep == 0) role = ExchangeRole.SHOW_FIRST },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    enabled = currentStep == 0
                ) {
                    Text("I show first")
                }
            }

            // Step indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(2) { step ->
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (step <= currentStep)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            )
                    )
                    if (step < 1) Spacer(modifier = Modifier.width(8.dp))
                }
            }

            // Content based on role and step
            Box(modifier = Modifier.weight(1f)) {
                val isShowStep = (role == ExchangeRole.SHOW_FIRST && currentStep == 0) ||
                        (role == ExchangeRole.SCAN_FIRST && currentStep == 1)

                if (isShowStep) {
                    ShowQRContent(
                        identityStore = identityStore,
                        isFirstStep = currentStep == 0,
                        addedFriendName = addedFriendName,
                        onNext = { currentStep = 1 },
                        onDone = {
                            onComplete()
                            onDismiss()
                        }
                    )
                } else {
                    ScanQRContent(
                        onScan = { code ->
                            onAddFriend(code)
                            try {
                                val parsed = parseFriendLink(code)
                                addedFriendName = parsed.name
                            } catch (_: Exception) {}
                            currentStep = 1
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ShowQRContent(
    identityStore: IdentityStore,
    isFirstStep: Boolean,
    addedFriendName: String?,
    onNext: () -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current

    val friendLink = remember(identityStore) {
        val identity = identityStore.getIdentity() ?: return@remember null
        val serverUrl = identityStore.serverUrl ?: return@remember null
        val name = identityStore.displayName ?: "Friend"
        generateFriendLink(identity, serverUrl, name)
    }

    val qrBitmap = remember(friendLink) {
        friendLink?.let { generateQrBitmap(it, 512) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "QR Code",
                modifier = Modifier
                    .size(250.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(16.dp)
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(250.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isFirstStep) {
            Text(
                text = "Have your friend scan this QR code",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Once they've scanned it, tap Next to scan theirs",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                Text("Next")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    friendLink?.let { link ->
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, link)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "Share Link"))
                    }
                },
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Share Link Instead")
            }
        } else {
            if (addedFriendName != null) {
                Text(
                    text = "Added $addedFriendName!",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                text = "Now have your friend scan this to complete the exchange",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                Text("Done")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    friendLink?.let { link ->
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, link)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "Share Link"))
                    }
                },
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Share Link Instead")
            }
        }
    }
}

@Composable
private fun ScanQRContent(
    onScan: (String) -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    var showLinkEntry by remember { mutableStateOf(false) }
    var linkText by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Link entry dialog
    if (showLinkEntry) {
        AlertDialog(
            onDismissRequest = { showLinkEntry = false },
            title = { Text("Add with Link") },
            text = {
                Column {
                    Text(
                        "Paste a friend's Coords link:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = linkText,
                        onValueChange = { linkText = it },
                        placeholder = { Text("coord://...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (linkText.isNotBlank()) {
                            onScan(linkText)
                            showLinkEntry = false
                        }
                    },
                    enabled = linkText.isNotBlank()
                ) {
                    Text("Add Friend")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLinkEntry = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (hasCameraPermission) {
        QRScanner(
            onScan = onScan,
            onAddWithLink = { showLinkEntry = true }
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Text(
                    text = "Camera permission is required to scan QR codes.",
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant Permission")
                }
            }
        }
    }
}

@Composable
private fun QRScanner(
    onScan: (String) -> Unit,
    onAddWithLink: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var scanned by remember { mutableStateOf(false) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    val reader = MultiFormatReader().apply {
                        setHints(
                            mapOf(
                                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                                DecodeHintType.TRY_HARDER to true
                            )
                        )
                    }

                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (!scanned) {
                            val result = analyzeImage(imageProxy, reader)
                            if (result != null && result.startsWith("coord://")) {
                                scanned = true
                                onScan(result)
                            }
                        }
                        imageProxy.close()
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay hint and link button
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "Point camera at your friend's QR code",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onAddWithLink,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add with Link")
            }
        }
    }
}

private fun analyzeImage(imageProxy: ImageProxy, reader: MultiFormatReader): String? {
    val buffer = imageProxy.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val source = PlanarYUVLuminanceSource(
        bytes,
        imageProxy.width,
        imageProxy.height,
        0,
        0,
        imageProxy.width,
        imageProxy.height,
        false
    )

    val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

    return try {
        val result = reader.decode(binaryBitmap)
        result.text
    } catch (e: NotFoundException) {
        null
    } catch (e: Exception) {
        null
    }
}

private fun generateQrBitmap(content: String, size: Int): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix: BitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

// Share back screen for deep link flow
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareBackScreen(
    identityStore: IdentityStore,
    friendName: String,
    onDismiss: () -> Unit
) {
    val qrBitmap = remember(identityStore) {
        val identity = identityStore.getIdentity() ?: return@remember null
        val serverUrl = identityStore.serverUrl ?: return@remember null
        val name = identityStore.displayName ?: "Friend"
        val link = generateFriendLink(identity, serverUrl, name)
        generateQrBitmap(link, 512)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share Your Link") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Added $friendName!",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier
                        .size(250.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Share your link so $friendName can see you too",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Have them scan this QR code",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                Text("Done")
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = onDismiss) {
                Text("Skip")
            }
        }
    }
}
