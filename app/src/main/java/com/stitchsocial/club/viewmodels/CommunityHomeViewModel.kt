/*
 * CommunityHomeViewModel.kt
 * STITCH SOCIAL - ANDROID KOTLIN
 *
 * Layer 5: ViewModels - the community home page's data.
 *
 * Loads the viewer's membership + profile, the top members (and their
 * avatars), recent posts and the community_home extras in parallel. Each is
 * independent: one failing read leaves its own section empty and the rest of
 * the page intact. Live state is NOT here — it stays in the view, where the
 * ghost-recovery and join logic it depends on already lives.
 */

package com.stitchsocial.club.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.stitchsocial.club.community.CommunityHome
import com.stitchsocial.club.community.CommunityHomeService
import com.stitchsocial.club.community.CommunityMembership
import com.stitchsocial.club.community.CommunityPost
import com.stitchsocial.club.community.CommunityPostType
import com.stitchsocial.club.community.CommunityUserProfile
import com.stitchsocial.club.community.InterestCardSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class CommunityHomeState(
    /** First load only; a pull-to-refresh uses [isRefreshing]. */
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val membership: CommunityMembership? = null,
    val viewer: CommunityUserProfile? = null,
    /** users/{communityID} — the creator's live profile (image, name). */
    val creator: CommunityUserProfile? = null,
    val topMembers: List<CommunityMembership> = emptyList(),
    val posts: List<CommunityPost> = emptyList(),
    /** userID -> profile for top members and post authors. */
    val profiles: Map<String, CommunityUserProfile> = emptyMap(),
    /** null when community_home failed or hasn't answered: render without it. */
    val home: CommunityHome? = null,
) {
    /** Most-hyped video posts, for the Highlights strip. */
    val highlights: List<CommunityPost>
        get() = posts
            .filter { it.isVideo && it.hypeCount > 0 }
            .sortedByDescending { it.hypeCount }
            .take(6)
}

val CommunityPost.isVideo: Boolean
    get() = postType == CommunityPostType.VIDEO_CLIP ||
        postType == CommunityPostType.VIDEO_LINK ||
        postType == CommunityPostType.AUTO_VIDEO_ANNOUNCEMENT ||
        !videoURL.isNullOrBlank()

class CommunityHomeViewModel(
    private val communityID: String,
    private val userID: String,
    private val service: CommunityHomeService = CommunityHomeService.shared,
) : ViewModel() {

    private val _state = MutableStateFlow(CommunityHomeState())
    val state: StateFlow<CommunityHomeState> = _state.asStateFlow()

    private val db = FirebaseFirestore.getInstance("stitchfin")

    /**
     * Call each time the page appears. The ViewModel outlives the page (it is
     * keyed per community in the Activity's store), so a second visit shows
     * what it had at once and reloads quietly behind it.
     */
    fun onAppear() = load(isRefresh = false)

    /** Pull-to-refresh: reload everything, with the indicator. */
    fun refresh() = load(isRefresh = true)

    private fun load(isRefresh: Boolean) {
        viewModelScope.launch {
            _state.update { if (isRefresh) it.copy(isRefreshing = true) else it }
            coroutineScope {
                val membership = async { service.fetchMembership(communityID, userID) }
                val members = async { service.fetchTopMembers(communityID) }
                val posts = async { service.fetchPosts(communityID) }
                val home = async { service.fetchHome(communityID) }
                // Viewer + creator in the same batch read as the avatars below
                // would serialise them behind members/posts; read them now.
                val people = async { service.fetchProfiles(listOf(userID, communityID)) }

                // The page can draw as soon as the Firestore reads land; the
                // callable (cold start can take seconds) fills in after.
                val m = membership.await()
                val top = members.await()
                val p = posts.await()
                val base = people.await()
                _state.update {
                    it.copy(
                        isLoading = false,
                        // The spinner ends with the Firestore reads; a cold
                        // callable fills its sections in when it answers.
                        isRefreshing = false,
                        membership = m,
                        topMembers = top,
                        posts = p,
                        viewer = base[userID] ?: it.viewer,
                        creator = base[communityID] ?: it.creator,
                        profiles = it.profiles + base,
                    )
                }

                launch {
                    val ids = top.take(10).map { it.userID } + p.map { it.authorID }
                    val more = service.fetchProfiles(ids.filter { it !in base })
                    _state.update { it.copy(profiles = it.profiles + more) }
                }

                val h = home.await()
                _state.update {
                    // Keep the last good answer on a failed refresh.
                    it.copy(home = h ?: it.home)
                }
            }
        }
    }

    // ── Mutations from the page ─────────────────────────────────────────────

    fun prependPost(post: CommunityPost) {
        _state.update { it.copy(posts = listOf(post) + it.posts.filter { p -> p.id != post.id }) }
    }

    fun removePost(postID: String) {
        _state.update { it.copy(posts = it.posts.filter { p -> p.id != postID }) }
    }

    /** Same write as before the redesign: a plain increment on the post. */
    fun hype(post: CommunityPost) {
        viewModelScope.launch {
            runCatching {
                db.collection("communities").document(communityID)
                    .collection("posts").document(post.id)
                    .update("hypeCount", FieldValue.increment(1L)).await()
                _state.update { s ->
                    s.copy(posts = s.posts.map { p -> if (p.id == post.id) p.copy(hypeCount = p.hypeCount + 1) else p })
                }
            }
        }
    }

    /** After the owner edits their switches in settings. */
    fun applyInterestCards(settings: InterestCardSettings) {
        _state.update { s ->
            val h = s.home ?: return@update s
            val owner = h.owner ?: return@update s
            s.copy(home = h.copy(owner = owner.copy(interestCards = settings)))
        }
    }
}

class CommunityHomeViewModelFactory(
    private val communityID: String,
    private val userID: String,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CommunityHomeViewModel(communityID, userID) as T
}
