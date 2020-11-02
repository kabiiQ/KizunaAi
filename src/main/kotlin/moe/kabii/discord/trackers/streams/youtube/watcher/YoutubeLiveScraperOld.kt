package moe.kabii.discord.trackers.streams.youtube.watcher

import discord4j.core.GatewayDiscordClient
import kotlinx.coroutines.delay
import moe.kabii.LOG
import moe.kabii.data.relational.streams.DBYoutubeStreams
import moe.kabii.data.relational.streams.TrackedStreams
import moe.kabii.discord.trackers.streams.youtube.YoutubeChannelError
import moe.kabii.discord.trackers.streams.youtube.YoutubeScraper
import moe.kabii.structure.extensions.loop
import moe.kabii.structure.extensions.stackTraceString
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Duration
import java.time.Instant
import kotlin.math.max

class YoutubeLiveScraperOld(discord: GatewayDiscordClient) : Runnable, YoutubeWatcher(discord) {
    override fun run() {
        loop {
            val start = Instant.now()

            try {
                val browser = YoutubeScraper()

                // target all tracked youtube channels that are not currently 'live'
                val checkChannels = newSuspendedTransaction {
                    TrackedStreams.StreamChannel.find {
                        TrackedStreams.StreamChannels.site eq TrackedStreams.DBSite.YOUTUBE
                    }
                        .associateBy(TrackedStreams.StreamChannel::siteChannelID)
                        .filter { (id, _) ->
                            // we only want youtube channels that are not currently known to be live - cross-check with 'live' dbs
                            DBYoutubeStreams.YoutubeStream.findStream(id).empty()
                    }
                }

                // for now, this is called in-place, blocking the current thread. we really want to be careful about page scraping
                // too fast, so this is fine.
                checkChannels.forEach { (id, channel) ->
                    try {
                        checkChannel(channel, id, browser)
                    } catch(e: Exception) {
                        LOG.warn("Error updating YouTube channel: '$id' :: ${e.message}")
                        LOG.debug(e.stackTraceString)
                    }
                    delay((1000..2000).random().toLong())
                }

                browser.close()
            } catch(e: Exception) {
                LOG.error("Uncaught exception in ${Thread.currentThread().name} :: ${e.message}")
                LOG.debug(e.stackTraceString)
            }
            val runDuration = Duration.between(start, Instant.now())
            val delay = 90_000L - runDuration.toMillis()
            delay(max(delay, (1500L..3000L).random()))
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun checkChannel(channel: TrackedStreams.StreamChannel, channelId: String, scraper: YoutubeScraper) {

        // if this channel has no discord targets, do not page scrape, and delete from db to prevent further work
        if(untrackStaleEntity(channel)) {
            return
        }

        try {
            // this channel has no known stream info. check if it is currently live, and record this information
            val liveStream = scraper.getLiveStream(channelId)
            if (liveStream == null) return // stream not live

            // record stream in database
            newSuspendedTransaction {
                DBYoutubeStreams.YoutubeStream.new {
                    this.streamChannel = channel
                    this.youtubeVideoId = liveStream.id
                    this.lastTitle = liveStream.title
                    this.lastThumbnail = liveStream.thumbnail
                    this.lastChannelName = liveStream.channel.name
                    this.lastAvatar = liveStream.channel.avatar

                    this.peakViewers = 0
                    // start ticks at 0 for Youtube, we do not have 'initial' viewers when discovering streams
                    this.uptimeTicks = 0
                    this.averageViewers = 0
                }

                // post this live stream information to all targets
                channel.targets.forEach { target ->
                    try {
                        createLiveNotification(liveStream, target, new = true)
                    } catch (e: Exception) {
                        LOG.warn("Error while creating live notification for channel: ${liveStream.channel} :: ${e.message}")
                        LOG.debug(e.stackTraceString)
                    }
                }
            }

        } catch(e: Exception) {
            LOG.error("Problem checking YouTube stream '$channelId' :: ${e.message}")

            if(e is YoutubeChannelError && e.ytText.contains("This channel does not exist")) {
                LOG.info("Untracking YouTube channel '$channelId' as it no longer exists.")
                channel.delete()
            } else throw e
        }
    }
}