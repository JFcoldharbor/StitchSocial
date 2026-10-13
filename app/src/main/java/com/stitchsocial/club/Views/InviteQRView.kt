/*
 * InviteQRView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views — "Show my invite QR" (INVITE_CONTRACT, Apps §3; the AppQR
 * board). A real QR of stitchsocial.me/invite/{code} with the Stitch mark in
 * the middle (error correction H, so the covered modules don't matter), plus
 * Share link and Save image.
 *
 * Reached from the Ambassador dashboard (ReferralDashboardView) and the
 * profile's overflow menu.
 */

package com.stitchsocial.club.views

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.firestore.FirebaseFirestore
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.stitchsocial.club.BuildConfig
import com.stitchsocial.club.R
import com.stitchsocial.club.services.InviteService
import com.stitchsocial.club.services.ReferralService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

// Board palette (INVITE_CONTRACT: black, #E91E63 / #F74198 → #8A7AFF, #22D3EE).
internal object InviteColors {
    val magenta = Color(0xFFE91E63)
    val pink = Color(0xFFF74198)
    val violet = Color(0xFF8A7AFF)
    val avatarFill = Color(0xFF3A1D2C)
    val muted = Color.White.copy(alpha = 0.65f)
}

/** Initials for the avatar fallback ("TR" for Teddy Ruks / @TeddyRuks). */
internal fun inviteInitials(displayName: String, username: String): String {
    val words = displayName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    val raw = when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}"
        words.size == 1 -> words[0].take(2)
        else -> username.take(2)
    }
    return raw.uppercase()
}

/** The board's avatar: a 2dp #F74198 ring around the photo, initials when none. */
@Composable
internal fun InviteAvatar(
    imageURL: String?,
    initials: String,
    size: Dp,
    fontSizeSp: Int = 16
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(InviteColors.avatarFill)
            .border(2.dp, InviteColors.pink, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (!imageURL.isNullOrBlank()) {
            AsyncImage(
                model = imageURL,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().padding(2.dp).clip(CircleShape)
            )
        } else {
            Text(initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = fontSizeSp.sp)
        }
    }
}

/**
 * The raw module matrix (1 px per module, no quiet zone; the white card is the
 * quiet zone). Drawn scaled with FilterQuality.None so modules stay crisp.
 */
internal fun inviteQRBitmap(content: String): Bitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
        EncodeHintType.MARGIN to 0,
        EncodeHintType.CHARACTER_SET to "UTF-8"
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h) { i ->
        if (matrix.get(i % w, i / w)) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
    }
    Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}.getOrNull()

private data class InviteQRProfile(
    val username: String,
    val displayName: String,
    val bio: String,
    val profileImageURL: String?,
    val referralCode: String,
    val referralCount: Int
)

private suspend fun loadInviteQRProfile(userID: String): InviteQRProfile? {
    val doc = FirebaseFirestore.getInstance("stitchfin").collection("users").document(userID).get().await()
    val data = doc.data ?: return null
    var code = (data["referralCode"] as? String).orEmpty()
    if (code.isBlank()) {
        // First time anyone asked: the same path the dashboard's Share uses.
        code = runCatching { ReferralService().generateReferralLink(userID).code }.getOrDefault("")
    }
    if (code.isBlank()) return null
    return InviteQRProfile(
        username = (data["username"] as? String).orEmpty(),
        displayName = (data["displayName"] as? String).orEmpty(),
        bio = (data["bio"] as? String).orEmpty().lineSequence().firstOrNull().orEmpty().trim(),
        profileImageURL = (data["profileImageURL"] as? String)?.takeIf { it.isNotBlank() },
        referralCode = code,
        referralCount = (data["referralCount"] as? Number)?.toInt() ?: 0
    )
}

@Composable
fun InviteQRScreen(
    userID: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<InviteQRProfile?>(null) }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    BackHandler(onBack = onDismiss)

    LaunchedEffect(userID) {
        profile = runCatching { loadInviteQRProfile(userID) }
            .onFailure { if (BuildConfig.DEBUG) println("⚠️ INVITE QR: load failed — ${it.message}") }
            .getOrNull()
        failed = profile == null
    }

    val inviteURL = profile?.let { InviteService.inviteURL(it.referralCode) }
    val qr = remember(inviteURL) { inviteURL?.let { inviteQRBitmap(it) } }

    // The card is drawn into this layer too, so Save image is exactly what's
    // on screen.
    val cardLayer = rememberGraphicsLayer()

    fun shareLink() {
        val url = inviteURL ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        context.startActivity(Intent.createChooser(intent, "Share your invite"))
        com.stitchsocial.club.services.AnalyticsService.referralShared("invite_qr")
    }

    fun saveImage() {
        if (saving || profile == null) return
        saving = true
        scope.launch {
            val ok = runCatching {
                val bitmap = cardLayer.toImageBitmap().asAndroidBitmap()
                withContext(Dispatchers.IO) { saveInviteImage(context, bitmap, profile!!.referralCode) }
            }.getOrDefault(false)
            saving = false
            Toast.makeText(
                context,
                if (ok) "Saved to your gallery" else "Couldn't save the image",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Android 9 is the one supported version that still needs the storage
    // permission to write into Pictures.
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) saveImage()
        else Toast.makeText(context, "Allow storage access to save the image", Toast.LENGTH_SHORT).show()
    }

    fun requestSave() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveImage()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(enabled = false) {}
    ) {
        val minHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    // Top bar: close, title, balancing spacer.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1A1820))
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Text("Your invite", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Spacer(Modifier.size(44.dp))
                    }

                    val p = profile
                    if (p == null) {
                        Box(Modifier.fillMaxWidth().height(420.dp), contentAlignment = Alignment.Center) {
                            if (failed) {
                                Text(
                                    "Couldn't load your invite. Try again in a moment.",
                                    color = InviteColors.muted, fontSize = 14.sp, textAlign = TextAlign.Center
                                )
                            } else {
                                CircularProgressIndicator(color = InviteColors.pink, strokeWidth = 3.dp)
                            }
                        }
                    } else {
                        // The card — also the saved image.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .drawWithContent {
                                    cardLayer.record { this@drawWithContent.drawContent() }
                                    drawLayer(cardLayer)
                                }
                                .clip(RoundedCornerShape(28.dp))
                                .background(
                                    // CSS 170deg: very nearly top to bottom.
                                    Brush.verticalGradient(listOf(Color(0xFF1D1020), Color(0xFF0D0B14)))
                                )
                                .border(1.dp, InviteColors.pink.copy(alpha = 0.35f), RoundedCornerShape(28.dp))
                                .padding(horizontal = 22.dp, vertical = 26.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                InviteAvatar(
                                    imageURL = p.profileImageURL,
                                    initials = inviteInitials(p.displayName, p.username),
                                    size = 48.dp
                                )
                                Column {
                                    Text(
                                        "@${p.username}", color = Color.White,
                                        fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1
                                    )
                                    if (p.bio.isNotBlank()) {
                                        Text(
                                            p.bio, color = InviteColors.muted, fontSize = 13.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 220.dp)
                                        )
                                    }
                                }
                            }

                            // QR on white, Stitch mark in the middle.
                            Box(
                                modifier = Modifier
                                    .size(252.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(Color.White)
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (qr != null) {
                                    Image(
                                        bitmap = qr.asImageBitmap(),
                                        contentDescription = "Invite QR code",
                                        filterQuality = FilterQuality.None,
                                        contentScale = ContentScale.FillBounds,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color.White),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = painterResource(R.drawable.stitchsociallogo),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }

                            Text(
                                buildAnnotatedString {
                                    append("Scan to join me ")
                                    withStyle(
                                        SpanStyle(
                                            fontFamily = FontFamily.Serif,
                                            fontStyle = FontStyle.Italic,
                                            fontWeight = FontWeight.Normal,
                                            fontSize = 30.sp,
                                            color = InviteColors.pink
                                        )
                                    ) { append("on Stitch") }
                                },
                                color = Color.White,
                                fontSize = 26.sp,
                                lineHeight = 30.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.02).em,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                "They'll follow you automatically, and their first post gets a boost.",
                                color = Color.White.copy(alpha = 0.68f),
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                textAlign = TextAlign.Center
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(InviteColors.magenta)
                                    .clickable { shareLink() },
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.IosShare, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Share link", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                                    .clickable(enabled = !saving) { requestSave() },
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (saving) {
                                    CircularProgressIndicator(
                                        color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp)
                                    )
                                } else {
                                    Icon(Icons.Default.Download, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text("Save image", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                        }
                    }
                }

                profile?.let { p ->
                    Row(
                        modifier = Modifier
                            .padding(top = 20.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF0F0F12))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${p.referralCount} joined with your invite",
                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp
                            )
                            Text(
                                "You earn 100 clout for each, up to 1,000.",
                                color = InviteColors.muted, fontSize = 13.sp
                            )
                        }
                        Text(
                            p.referralCode,
                            color = InviteColors.muted, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em
                        )
                    }
                }
            }
        }
    }
}

/** Write the card into Pictures/StitchSocial. True on success. */
private fun saveInviteImage(context: Context, bitmap: Bitmap, code: String): Boolean {
    val filename = "Stitch_invite_${code}_${System.currentTimeMillis()}.png"
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StitchSocial")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val written = resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: false
        if (!written) {
            resolver.delete(uri, null, null)
            return false
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } else {
        @Suppress("DEPRECATION")
        val dir = java.io.File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "StitchSocial"
        )
        dir.mkdirs()
        val file = java.io.File(dir, filename)
        val written = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (written) {
            @Suppress("DEPRECATION")
            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))
        }
        written
    }
}
