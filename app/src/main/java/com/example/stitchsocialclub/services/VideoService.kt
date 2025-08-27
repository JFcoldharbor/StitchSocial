/*
 * VideoServiceImpl.kt - FIREBASE DATABASE NAME FIX
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * FIXED: Use "stitchfin" database instead of default database
 * This should stop the NOT_FOUND errors causing GPU thrashing
 */

package com.example.stitchsocialclub.services

import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.FirebaseFirestore
import com.example.stitchsocialclub.foundation.*
import kotlinx.coroutines.tasks.await

/**
 * VideoService implementation with FIXED database configuration
 * Uses "stitchfin" database instead of default
 */
class VideoServiceImpl {

    // FIXED: Use "stitchfin" database instead of default
    private val db: FirebaseFirestore by lazy {
        try {
            Firebase.firestore("stitchfin").also {
                println("VIDEO SERVICE: Connecting to 'stitchfin' database")
            }
        } catch (e: Exception) {
            println("VIDEO SERVICE: Failed to connect to 'stitchfin' database: ${e.message}")
            println("VIDEO SERVICE: Falling back to mock data")
            throw e
        }
    }

    /**
     * Get feed videos with proper database connection
     */
    suspend fun getFeedVideos(followingUsers: List<String>): List<SimpleThreadData> {
        return try {
            println("VIDEO SERVICE: Loading videos from stitchfin database")

            // Try to query the correct database
            val snapshot = db.collection("videos")
                .limit(10)
                .get()
                .await()

            if (snapshot.documents.isNotEmpty()) {
                println("VIDEO SERVICE: Found ${snapshot.documents.size} videos in stitchfin database")

                // Convert Firebase documents to our data structure
                snapshot.documents.mapIndexed { index, doc ->
                    val data = doc.data ?: emptyMap()

                    SimpleThreadData(
                        id = doc.id,
                        parentVideo = SimpleVideoMetadata(
                            id = doc.id,
                            title = data["title"] as? String ?: "Video ${index + 1}",
                            creatorName = data["creatorName"] as? String ?: "Creator ${index + 1}",
                            videoURL = data["videoURL"] as? String ?: "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
                        ),
                        childVideos = emptyList() // Simplified for now
                    )
                }
            } else {
                println("VIDEO SERVICE: No videos found in stitchfin database, using mock data")
                generateMockData()
            }

        } catch (e: Exception) {
            println("VIDEO SERVICE: Error connecting to stitchfin database: ${e.message}")
            println("VIDEO SERVICE: Using mock data instead")
            generateMockData()
        }
    }

    /**
     * Generate mock data when database is unavailable
     */
    private fun generateMockData(): List<SimpleThreadData> {
        println("VIDEO SERVICE: Generating mock data with real video URLs")

        return listOf(
            SimpleThreadData(
                id = "mock_thread_1",
                parentVideo = SimpleVideoMetadata(
                    id = "mock_video_1",
                    title = "Big Buck Bunny Short",
                    creatorName = "Blender Foundation",
                    videoURL = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
                ),
                childVideos = emptyList()
            ),
            SimpleThreadData(
                id = "mock_thread_2",
                parentVideo = SimpleVideoMetadata(
                    id = "mock_video_2",
                    title = "Elephants Dream Short",
                    creatorName = "Orange Open Movie Project",
                    videoURL = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"
                ),
                childVideos = emptyList()
            ),
            SimpleThreadData(
                id = "mock_thread_3",
                parentVideo = SimpleVideoMetadata(
                    id = "mock_video_3",
                    title = "Sintel Short Film",
                    creatorName = "Blender Institute",
                    videoURL = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4"
                ),
                childVideos = emptyList()
            )
        )
    }

    // Data classes for video structure
    data class SimpleThreadData(
        val id: String,
        val parentVideo: SimpleVideoMetadata,
        val childVideos: List<SimpleVideoMetadata>
    )

    data class SimpleVideoMetadata(
        val id: String,
        val title: String,
        val creatorName: String,
        val videoURL: String
    )
}