package moe.kabii.command.commands.audio.filters

import kotlinx.coroutines.reactive.awaitSingle
import moe.kabii.command.Command
import moe.kabii.command.commands.audio.AudioCommandContainer
import moe.kabii.discord.audio.AudioManager
import moe.kabii.discord.audio.QueueData

object FilterPresets : AudioCommandContainer {
    object DoubleTime : Command("dt", "doubletime") {
        override val wikiPath = "Music-Player#audio-manipulationfilters"

        init {
            discord {
                validateChannel(this)
                val audio = AudioManager.getGuildAudio(target.id.asLong())
                val track = audio.player.playingTrack
                if(track == null) {
                    error("There is no track currently playing.").awaitSingle()
                    return@discord
                }
                val data = track.userData as QueueData
                with(data.audioFilters) {
                    reset()
                    addExclusiveFilter(FilterType.Speed(1.25))
                }
                data.apply = true
                audio.player.stopTrack()
            }
        }
    }

    object Nightcore : Command("nightcore", "nc") {
        override val wikiPath = "Music-Player#audio-manipulationfilters"

        init {
            discord {
                validateChannel(this)
                val audio = AudioManager.getGuildAudio(target.id.asLong())
                val track = audio.player.playingTrack
                if(track == null) {
                    error("There is no track currently playing.").awaitSingle()
                    return@discord
                }
                val data = track.userData as QueueData
                with(data.audioFilters) {
                    reset()
                    addExclusiveFilter(FilterType.Speed(1.25))
                    addExclusiveFilter(FilterType.Pitch(1.25))
                }
                data.apply = true
                audio.player.stopTrack()
            }
        }
    }

    object Daycore : Command("daycore", "dc") {
        override val wikiPath = "Music-Player#audio-manipulationfilters"

        init {
            discord {
                validateChannel(this)
                val audio = AudioManager.getGuildAudio(target.id.asLong())
                val track = audio.player.playingTrack
                if(track == null) {
                    error("There is no track currently playing.").awaitSingle()
                    return@discord
                }
                val data = track.userData as QueueData
                with(data.audioFilters) {
                    reset()
                    addExclusiveFilter(FilterType.Speed(0.75))
                    addExclusiveFilter(FilterType.Pitch(0.75))
                }
                data.apply = true
                audio.player.stopTrack()
            }
        }
    }

    object ResetFilters : Command("reset") {
        override val wikiPath = "Music-Player#audio-manipulationfilters"

        init {
            discord {
                validateChannel(this)
                val audio = AudioManager.getGuildAudio(target.id.asLong())
                val track = audio.player.playingTrack
                if(track == null) {
                    error("There is no track currently playing.").awaitSingle()
                    return@discord
                }
                val data = track.userData as QueueData
                data.audioFilters.reset()
                data.apply = true
                audio.player.stopTrack()
            }
        }
    }
}