package moe.kabii.command.commands.trackers

import moe.kabii.command.Command
import moe.kabii.data.relational.streams.TrackedStreams
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

object UntrackEmpty : Command("untrackempty") {
    override val wikiPath: String? = null
    init {
        terminal {
            newSuspendedTransaction {
                // todo this will be automatic task at some point, or fix how getActiveTargets() only pulls when videos are updated thus resulting in some channels never being checked
                val empty = TrackedStreams.StreamChannel.all()
                    .filter { channel -> channel.targets.empty() }
                    .forEach { channel ->
                        println("Untracking: ${channel.site}/${channel.siteChannelID}")
                        channel.delete()
                    }
            }
        }
    }
}