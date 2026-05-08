/*
 * EditProfileView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 8: Views — Matches iOS NewEditProfileView exactly
 * Features: PhotoPicker image upload to Firebase Storage,
 *           displayName/username/bio editing, privacy toggle,
 *           validation, optimistic UI, saving state
 *
 * CACHING: On save, caller's loadUser() re-reads Firestore once.
 *          No extra reads here — UserService.updateProfile is a single write.
 * IMAGE UPLOAD: Firebase Storage → profileImages/{userID}.jpg
 *               Returns download URL written to users/{userID}.profileImageURL
 */

package com.stitchsocial.club.views

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.stitchsocial.club.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileView(
    userID: String,
    currentUserName: String = "User",
    currentUsername: String = "user",
    currentUserImage: String? = null,
    currentBio: String = "",
    currentIsPrivate: Boolean = false,
    onSave: (String, String, String, Uri?) -> Unit = { _, _, _, _ -> },
    onCancel: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Form state
    var displayName by remember { mutableStateOf(currentUserName) }
    var username by remember { mutableStateOf(currentUsername) }
    var bio by remember { mutableStateOf(currentBio) }
    var isPrivate by remember { mutableStateOf(currentIsPrivate) }
    var selectedImage by remember { mutableStateOf<Uri?>(null) }

    // UI state
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var uploadProgress by remember { mutableStateOf<Float?>(null) }

    // Validation
    var usernameAvailable by remember { mutableStateOf<Boolean?>(true) }
    var isCheckingUsername by remember { mutableStateOf(false) }
    var displayNameError by remember { mutableStateOf<String?>(null) }
    var bioError by remember { mutableStateOf<String?>(null) }

    // Sync when props change (matches iOS LaunchedEffect)
    LaunchedEffect(currentUserName, currentUsername, currentBio, currentIsPrivate) {
        displayName = currentUserName
        username = currentUsername
        bio = currentBio
        isPrivate = currentIsPrivate
    }

    // Username validation with debounce (matches iOS validateUsername)
    LaunchedEffect(username) {
        if (username == currentUsername) {
            usernameAvailable = true
            isCheckingUsername = false
            return@LaunchedEffect
        }
        if (username.length < 3) {
            usernameAvailable = null
            isCheckingUsername = false
            return@LaunchedEffect
        }
        isCheckingUsername = true
        delay(500)
        usernameAvailable = username.matches(Regex("^[a-zA-Z0-9_]{3,20}$"))
        isCheckingUsername = false
    }

    // Change detection (matches iOS hasChanges)
    val hasChanges = displayName != currentUserName ||
            username != currentUsername ||
            bio != currentBio ||
            isPrivate != currentIsPrivate ||
            selectedImage != null

    val isFormValid = displayName.trim().isNotEmpty() &&
            username.trim().length >= 3 &&
            (usernameAvailable == true) &&
            bio.length <= 150

    // Image picker — matches iOS PhotosPicker
    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> selectedImage = uri }

    // Save function — matches iOS saveProfile()
    fun saveProfile() {
        if (!isFormValid || isSaving) return
        isSaving = true
        errorMessage = null

        scope.launch {
            try {
                var imageURL: String? = currentUserImage

                // 1. Upload image if selected — Firebase Storage
                selectedImage?.let { uri ->
                    uploadProgress = 0f
                    imageURL = uploadProfileImage(context, userID, uri) { progress ->
                        uploadProgress = progress
                    }
                    uploadProgress = null
                }

                // 2. Update Firestore profile fields
                val db = FirebaseFirestore.getInstance("stitchfin")
                val updates = mutableMapOf<String, Any>(
                    "displayName" to displayName.trim(),
                    "bio" to bio.trim(),
                    "isPrivate" to isPrivate
                )
                if (username.trim() != currentUsername) {
                    updates["username"] = username.trim().lowercase()
                    updates["usernameLowercase"] = username.trim().lowercase()
                }
                imageURL?.let { updates["profileImageURL"] = it }

                db.collection("users").document(userID)
                    .update(updates)
                    .await()

                // 3. Notify caller (matches iOS onSave(updatedUser))
                onSave(displayName.trim(), bio.trim(), username.trim(), selectedImage)

            } catch (e: Exception) {
                errorMessage = "Failed to save profile: ${e.message}"
                if (BuildConfig.DEBUG) { println("❌ EDIT PROFILE: Save failed: ${e.message}") }
            } finally {
                isSaving = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Edit Profile", color = Color.White, fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, "Cancel", tint = Color.Gray)
                    }
                },
                actions = {
                    TextButton(
                        onClick = { saveProfile() },
                        enabled = hasChanges && isFormValid && !isSaving
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.Cyan,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "Save",
                                color = if (hasChanges && isFormValid) Color.Cyan else Color.Gray,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { padding ->

        // Saving overlay (matches iOS savingView)
        if (isSaving) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (uploadProgress != null) "Uploading photo..." else "Saving...",
                        color = Color.White,
                        fontSize = 16.sp
                    )
                    uploadProgress?.let { progress ->
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.width(160.dp),
                            color = Color.Cyan,
                            trackColor = Color.Gray.copy(alpha = 0.3f)
                        )
                    }
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            // MARK: - Profile Image Section (matches iOS profileImageSection)
            Box(
                modifier = Modifier.size(120.dp),
                contentAlignment = Alignment.Center
            ) {
                // Avatar circle
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .border(2.dp, Color.Cyan, CircleShape)
                        .clickable { imageLauncher.launch("image/*") }
                ) {
                    when {
                        selectedImage != null -> AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(selectedImage).crossfade(true).build(),
                            contentDescription = "Selected image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        !currentUserImage.isNullOrEmpty() -> AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(currentUserImage).crossfade(true).build(),
                            contentDescription = "Profile image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        else -> Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Gray.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Person, null, modifier = Modifier.size(60.dp), tint = Color.Gray)
                        }
                    }
                }

                // Camera badge (matches iOS camera.fill overlay)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.85f))
                        .border(2.dp, Color.White, CircleShape)
                        .clickable { imageLauncher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PhotoCamera, "Change photo", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Tap to change photo", color = Color.Gray, fontSize = 14.sp)

            Spacer(Modifier.height(32.dp))

            // MARK: - Form Fields (matches iOS formFields)

            // Display Name
            ProfileField(
                label = "Display Name",
                value = displayName,
                onValueChange = {
                    displayName = it
                    displayNameError = if (it.trim().isEmpty()) "Display name required" else null
                },
                error = displayNameError,
                placeholder = "Your name"
            )

            Spacer(Modifier.height(16.dp))

            // Username
            ProfileField(
                label = "Username",
                value = username,
                onValueChange = { username = it.lowercase().replace(" ", "") },
                placeholder = "username",
                prefix = "@",
                trailingIcon = {
                    when {
                        isCheckingUsername -> CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Gray, strokeWidth = 2.dp)
                        username == currentUsername -> Icon(Icons.Default.Check, null, tint = Color.Cyan, modifier = Modifier.size(18.dp))
                        usernameAvailable == true -> Icon(Icons.Default.Check, null, tint = Color.Cyan, modifier = Modifier.size(18.dp))
                        usernameAvailable == false -> Icon(Icons.Default.Close, null, tint = Color.Red, modifier = Modifier.size(18.dp))
                    }
                },
                error = if (usernameAvailable == false) "Username unavailable or invalid" else null
            )

            Spacer(Modifier.height(16.dp))

            // Bio
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Bio", color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = bio,
                    onValueChange = {
                        if (it.length <= 150) bio = it
                        bioError = if (it.length > 150) "Bio must be 150 chars or less" else null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    placeholder = { Text("Tell people about yourself...", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.Cyan,
                        unfocusedBorderColor = Color.Gray.copy(alpha = 0.4f),
                        focusedContainerColor = Color.White.copy(alpha = 0.05f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                        cursorColor = Color.Cyan
                    ),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 5
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    bioError?.let { Text(it, color = Color.Red, fontSize = 12.sp) } ?: Spacer(Modifier.width(1.dp))
                    Text("${bio.length}/150", color = Color.Gray, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(24.dp))

            // MARK: - Privacy Toggle (matches iOS privacySection)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isPrivate) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = if (isPrivate) Color.Cyan else Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Private Account", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (isPrivate) "Only approved followers see your content"
                        else "Anyone can see your content",
                        color = Color.Gray, fontSize = 13.sp
                    )
                }
                Switch(
                    checked = isPrivate,
                    onCheckedChange = { isPrivate = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color.Cyan,
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f)
                    )
                )
            }

            // Error message
            errorMessage?.let { msg ->
                Spacer(Modifier.height(16.dp))
                Text(msg, color = Color.Red, fontSize = 14.sp, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(60.dp))
        }
    }
}

// MARK: - Profile Field Component

@Composable
private fun ProfileField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    prefix: String? = null,
    error: String? = null,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = Color.Gray) },
            prefix = prefix?.let { { Text(it, color = Color.Gray) } },
            trailingIcon = trailingIcon,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color.Cyan,
                unfocusedBorderColor = Color.Gray.copy(alpha = 0.4f),
                focusedContainerColor = Color.White.copy(alpha = 0.05f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                cursorColor = Color.Cyan
            ),
            shape = RoundedCornerShape(12.dp),
            isError = error != null
        )
        error?.let { Text(it, color = Color.Red, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp)) }
    }
}

// MARK: - Firebase Storage Upload
// Matches iOS userService.updateProfileImage(userID:imageData:)

private suspend fun uploadProfileImage(
    context: Context,
    userID: String,
    uri: Uri,
    onProgress: (Float) -> Unit
): String {
    val storage = FirebaseStorage.getInstance()
    val ref = storage.reference.child("profileImages/$userID.jpg")

    val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
        ?: throw Exception("Could not read image data")

    val uploadTask = ref.putBytes(bytes)

    // Track progress
    uploadTask.addOnProgressListener { snapshot ->
        val progress = snapshot.bytesTransferred.toFloat() / snapshot.totalByteCount
        onProgress(progress)
    }

    uploadTask.await()
    val downloadURL = ref.downloadUrl.await()
    if (BuildConfig.DEBUG) { println("✅ EDIT PROFILE: Image uploaded → $downloadURL") }
    return downloadURL.toString()
}