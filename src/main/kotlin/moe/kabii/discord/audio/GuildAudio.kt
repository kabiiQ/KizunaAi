package moe.kabii.discord.audio

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import discord4j.common.util.Snowflake
import discord4j.core.GatewayDiscordClient
import discord4j.core.`object`.entity.Member
import discord4j.core.`object`.entity.channel.VoiceChannel
import discord4j.rest.util.Permission
import discord4j.voice.AudioProvider
import discord4j.voice.VoiceConnection
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.kabii.LOG
import moe.kabii.command.commands.audio.filters.FilterFactory
import moe.kabii.command.hasPermissions
import moe.kabii.data.mongodb.GuildConfiguration
import moe.kabii.data.mongodb.GuildConfigurations
import moe.kabii.data.mongodb.guilds.MusicSettings
import moe.kabii.rusty.Err
import moe.kabii.rusty.Ok
import moe.kabii.rusty.Result
import moe.kabii.util.DurationFormatter
import moe.kabii.util.extensions.tryAwait
import kotlin.reflect.KProperty1

// contains the audio providers and current audio queue for a guild
data class GuildAudio(
    val manager: AudioManager,
    val guildId: Long,
    var player: AudioPlayer,
    var provider: AudioProvider,
    val queue: MutableList<AudioTrack> = mutableListOf()
) {
    val discord: AudioConnection = AudioConnection(this)

    var ending: Boolean = false
    var looping: Boolean = false
    private val queueMutex = Mutex()

    val playing: Boolean
        get() = player.playingTrack != null || queue.isNotEmpty()

    // queue with the track currently being played at the front.
    val playlist: List<AudioTrack>
        get() = if (player.playingTrack != null) listOf(player.playingTrack) + queue else queue.toList()

    val duration: Long?
        get() = if(playlist.none { track -> track.info.isStream })
            playlist.sumOf { track -> track.duration - track.position }
        else null

    val formatDuration: String?
        get() = duration?.let(::DurationFormatter)
            ?.let(DurationFormatter::colonTime)

    // queue song for a user, returns false if user is over quota
    suspend fun tryAdd(track: AudioTrack, member: Member? = null, position: Int? = null, checkLimits: Boolean = true): Boolean {
        val maxTracksUser = GuildConfigurations.getOrCreateGuild(guildId).musicBot.maxTracksUser
        val meta = track.userData as? QueueData
        checkNotNull(meta) { "AudioTrack has no origin information: ${track.info}" }
        if (member != null && checkLimits && maxTracksUser != 0L) {
            val inQueue = queue.count { queuedTrack -> (queuedTrack.userData as QueueData).author == meta.author }
            if (inQueue >= maxTracksUser) {
                if (!member.hasPermissions(Permission.MANAGE_MESSAGES)) return false
            }
        }
        if (position != null) queue.add(position, track) else queue.add(track)
        saveQueue()
        return true
    }

    suspend fun forceAdd(track: AudioTrack, position: Int? = null): Boolean {
        if(position != null) queue.add(position, track) else queue.add(track)
        saveQueue()
        return true
    }

     suspend fun joinChannel(channel: VoiceChannel): Result<VoiceConnection, Throwable> {
         discord.mutex.withLock {
             val config = GuildConfigurations.getOrCreateGuild(channel.guildId.asLong())
             val join = channel.join { spec ->
                 spec.setProvider(this.provider)
             }.tryAwait()
             if (join is Ok) {
                 discord.connection = join.value
                 config.musicBot.lastChannel = channel.id.asLong()
                 config.save()
             }
             return join
         }
     }

    suspend fun refreshAudio(voice: VoiceChannel?): GuildAudio {
        discord.mutex.withLock {
            // save current playback state if track is playing
            val playing = player.playingTrack
            val resumeTrack = playing?.makeClone()?.apply {
                position = playing.position
                userData = playing.userData
            }
            // create new player/provider
            val (player, provider) = AudioManager.createAudioComponents()
            val newAudio = this.copy(player = player, provider = provider)
            this.ending = true
            this.player.stopTrack()
            with(AudioManager.guilds) {
                synchronized(this) {
                    put(guildId, newAudio)
                }
            }
            if(voice != null) {
                if(discord.connection != null) {
                    discord.connection!!.disconnect().tryAwait().ifErr { err ->
                        LOG.debug("Error resetting audio connection: $err")
                    }
                }
                val join = newAudio.joinChannel(voice)
                if(join is Err) {
                    newAudio.discord.connection = null
                    throw IllegalStateException("Voice connection lost: $join")
                }
            }
            newAudio.player.startTrack(resumeTrack, false)
            return newAudio
        }
    }

    // this needs to be called anywhere we manually edit the queue, adding/anything playing the next track is encapsulated but shuffling etc are not currently
    private suspend fun saveQueue() {
        val config = GuildConfigurations.getOrCreateGuild(guildId)
        // save copy of queue to db with just serializable info that we need to requeue
        config.musicBot.activeQueue = playlist.map { track ->
            val data = track.userData as QueueData
            MusicSettings.QueuedTrack(
                uri = track.info.uri,
                author_name = data.author_name,
                author = data.author.asLong(),
                originChannel = data.originChannel.asLong()
            )
        }
        config.save()
    }

    suspend fun <R> editQueue(block: suspend MutableList<AudioTrack>.() -> R): R {
        val edit = queueMutex.withLock {
            block(queue)
        }
        saveQueue()
        return edit
    }

    fun <R> editQueueSync(block: suspend MutableList<AudioTrack>.() -> R): R = runBlocking { editQueue(block) }

}

data class QueueData(
    val audio: GuildAudio,
    val discord: GatewayDiscordClient,
    val author_name: String, // just caching the author's username as it is unlikely to change and is only used in output
    val author: Snowflake,
    val originChannel: Snowflake,
    var volume: Int,

    val votes: MutableSet<Snowflake> = mutableSetOf(),
    val voting: Mutex = Mutex(),
    var endMarkerMillis: Long? = null,
    val associatedMessages: MutableList<BotMessage> = mutableListOf(),
    val audioFilters: FilterFactory = FilterFactory()
) {
    sealed class BotMessage(val channelID: Snowflake, val messageID: Snowflake, private val cfgProp: KProperty1<MusicSettings, Boolean>) {
        class NPEmbed(chan: Snowflake, msg: Snowflake) : BotMessage(chan, msg, MusicSettings::deleteOldBotMessages)
        class TrackQueued(chan: Snowflake, msg: Snowflake) : BotMessage(chan, msg, MusicSettings::deleteOldBotMessages)
        class UserPlayCommand(chan: Snowflake, msg: Snowflake) : BotMessage(chan, msg, MusicSettings::deleteUserCommands)

        fun enabledFor(config: GuildConfiguration): Boolean = cfgProp.get(config.musicBot)
    }

    var silent = false // don't post added to queue message. for bulk actions, etc
    var apply = false // if this track is stopped, restart it. for applying filters
}