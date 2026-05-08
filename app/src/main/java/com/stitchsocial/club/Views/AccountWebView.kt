/*
 * AccountWebView.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Mirror of Swift AccountWebView.swift — embeds the external account portal
 * (https://stitchsocial.me/app/account) with a Firebase ID token query param.
 */

package com.stitchsocial.club.views

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import com.stitchsocial.club.BuildConfig

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AccountWebView(
    initialPath: String = "/app/account",
    onDismiss: () -> Unit
) {
    var authToken by remember { mutableStateOf<String?>(null) }
    var tokenError by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var pageTitle by remember { mutableStateOf("Manage Account") }

    LaunchedEffect(Unit) {
        try {
            val user = FirebaseAuth.getInstance().currentUser
            if (user == null) {
                tokenError = true
                return@LaunchedEffect
            }
            val result = user.getIdToken(false).await()
            authToken = result.token
            if (authToken.isNullOrEmpty()) tokenError = true
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { println("WEBVIEW: Token error - ${e.message}") }
            tokenError = true
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Top bar
        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 40.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Done", color = Color.Cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    pageTitle, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White, modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                if (isLoading) {
                    CircularProgressIndicator(color = Color.Cyan, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                } else {
                    Spacer(Modifier.size(60.dp))
                }
            }

            // Content
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    tokenError -> AuthErrorView(onRetry = { tokenError = false; authToken = null })
                    authToken == null -> AuthenticatingView()
                    else -> {
                        val token = authToken!!
                        val url = "https://stitchsocial.me$initialPath?token=$token&app=true"
                        AndroidView(
                            modifier = Modifier.fillMaxSize().background(Color.Black),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    setBackgroundColor(AndroidColor.BLACK)
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView, finishedUrl: String) {
                                            super.onPageFinished(view, finishedUrl)
                                            isLoading = false
                                            view.title?.let { pageTitle = it }
                                            // Match iOS dark-mode background tweak
                                            view.evaluateJavascript(
                                                "document.body.style.backgroundColor='#000';" +
                                                "document.body.style.color='#fff';",
                                                null
                                            )
                                        }
                                    }
                                    webChromeClient = object : WebChromeClient() {
                                        override fun onReceivedTitle(view: WebView?, title: String?) {
                                            title?.let { pageTitle = it }
                                        }
                                    }
                                    loadUrl(url)
                                }
                            }
                        )

                        if (isLoading) {
                            Box(
                                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                                    Text("Loading...", fontSize = 13.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthenticatingView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
        Spacer(Modifier.height(12.dp))
        Text("Authenticating...", color = Color.Gray, fontSize = 14.sp)
    }
}

@Composable
private fun AuthErrorView(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Warning, null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text("Authentication Error", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(
            "Please sign in again and try once more.",
            fontSize = 13.sp, color = Color.Gray, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Try Again", color = Color.Black, fontWeight = FontWeight.SemiBold)
        }
    }
}
