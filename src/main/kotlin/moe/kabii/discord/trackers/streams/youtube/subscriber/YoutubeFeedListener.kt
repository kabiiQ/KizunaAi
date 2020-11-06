package moe.kabii.discord.trackers.streams.youtube.subscriber

import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.util.pipeline.*
import moe.kabii.LOG
import moe.kabii.data.Keys
import org.apache.commons.codec.digest.HmacAlgorithms
import org.apache.commons.codec.digest.HmacUtils

class YoutubeFeedListener(val manager: YoutubeSubscriptionManager) {

    private val signingKey = Keys.config[Keys.Youtube.signingKey]
    private val port = Keys.config[Keys.Youtube.callbackPort]

    val server = embeddedServer(Netty, port = port) {
        routing {

            get {
                // GET - subscription validation
                log(this)

                if(!call.request.origin.remoteHost.endsWith("google.com")) {
                    call.response.status(HttpStatusCode.Forbidden)
                    return@get
                }

                // if action is 'subscribe', validate this is a currently desired subscription
                val mode = call.parameters["hub.mode"]
                val channelTopic = call.parameters["hub.topic"]
                when(mode) {
                    "subscribe" -> {
                        if(!manager.currentSubscriptions.contains(channelTopic)) {
                            call.response.status(HttpStatusCode.NotFound) // return 404 per hubbub spec
                            LOG.debug("Subscription rejected: $channelTopic")
                            return@get
                        } // else continue verification
                    }
                    "unsubscribe" -> {} // allow unsubscription without validation
                    "denied" -> {
                        LOG.warn("Subscription denied: ${call.parameters}")
                        return@get // return 500
                    }
                    else -> {
                        // bad or no 'mode' header
                        call.response.status(HttpStatusCode.BadRequest)
                        return@get
                    }
                }

                val challenge = call.parameters["hub.challenge"]
                if(challenge != null) {
                    call.respondText(challenge, status = HttpStatusCode.OK)
                    LOG.debug("$mode validated: $channelTopic")
                }
            }

            post {
                // POST - feed updates
                log(this)

                if(!call.request.origin.remoteHost.endsWith("google.com")) {
                    call.response.status(HttpStatusCode.Forbidden)
                    return@post
                }

                // always return 2xx code per hubbub spec to avoid re-sending
                call.response.status(HttpStatusCode.OK)

                call.request.queryParameters["channel"]
                    .also { LOG.trace("POST channel: $it") }
                    ?: return@post

                val body = call.receiveStream().bufferedReader().readText()

                val signature = call.request.header("X-Hub-Signature")
                if(signature == null) {
                    LOG.warn("Payload received with no signature: $body")
                    return@post
                }

                // validate body signed against our secret
                val bodySignature = HmacUtils(HmacAlgorithms.HMAC_SHA_1, signingKey).hmacHex(body)
                if(signature != "sha1=$bodySignature") {
                    LOG.warn("Unable to verify payload signature: $body\nX-Hub-Signature: $signature\nCalculated signature: $signature")
                    return@post
                }

                // successfully acquired information on an updated video.
                // let youtubevideointake decide what to do with this information
                YoutubeVideoIntake.intakeXml(body)
            }
        }
    }

    private fun log(ctx: PipelineContext<Unit, ApplicationCall>) {
        LOG.trace(":$port - to ${ctx.call.request.origin.uri} - from ${ctx.call.request.origin.remoteHost}")
    }
}