package com.stitchsocial.club.views

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
import kotlinx.coroutines.delay

/**
 * EditProfileView.kt - FIXED compilation errors
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileView(
    userID: String,
    currentUserName: String = "User",
    currentUsername: String = "user",
    currentUserImage: String? = null,
    currentBio: String = "",
    onSave: (String, String, String, Uri?) -> Unit = { _, _, _, _ -> },
    onCancel: () -> Unit = {}
) {
    // State variables
    var displayName by remember { mutableStateOf(currentUserName) }
    var username by remember { mutableStateOf(currentUsername) }
    var bio by remember { mutableStateOf(currentBio) }
    var selectedImage by remember { mutableStateOf<Uri?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var usernameAvailable by remember { mutableStateOf<Boolean?>(null) }
    var isCheckingUsername by remember { mutableStateOf(false) }

    // Update fields when props change
    LaunchedEffect(currentUserName, currentUsername, currentBio) {
        displayName = currentUserName
        username = currentUsername
        bio = currentBio
    }

    // Username availability check with debouncing
    LaunchedEffect(username) {
        if (username != currentUsername && username.length >= 3) {
            isCheckingUsername = true
            delay(500) // Debounce

            // Simple validation - in real app, call UserService
            usernameAvailable = username.matches(Regex("^[a-zA-Z0-9_]+$")) && username.length <= 20
            isCheckingUsername = false
        } else if (username == currentUsername) {
            usernameAvailable = true
            isCheckingUsername = false
        } else {
            usernameAvailable = null
            isCheckingUsername = false
        }
    }

    // Image picker
    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> selectedImage = uri }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Profile", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, "Cancel", tint = Color.Gray)
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            isLoading = true
                            onSave(displayName.trim(), bio.trim(), username.trim(), selectedImage)
                        },
                        enabled = displayName.trim().isNotEmpty() &&
                                username.trim().length >= 3 &&
                                (usernameAvailable == true) &&
                                !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.Cyan,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "Save",
                                color = if (displayName.trim().isNotEmpty() &&
                                    username.trim().length >= 3 &&
                                    usernameAvailable == true) Color.Cyan else Color.Gray
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // Profile Image Section
            Box(
                modifier = Modifier.size(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .border(2.dp, Color.Cyan, CircleShape)
                        .clickable { imageLauncher.launch("image/*") }
                ) {
                    when {
                        selectedImage != null -> {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(selectedImage)
                                    .build(),
                                contentDescription = "Selected image",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        !currentUserImage.isNullOrEmpty() -> {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(currentUserImage)
                                    .build(),
                                contentDescription = "Current image",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        else -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Gray.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    "Default Avatar",
                                    modifier = Modifier.size(60.dp),
                                    tint = Color.Gray
                                )
                            }
                        }
                    }
                }

                // Camera icon
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .offset(x = 35.dp, y = 35.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.8f))
                        .border(2.dp, Color.White, CircleShape)
                        .clickable { imageLauncher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        "Change photo",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                "Tap to change photo",
                color = Color.Gray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Display Name Field
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Display Name", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("${displayName.length}/30", color = Color.Gray, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { if (it.length <= 30) displayName = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Enter display name", color = Color.Gray) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.Cyan,
                        unfocusedBorderColor = Color.Gray,
                        cursorColor = Color.Cyan
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Username Field
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Username", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when {
                            isCheckingUsername -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    color = Color.Cyan,
                                    strokeWidth = 1.dp
                                )
                            }
                            usernameAvailable == true -> {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Available",
                                    tint = Color.Green,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            usernameAvailable == false -> {
                                Icon(
                                    Icons.Default.Cancel,
                                    contentDescription = "Taken",
                                    tint = Color.Red,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("${username.length}/20", color = Color.Gray, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { newValue ->
                        // Filter valid characters
                        val filtered = newValue.filter { char -> char.isLetterOrDigit() || char == '_' }
                        if (filtered.length <= 20) {
                            username = filtered.lowercase()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Enter username", color = Color.Gray) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    isError = usernameAvailable == false,
                    supportingText = {
                        when {
                            usernameAvailable == false -> {
                                Text("Username not available", color = Color.Red)
                            }
                            username.length < 3 && username.isNotEmpty() -> {
                                Text("Minimum 3 characters", color = Color(0xFFFFA500))
                            }
                            usernameAvailable == true && username != currentUsername -> {
                                Text("Username available!", color = Color.Green)
                            }
                            else -> null
                        }
                    },
                    leadingIcon = {
                        Text("@", color = Color.Gray, fontSize = 16.sp)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = when {
                            usernameAvailable == true -> Color.Green
                            usernameAvailable == false -> Color.Red
                            else -> Color.Cyan
                        },
                        unfocusedBorderColor = Color.Gray,
                        cursorColor = Color.Cyan
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Bio Field
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Bio", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("${bio.length}/150", color = Color.Gray, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = bio,
                    onValueChange = { if (it.length <= 150) bio = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Tell us about yourself", color = Color.Gray) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.Cyan,
                        unfocusedBorderColor = Color.Gray,
                        cursorColor = Color.Cyan
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}