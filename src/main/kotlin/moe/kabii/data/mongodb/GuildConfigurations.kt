package moe.kabii.data.mongodb

import kotlinx.coroutines.runBlocking
import moe.kabii.data.mongodb.guilds.*
import moe.kabii.data.relational.twitter.TwitterTarget
import moe.kabii.structure.GuildID
import org.litote.kmongo.Id
import org.litote.kmongo.coroutine.updateOne
import org.litote.kmongo.newId
import java.util.concurrent.ConcurrentHashMap

object GuildConfigurations {
    val mongoConfigurations = MongoDBConnection.mongoDB.getCollection<GuildConfiguration>()
    val guildConfigurations: MutableMap<GuildID, GuildConfiguration>

    init {
        // grab existing guild configurations
        guildConfigurations = runBlocking {
            mongoConfigurations.find().toList()
                .associateBy(GuildConfiguration::guildid)
                .run { ConcurrentHashMap(this) }
        }
    }

    @Synchronized fun getOrCreateGuild(id: Long) = guildConfigurations.getOrPut(id) { GuildConfiguration(guildid = id) }
    @Synchronized fun getGuildForTwitch(twitchID: Long) = guildConfigurations.asSequence().find { it.value.options.linkedTwitchChannel?.twitchid == twitchID }?.value

    @Synchronized suspend fun findFeatures(guildId: Long?, channelId: Long?): Pair<GuildConfiguration?, FeatureChannel?> {
        if(guildId == null || channelId == null) return null to null
        val guildConfig = getOrCreateGuild(guildId)
        return guildConfig to guildConfig.getOrCreateFeatures(channelId)
    }

    @Synchronized suspend fun findFeatures(target: TwitterTarget): FeatureChannel? {
        val guildId = target.discordChannel.guild?.guildID
        if(guildId == null) return null
        val guildConfig = getOrCreateGuild(guildId)
        return guildConfig.getOrCreateFeatures(target.discordChannel.channelID)
    }
}

// per guild - guildconfiguration collection
data class GuildConfiguration(
    val _id: Id<GuildConfiguration> = newId(),
    val guildid: Long,
    var prefix: String = defaultPrefix,
    var suffix: String? = defaultSuffix,
    val options: OptionalFeatures = OptionalFeatures(),
    val customCommands: CustomCommands = CustomCommands(),
    val autoRoles: AutoRoles = AutoRoles(),
    val selfRoles: SelfRoles = SelfRoles(),
    val guildSettings: GuildSettings = GuildSettings(),
    val tempVoiceChannels: TempChannels = TempChannels(),
    val commandFilter: CommandFilter = CommandFilter(),
    val musicBot: MusicSettings = MusicSettings(),
    var starboard: StarboardSetup? = null) {

    companion object {
        const val defaultPrefix = ";"
        const val defaultSuffix = "desu"
    }

    fun logChannels() = options.featureChannels.values.toList()
        .filter(FeatureChannel::logChannel)

    suspend fun save() {
        GuildConfigurations.mongoConfigurations.updateOne(this, upsert)
    }

    suspend fun getOrCreateFeatures(channel: Long): FeatureChannel = options.featureChannels.getOrPut(channel) {
        FeatureChannel(channel).also { save() }
    }

    suspend fun removeSelf() {
        GuildConfigurations.guildConfigurations.remove(guildid)
        GuildConfigurations.mongoConfigurations.deleteOneById(this._id)
    }
}
