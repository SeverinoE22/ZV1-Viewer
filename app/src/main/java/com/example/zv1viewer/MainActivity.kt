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

    private var activeConnection: UsbDeviceConnection? = null
    private var activeMtpDevice: MtpDevice? = null

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

                    closeUsb()
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

    fun loadPhotos() {
        closeUsb()

        photosState.value = emptyList()
        isLoadingListState.value = true
        statusTextState.value = "Searching Sony ZV-1..."

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        var sonyDevice: UsbDevice? = null
        kotlin.concurrent.thread {
            for (i in 0 until 5) {
                sonyDevice = usbManager.deviceList.values.firstOrNull { it.vendorId == 1356 }
                if (sonyDevice != null) break
                Thread.sleep(300)
            }

            val device = sonyDevice
            if (device == null) {
                runOnUiThread {
                    statusTextState.value = "No Sony ZV-1 detected.\nCheck cable/switch/settings."
                    isLoadingListState.value = false
                }
                return@thread
            }

            if (!usbManager.hasPermission(device)) {
                runOnUiThread {
                    statusTextState.value = "Requesting USB-permission..."
                    isLoadingListState.value = false
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                    val intent = Intent(ACTION_USB_PERMISSION).apply {
                        setPackage(packageName)
                    }
                    val pi = PendingIntent.getBroadcast(this, 0, intent, flags)
                    usbManager.requestPermission(device, pi)
                }
                return@thread
            }

            runOnUiThread { statusTextState.value = "Reading ZV-1 SD-Card..." }

            val connection = usbManager.openDevice(device)
            if (connection == null) {
                runOnUiThread {
                    statusTextState.value = "USB-Connection failed (busy?)"
                    isLoadingListState.value = false
                }
                return@thread
            }

            val mtp = MtpDevice(device)
            if (!mtp.open(connection)) {
                connection.close()
                runOnUiThread {
                    statusTextState.value = "MTP-Connection failed"
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
                    if (info != null) {
                        val isJpg = info.name.endsWith(".jpg", true) || info.name.endsWith(".jpeg", true)
                        val isArw = info.name.endsWith(".arw", true)
                        if (isJpg || isArw) {
                            val thumbBytes = mtp.getThumbnail(handle)
                            val thumbBmp = thumbBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                            list.add(MtpPhoto(handle, info.name, info.dateCreated, info.compressedSize, thumbBmp))
                        }
                    }
                }
            }

            val sortedList = list.sortedByDescending { it.dateCreated }

            runOnUiThread {
                photosState.value = sortedList
                statusTextState.value = if (sortedList.isEmpty()) "Cannot find any photos on ZV-1." else "${sortedList.size} photos found"
                isLoadingListState.value = false
            }
        }
    }


    private suspend fun loadFullImage(photo: MtpPhoto): Bitmap? = withContext(Dispatchers.IO) {
        val mtp = activeMtpDevice ?: return@withContext null
        try {
            val isArw = photo.name.endsWith(".arw", true)

            val bytes: ByteArray = if (isArw) {
                val maxRead = minOf(photo.compressedSize, 2_500_000)
                val buffer = ByteArray(maxRead)
                val read = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    mtp.getPartialObject(photo.handle, 0L, maxRead.toLong(), buffer)
                } else {
                    val fullBytes = mtp.getObject(photo.handle, photo.compressedSize) ?: return@withContext null
                    return@withContext decodeSampledJpeg(extractEmbeddedJpegFromArw(fullBytes, fullBytes.size) ?: return@withContext null)
                }
                if (read <= 0) return@withContext null
                extractEmbeddedJpegFromArw(buffer, read.toInt()) ?: return@withContext null
            } else {
                mtp.getObject(photo.handle, photo.compressedSize) ?: return@withContext null
            }


            decodeSampledJpeg(bytes)
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeSampledJpeg(bytes: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)

        var sample = 1
        while (opts.outWidth / (sample * 2) >= 2560 || opts.outHeight / (sample * 2) >= 2560) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null

        // Exakt auf max. 2560px skalieren, falls noch leicht darüber (z. B. 2736px -> 2560px)
        val maxDim = maxOf(bmp.width, bmp.height)
        return if (maxDim > 2560) {
            val scale = 2560f / maxDim
            val newW = (bmp.width * scale).toInt()
            val newH = (bmp.height * scale).toInt()
            Bitmap.createScaledBitmap(bmp, newW, newH, true).also {
                if (it != bmp) bmp.recycle()
            }
        } else {
            bmp
        }
    }


    private fun extractEmbeddedJpegFromArw(data: ByteArray, length: Int): ByteArray? {
        var bestStart = -1
        var bestEnd = -1
        var i = 0
        while (i < length - 2) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte() && data[i + 2] == 0xFF.toByte()) {
                val start = i
                var j = i + 2
                while (j < length - 1) {
                    if (data[j] == 0xFF.toByte() && data[j + 1] == 0xD9.toByte()) {
                        val end = j + 2
                        if (end - start > (bestEnd - bestStart)) {
                            bestStart = start
                            bestEnd = end
                        }
                        i = end
                        break
                    }
                    j++
                }
            }
            i++
        }
        return if (bestStart != -1 && bestEnd > bestStart) {
            data.copyOfRange(bestStart, bestEnd)
        } else {
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

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Holt die Activity, um loadPhotos() aufzurufen
    val activity = context as? MainActivity

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

    LaunchedEffect(photos) {
        selectedIndex = 0
    }

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
                    OutlinedButton(
                        onClick = {
                            val bmp = fullBitmap ?: return@OutlinedButton
                            val rawName = photos.getOrNull(selectedIndex)?.name ?: "ZV1_Photo.jpg"
                            val name = rawName.replace(Regex("\\.arw$", RegexOption.IGNORE_CASE), ".jpg")
                            scope.launch {
                                val saved = savePreviewToGallery(context, bmp, name)
                                Toast.makeText(
                                    context,
                                    if (saved) "Saved: $name" else "Saving failed",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    ) {
                        Text("Save to gallery")
                    }

                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(if (isFullscreen) 0.dp else 8.dp))
                .background(Color.Black)
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
                            if (photo.name.endsWith(".arw", true)) {
                                Text(
                                    text = "RAW",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        } else {
                            Text(photo.name.takeLast(8), color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }

                    }
                }
            }
        }
    }
}

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