package moe.kabii.discord.event.guild

import discord4j.core.`object`.entity.channel.TextChannel
import discord4j.core.event.domain.message.MessageBulkDeleteEvent
import kotlinx.coroutines.reactive.awaitSingle
import moe.kabii.LOG
import moe.kabii.data.mongodb.GuildConfigurations
import moe.kabii.discord.command.kizunaColor
import moe.kabii.discord.event.EventListener
import moe.kabii.structure.snowflake
import moe.kabii.structure.stackTraceString
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux

object MessageBulkDeletionListener : EventListener<MessageBulkDeleteEvent>(MessageBulkDeleteEvent::class) {
    override suspend fun handle(event: MessageBulkDeleteEvent) {
        val config = GuildConfigurations.getOrCreateGuild(event.guildId.asLong())

        val deleteLogs = config.logChannels()
            .filter { channel -> channel.logSettings.deleteLog }
        if(deleteLogs.none()) return

        val eventChannel = event.channel
            .ofType(TextChannel::class.java)
            .awaitSingle()

        val authorCount = event.messages
            .map { message ->
                message.author.map { author -> author.id.asLong() }
                    .orElse(0L)
            }
            .distinct()
        val messageCount = event.messages.size

        deleteLogs.toFlux()
            .map { log -> log.channelID.snowflake }
            .flatMap { logID ->
                event.guild.flatMap { guild ->
                    guild.getChannelById(logID)
                }
            }
            .ofType(TextChannel::class.java)
            .flatMap { log ->
                log.createEmbed { spec ->
                    kizunaColor(spec)
                    spec.setDescription("$messageCount messages from $authorCount users were bulk-deleted in ${eventChannel.name}.")
                }
            }.onErrorResume { t ->
                LOG.info("Exception caught sending bulk delete log :: ${t.message}")
                LOG.debug(t.stackTraceString)
                Mono.empty()
            }
            .subscribe()
    }
}