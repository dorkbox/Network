package dorkbox.network.connection

import dorkbox.network.Configuration
import dorkbox.network.aeron.client.ClientRejectedException
import dorkbox.network.aeron.client.ClientTimedOutException
import dorkbox.network.connection.registration.Registration
import io.aeron.FragmentAssembler
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
import kotlinx.coroutines.launch
import org.agrona.DirectBuffer
import org.slf4j.Logger
import java.security.SecureRandom

class ConnectionManagerClient<C : Connection>(logger: Logger, config: Configuration) : ConnectionManager<C>(logger, config) {
    // a one-time key for connecting
    private val oneTimePad = SecureRandom().nextInt()


    @Volatile
    var connectionInfo: ClientConnectionInfo? = null

    private var failed = false

    lateinit var handler: FragmentHandler
    lateinit var endPoint: EndPoint<C>
    var sessionId: Int = 0

    fun init(endPoint: EndPoint<C>) {
        this.endPoint = endPoint

        // now we have a bi-directional connection with the server on the handshake "socket".
        handler = FragmentAssembler(FragmentHandler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
            endPoint.actionDispatch.launch {
                val message = endPoint.readHandshakeMessage(buffer, offset, length, header)
                logger.debug("[{}] response: {}", sessionId, message)

                // it must be a registration message
                if (message !is Registration) {
                    logger.error("[{}] server returned unrecognized message: {}", sessionId, message)
                    return@launch
                }

                // it must be the correct state
                if (message.state != Registration.HELLO_ACK) {
                    logger.error("[{}] ignored message that is not HELLO_ACK", sessionId)
                    return@launch
                }

                if (message.sessionId != sessionId) {
                    logger.error("[{}] ignored message intended for another client", sessionId)
                    return@launch
                }

                // The message was intended for this client. Try to parse it as one of the available message types.
                connectionInfo = ClientConnectionInfo(
                        subscriptionPort = message.publicationPort,
                        publicationPort = message.subscriptionPort,
                        sessionId = oneTimePad xor message.oneTimePad,
                        streamId = oneTimePad xor message.streamId,
                        publicKey = message.publicKey!!)

                connectionInfo!!.log(sessionId, logger)
            }
        })
    }

    @Throws(ClientTimedOutException::class, ClientRejectedException::class)
    suspend fun initHandshake(mediaConnection: MediaDriverConnection, connectionTimeoutMS: Long) : ClientConnectionInfo {
        val registrationMessage = Registration.hello(
                oneTimePad = oneTimePad,
                publicKey = config.settingsStore.getPublicKey()!!,
                registrationData = config.serialization.getKryoRegistrationDetails()
        )


        // Send the one-time pad to the server.
        endPoint.writeHandshakeMessage(mediaConnection.publication, registrationMessage)
        sessionId = mediaConnection.publication.sessionId()


        logger.debug("waiting for response")

        // block until we receive the connection information from the server

        var pollCount: Int
        val subscription = mediaConnection.subscription
        val pollIdleStrategy = endPoint.config.pollIdleStrategy

        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < connectionTimeoutMS) {
            pollCount = subscription.poll(handler, 1024)

            if (failed) {
                // no longer necessary to hold this connection open
                mediaConnection.close()
                throw ClientRejectedException("Server rejected this client")
            }

            if (connectionInfo != null) {
                // no longer necessary to hold this connection open
                mediaConnection.close()
                break
            }

            // 0 means we idle. >0 means reset and don't idle (because there are likely more)
            pollIdleStrategy.idle(pollCount)
        }

        if (connectionInfo == null) {
            // no longer necessary to hold this connection open
            mediaConnection.close()
            throw ClientTimedOutException("Waiting for registration response from server")
        }

        return connectionInfo!!
    }
}
