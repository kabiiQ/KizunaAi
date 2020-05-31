package moe.kabii.discord.command

import discord4j.core.`object`.entity.Guild
import discord4j.core.`object`.entity.Member
import discord4j.core.`object`.entity.Role
import discord4j.core.`object`.entity.channel.TextChannel
import discord4j.rest.util.Permission
import discord4j.core.event.domain.message.MessageCreateEvent
import kotlinx.coroutines.reactive.awaitFirstOrNull
import moe.kabii.data.Keys
import reactor.core.publisher.Flux

class MemberPermissionsException(vararg val perms: Permission) : RuntimeException()
class BotAdminException : RuntimeException()

@Throws(BotAdminException::class)
fun MessageCreateEvent.verifyBotAdmin() {
    val admin = BotAdmin.check(message.author.get().id.asLong(), message.channelId.asLong())
    if(!admin) throw BotAdminException()
}

object BotAdmin {
    fun check(userID: Long? = null, channelID: Long? = null): Boolean {
        if(userID != null && Keys.config[Keys.Admin.users].contains(userID)) return true
        if(channelID != null && Keys.config[Keys.Admin.channels].contains(channelID)) return true
        return false
    }
}

suspend fun Member.hasPermissions(vararg permissions: Permission): Boolean {
    if(BotAdmin.check(userID = id.asLong())) return true
    return basePermissions.awaitFirstOrNull()?.containsAll(permissions.toList()) == true
}

@Throws(MemberPermissionsException::class)
suspend fun Member.verify(vararg permissions: Permission) {
    if(this.hasPermissions(*permissions)) return
    throw MemberPermissionsException(*permissions)
}

suspend fun Member.hasPermissions(channel: TextChannel, vararg permissions: Permission): Boolean = channel.getEffectivePermissions(id).awaitFirstOrNull()?.containsAll(permissions.toList()) == true

@Throws(MemberPermissionsException::class)
suspend fun Member.channelVerify(channel: TextChannel, vararg permissions: Permission) {
    if(hasPermissions(channel, *permissions)) return
    throw MemberPermissionsException(*permissions)
}

object PermissionUtil {
    fun filterSafeRoles(roles: Flux<Role>, forMember: Member, inGuild: Guild, managed: Boolean, everyone: Boolean): Flux<Role> {
        return if (inGuild.ownerId == forMember.id) roles else // owner can apply all roles, otherwise get any roles below
            forMember.highestRole
                .flatMap(Role::getPosition)
                .flatMapMany { highest ->
                    roles.filter { role -> role.position.block() < highest }
                }
                .filter { role -> managed || !role.isManaged }
                .filter { role -> everyone || !role.isEveryone }
    }

    fun isSafeRole(role: Role, forMember: Member, inGuild: Guild, managed: Boolean, everyone: Boolean): Boolean =
        filterSafeRoles(Flux.just(role), forMember, inGuild, managed, everyone).hasElement(role).block()


}
