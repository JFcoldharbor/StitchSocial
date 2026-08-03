package com.stitchsocial.club.services

/**
 * Where each video was when you last left it.
 *
 * iOS keeps playback position across a mid-scroll exit — swipe away, come back,
 * and the stitch picks up where it stopped instead of restarting. Android
 * couldn't: the player is remembered per composable, so once a page scrolls out
 * of the pager's window it's disposed and the position dies with it.
 *
 * Process-scoped and deliberately small. Positions are worth nothing across a
 * relaunch — you don't come back an hour later expecting to be 12 seconds into a
 * reply — so this never touches disk.
 */
object VideoPositionMemory {

    /** Below this a "resume" is indistinguishable from a restart, and seeking
     *  costs a decode. */
    private const val MIN_MEANINGFUL_MS = 2_000L

    /** Bounded so a long session can't grow it without limit. Oldest out first. */
    private const val MAX_ENTRIES = 60

    private val positions = LinkedHashMap<String, Long>(16, 0.75f, true)

    @Synchronized
    fun save(videoID: String, positionMs: Long, durationMs: Long) {
        if (videoID.isEmpty() || positionMs < MIN_MEANINGFUL_MS) return
        // Within a couple of seconds of the end, resuming means watching the
        // last blink of a video. Treat it as finished.
        if (durationMs > 0 && positionMs > durationMs - MIN_MEANINGFUL_MS) {
            positions.remove(videoID)
            return
        }
        positions[videoID] = positionMs
        if (positions.size > MAX_ENTRIES) {
            val oldest = positions.keys.firstOrNull()
            if (oldest != null) positions.remove(oldest)
        }
    }

    @Synchronized
    fun get(videoID: String): Long = positions[videoID] ?: 0L

    @Synchronized
    fun clear() = positions.clear()
}
