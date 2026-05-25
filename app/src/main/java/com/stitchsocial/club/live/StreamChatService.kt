package com.stitchsocial.club.live

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Listens to and writes into the per-stream `chat` subcollection. Mirrors
 * iOS `StreamChatService`. Singleton because there's only ever one active
 * stream chat at a time on a given device.
 */
class StreamChatService private constructor() {

    companion object {
        @Volatile private var INSTANCE: StreamChatService? = null
        fun getInstance(): StreamChatService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: StreamChatService().also { INSTANCE = it }
            }

        private const val TAG = "StreamChat"
        private const val MAX_VISIBLE = 80   // keep the in-memory buffer bounded
    }

    private val db = FirebaseFirestore.getInstance("stitchfin")
    private var listener: ListenerRegistration? = null

    private val _messages = MutableStateFlow<List<StreamChatMessage>>(emptyList())
    val messages: StateFlow<List<StreamChatMessage>> = _messages.asStateFlow()

    fun listen(communityID: String, streamID: String) {
        cleanup()
        Log.d(TAG, "💬 listening on communities/$communityID/streams/$streamID/chat")
        listener = db.collection("communities/$communityID/streams/$streamID/chat")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limitToLast(MAX_VISIBLE.toLong())
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "❌ chat listener error: ${err.localizedMessage}")
                    return@addSnapshotListener
                }
                val docs = snap?.documents.orEmpty()
                val parsed = docs.mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    StreamChatMessage.fromDoc(doc.id, data)
                }
                Log.d(TAG, "💬 snapshot: ${docs.size} raw, ${parsed.size} decoded")
                _messages.value = parsed
            }
    }

    fun cleanup() {
        listener?.remove()
        listener = null
        _messages.value = emptyList()
    }

    /**
     * Send a chat message. Returns when the Firestore write completes (or
     * fails silently with a logged warning). The caller may want to optimistic-
     * update the UI before this resolves — caller's choice.
     */
    suspend fun send(
        communityID: String,
        streamID: String,
        authorID: String,
        authorUsername: String,
        authorDisplayName: String,
        authorLevel: Int,
        isCreator: Boolean,
        body: String,
    ) {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return

        val id = UUID.randomUUID().toString()
        val payload = mapOf(
            "id" to id,
            // streamID + communityID match iOS StreamChatMessage's required
            // fields — without them, iOS Codable decode of the message fails
            // and the chat row never renders on iOS clients.
            "streamID" to streamID,
            "communityID" to communityID,
            "authorID" to authorID,
            "authorUsername" to authorUsername,
            "authorDisplayName" to authorDisplayName,
            "authorLevel" to authorLevel,
            "isCreator" to isCreator,
            "body" to trimmed,
            "messageType" to "chat",
            "createdAt" to Timestamp.now(),
        )
        Log.d(TAG, "💬 sending '$trimmed' from @$authorUsername to $streamID")
        runCatching {
            db.collection("communities/$communityID/streams/$streamID/chat")
                .document(id)
                .set(payload)
                .await()
            Log.d(TAG, "✅ chat write ok")
        }.onFailure {
            Log.w(TAG, "❌ chat send failed: ${it.localizedMessage}")
        }
    }
}
