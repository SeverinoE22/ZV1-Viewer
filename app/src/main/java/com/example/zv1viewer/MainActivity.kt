package com.example.zv1viewer

import android.app.PendingIntent
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MtpPhoto(
    val handle: Int,
    val name: String,
    val dateCreated: Long,
    val compressedSize: Int,
    val thumbnail: Bitmap?
)

class MainActivity : ComponentActivity() {
    private val ACTION_USB_PERMISSION = "com.example.zv1viewer.USB_PERMISSION"

    // Diese Variablen gehören jetzt der Activity, nicht mehr der UI
    private var activeConnection: UsbDeviceConnection? = null
    private var activeMtpDevice: MtpDevice? = null

    // UI States
    private val photosState = mutableStateOf<List<MtpPhoto>>(emptyList())
    private val statusTextState = mutableStateOf("Searching for Sony ZV-1...")
    private val isLoadingListState = mutableStateOf(false)
    private val refreshTrigger = mutableIntStateOf(0)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION,
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    refreshTrigger.intValue++
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    // Kabel wurde gezogen!
                    // 1. USB-Verbindung sauber schließen
                    closeUsb()
                    // 2. Die App komplett beenden und aus dem Task-Manager werfen
                    finishAndRemoveTask()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ZV1ViewerScreen(
                    trigger = refreshTrigger.intValue,
                    photos = photosState.value,
                    statusText = statusTextState.value,
                    isLoadingList = isLoadingListState.value,
                    getFullImage = { photo -> loadFullImage(photo) },
                    getMtpDevice = { activeMtpDevice }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        refreshTrigger.intValue++
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        closeUsb()
    }

    // Wird aufgerufen, wenn der Trigger sich ändert
    fun loadPhotos() {
        // 1. Alte Verbindung sauber schließen
        closeUsb()

        photosState.value = emptyList()
        isLoadingListState.value = true
        statusTextState.value = "Suche Sony ZV-1..."

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // Retry-Schleife für Device-Erkennung
        var sonyDevice: UsbDevice? = null
        // Wir machen das asynchron, um den Main-Thread nicht zu blockieren
        kotlin.concurrent.thread {
            for (i in 0 until 5) {
                sonyDevice = usbManager.deviceList.values.firstOrNull { it.vendorId == 1356 }
                if (sonyDevice != null) break
                Thread.sleep(300)
            }

            val device = sonyDevice
            if (device == null) {
                runOnUiThread {
                    statusTextState.value = "Keine Sony ZV-1 verbunden.\nKabel-Schalter auf MTP stellen."
                    isLoadingListState.value = false
                }
                return@thread
            }

            if (!usbManager.hasPermission(device)) {
                runOnUiThread {
                    statusTextState.value = "Fordere USB-Berechtigung an..."
                    isLoadingListState.value = false
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
                    usbManager.requestPermission(device, pi)
                }
                return@thread
            }

            runOnUiThread { statusTextState.value = "Lese ZV-1 SD-Karte..." }

            // MTP Initialisierung (im Hintergrund-Thread)
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                runOnUiThread {
                    statusTextState.value = "USB-Verbindung fehlgeschlagen (busy?)"
                    isLoadingListState.value = false
                }
                return@thread
            }

            val mtp = MtpDevice(device)
            if (!mtp.open(connection)) {
                connection.close()
                runOnUiThread {
                    statusTextState.value = "MTP-Verbindung fehlgeschlagen"
                    isLoadingListState.value = false
                }
                return@thread
            }

            activeConnection = connection
            activeMtpDevice = mtp

            val list = mutableListOf<MtpPhoto>()
            var storageIds = mtp.storageIds ?: intArrayOf()
            if (storageIds.isEmpty()) {
                Thread.sleep(1200)
                storageIds = mtp.storageIds ?: intArrayOf()
            }

            for (storageId in storageIds) {
                val handles = mtp.getObjectHandles(storageId, 0, 0) ?: intArrayOf()
                for (handle in handles) {
                    val info = mtp.getObjectInfo(handle)
                    if (info != null && (info.name.endsWith(".jpg", true) || info.name.endsWith(".jpeg", true))) {
                        val thumbBytes = mtp.getThumbnail(handle)
                        val thumbBmp = thumbBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        list.add(MtpPhoto(handle, info.name, info.dateCreated, info.compressedSize, thumbBmp))
                    }
                }
            }

            val sortedList = list.sortedByDescending { it.dateCreated }

            runOnUiThread {
                photosState.value = sortedList
                statusTextState.value = if (sortedList.isEmpty()) "Keine JPEGs auf der ZV-1 gefunden." else "${sortedList.size} Fotos gefunden"
                isLoadingListState.value = false
            }
        }
    }

    // Wird von der UI aufgerufen, wenn ein Bild im Vollbild geladen werden soll
    private suspend fun loadFullImage(photo: MtpPhoto): Bitmap? = withContext(Dispatchers.IO) {
        val mtp = activeMtpDevice ?: return@withContext null
        try {
            val bytes = mtp.getObject(photo.handle, photo.compressedSize) ?: return@withContext null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (opts.outWidth / sample > 2560 || opts.outHeight / sample > 2560) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
        } catch (e: Exception) {
            null
        }
    }

    private fun closeUsb() {
        try { activeMtpDevice?.close() } catch (e: Exception) {}
        try { activeConnection?.close() } catch (e: Exception) {}
        activeMtpDevice = null
        activeConnection = null
    }
}

@Composable
fun ZV1ViewerScreen(
    trigger: Int,
    photos: List<MtpPhoto>,
    statusText: String,
    isLoadingList: Boolean,
    getFullImage: suspend (MtpPhoto) -> Bitmap?,
    getMtpDevice: () -> MtpDevice?
) {
    // Dieser Effekt reagiert auf den Trigger (in der Activity definiert)
    // Er wird nicht mehr für die USB-Logik genutzt, sondern ruft loadPhotos() auf.
    // Da wir keinen direkten Zugriff auf loadPhotos() von außen haben, triggern wir es über die Activity.
    // (Dies geschieht durch den onRefresh-Callback und den Receiver)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Holt die Activity, um loadPhotos() aufzurufen
    val activity = context as? MainActivity

    // Dieser Effekt startet das Laden, wenn die App startet oder der Trigger sich ändert
    LaunchedEffect(trigger) {
        activity?.loadPhotos()
    }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var fullBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoadingFull by remember { mutableStateOf(false) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var isFullscreen by remember { mutableStateOf(false) }
    var dragSum by remember { mutableFloatStateOf(0f) }

    // Wenn sich die Fotoliste ändert, Index zurücksetzen
    LaunchedEffect(photos) {
        selectedIndex = 0
    }

    // Vollbild laden, wenn sich der Index ändert
    LaunchedEffect(photos, selectedIndex) {
        scale = 1f; offsetX = 0f; offsetY = 0f
        val current = photos.getOrNull(selectedIndex) ?: return@LaunchedEffect

        isLoadingFull = true
        fullBitmap = getFullImage(current)
        isLoadingFull = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(if (isFullscreen) 0.dp else 8.dp)
    ) {
        if (!isFullscreen) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = photos.getOrNull(selectedIndex)?.name ?: statusText,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )

                if (fullBitmap != null) {
                    OutlinedButton(onClick = { isFullscreen = true }) {
                        Text("Fullscreen")
                    }
                    Button(
                        onClick = {
                            val bmp = fullBitmap ?: return@Button
                            val name = photos.getOrNull(selectedIndex)?.name ?: "ZV1_Photo.jpg"
                            scope.launch {
                                val saved = savePreviewToGallery(context, bmp, name)
                                Toast.makeText(
                                    context,
                                    if (saved) "Saved: $name" else "Save failed",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    ) {
                        Text("Save (2560px)")
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(if (isFullscreen) 0.dp else 8.dp))
                .background(Color(0xFF121212))
                .pointerInput(photos.size, selectedIndex) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 6f)
                        scale = newScale
                        if (newScale > 1.05f) {
                            offsetX += pan.x
                            offsetY += pan.y
                            dragSum = 0f
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                            dragSum += pan.x
                            if (dragSum < -140f && selectedIndex < photos.lastIndex) {
                                dragSum = 0f
                                selectedIndex++
                            } else if (dragSum > 140f && selectedIndex > 0) {
                                dragSum = 0f
                                selectedIndex--
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoadingList -> CircularProgressIndicator(color = Color.White)
                isLoadingFull -> CircularProgressIndicator(color = Color.White)
                fullBitmap != null -> {
                    Image(
                        bitmap = fullBitmap!!.asImageBitmap(),
                        contentDescription = "ZV1 Photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY
                            )
                    )
                }
                else -> Text(text = statusText, color = Color.LightGray)
            }

            if (isFullscreen) {
                Button(
                    onClick = { isFullscreen = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black.copy(alpha = 0.6f),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                ) {
                    Text("Thumbnails")
                }
            }
        }

        if (!isFullscreen && photos.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(photos) { index, photo ->
                    val isSelected = index == selectedIndex
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) Color(0xFFFF6D00) else Color.DarkGray,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable { selectedIndex = index },
                        contentAlignment = Alignment.Center
                    ) {
                        if (photo.thumbnail != null) {
                            Image(
                                bitmap = photo.thumbnail.asImageBitmap(),
                                contentDescription = photo.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(photo.name.takeLast(8), color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    } // schließt Box (Thumbnail)
                } // schließt itemsIndexed
            } // schließt LazyRow
        } // schließt if (!isFullscreen && photos.isNotEmpty())
    } // schließt Column
} // schließt fun ZV1ViewerScreen

suspend fun savePreviewToGallery(context: Context, bitmap: Bitmap, fileName: String): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ZV1Viewer")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            true
        } catch (t: Throwable) {
            false
        }
    }