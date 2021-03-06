package moe.kabii.command.commands.configuration

import discord4j.rest.util.Permission
import kotlinx.coroutines.reactive.awaitSingle
import moe.kabii.command.Command
import moe.kabii.command.CommandContainer
import moe.kabii.command.verify
import moe.kabii.discord.util.DiscordBot

object CommandOptions : CommandContainer {
    object Prefix : Command("prefix", "setprefix", "prefix-set", "set-prefix", "changeprefix") {
        override val wikiPath = "Configuration#changing-command-prefix-andor-suffix"
        override val commandExempt = true

        init {
            discord {
                if(args.isEmpty()) {
                    // display current prefix
                    val prefix = config.prefix
                    embed("The current command prefix for **${target.name}** is **$prefix**. Command example for changing prefix: **prefix $prefix**.").awaitSingle()
                    return@discord
                }
                member.verify(Permission.MANAGE_GUILD)
                config.prefix = args[0]
                config.save()
                embed("Command prefix for **${target.name}** has been set to **${args[0]}** Commands are also accessible by using a mention as the prefix: <@${DiscordBot.selfId.asString()}>").awaitSingle()
            }
        }
    }

    object Suffix : Command("suffix", "setsuffix", "set-suffix", "changesuffix") {
        override val wikiPath = "Configuration#changing-command-prefix-andor-suffix"
        override val commandExempt = true

        init {
            discord {
                if(args.isEmpty()) {
                    val suffix = config.suffix
                    embed("The current command prefix for **${target.name}** is **$suffix**. Command example for changing suffix: **suffix desu**. The suffix can be removed with **suffix none**.").awaitSingle()
                    return@discord
                }
                member.verify(Permission.MANAGE_GUILD)
                val suffix = when(args[0]) {
                    "none", "remove", "reset" -> null
                    else -> args[0]
                }
                config.suffix = suffix
                config.save()
                if(suffix == null) {
                    embed("The command suffix for **${target.name}** has been removed.")
                } else {
                    embed("The command suffix for **${target.name}** has been set to **$suffix**.")
                }.awaitSingle()
            }
        }
    }
}