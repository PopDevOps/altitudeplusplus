package com.altitudeplusplus.android.playback

/** Result of checking one stream for offline playback. */
sealed interface OfflineReport {
    val title: String

    /** Unencrypted clip: no license involved. */
    data class Unencrypted(override val title: String) : OfflineReport

    /** [serverAnswer] is the license server's reply to a saved-license request. */
    data class Protected(override val title: String, val serverAnswer: String) : OfflineReport
}
