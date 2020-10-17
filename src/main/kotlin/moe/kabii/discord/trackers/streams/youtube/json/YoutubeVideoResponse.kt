package moe.kabii.discord.trackers.streams.youtube.json

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import moe.kabii.rusty.Try
import java.time.Duration

@JsonClass(generateAdapter = true)
data class YoutubeVideoResponse(
    val items: List<YoutubeVideo>
)

@JsonClass(generateAdapter = true)
data class YoutubeVideo(
    val id: String,
    val snippet: YoutubeVideoSnippet,
    val contentDetails: YoutubeVideoContentDetails
)

@JsonClass(generateAdapter = true)
data class YoutubeVideoSnippet(
    val channelId: String,
    val title: String,
    val description: String,
    val thumbnails: YoutubeThumbnails,
    val channelTitle: String,
    @Json(name="liveBroadcastContent") val _liveBroadcastContent: String
) {

    // "live", "none" , or "upcoming"
    @Transient val live: Boolean = _liveBroadcastContent == "live"
}

@JsonClass(generateAdapter = true)
data class YoutubeVideoContentDetails(
    @Json(name="duration") val _rawDuration: String
) {

    @Transient val duration: Duration? = Try {
        // shouldn't fail but we definitely do not want an exception here
        Duration.parse(_rawDuration)
    }.result.orNull()
}