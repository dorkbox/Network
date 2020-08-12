package dorkbox.network.handshake

import dorkbox.network.Configuration
import dorkbox.network.aeron.client.ClientException
import dorkbox.network.aeron.client.ClientTimedOutException
import dorkbox.network.connection.*
import io.aeron.FragmentAssembler
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
import kotlinx.coroutines.launch
import mu.KLogger
import org.agrona.DirectBuffer
import java.security.SecureRandom

internal class ClientHandshake<CONNECTION: Connection>(logger: KLogger,
                                                       config: Configuration,
                                                       listenerManager: ListenerManager<CONNECTION>,
                                                       val crypto: CryptoManagement) : ConnectionManager<CONNECTION>(logger, config, listenerManager) {
    // a one-time key for connecting
    private val oneTimePad = SecureRandom().nextInt()

    @Volatile
    var connectionHelloInfo: ClientConnectionInfo? = null

    @Volatile
    var connectionDone = false


    private var failed = false

    lateinit var handler: FragmentHandler
    lateinit var endPoint: EndPoint<*>
    var sessionId: Int = 0

    fun init(endPoint: EndPoint<*>) {
        this.endPoint = endPoint

        // now we have a bi-directional connection with the server on the handshake "socket".
        handler = FragmentAssembler(FragmentHandler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
            endPoint.actionDispatch.launch {
                val message = endPoint.readHandshakeMessage(buffer, offset, length, header)
                logger.debug("[{}] handshake response: {}", sessionId, message)

                // it must be a registration message
                if (message !is Message) {
                    logger.error("[{}] server returned unrecognized message: {}", sessionId, message)
                    return@launch
                }

                if (message.sessionId != sessionId) {
                    logger.error("[{}] ignored message intended for another client", sessionId)
                    return@launch
                }

                // it must be the correct state
                when (message.state) {
                    Message.HELLO_ACK -> {
                        // The message was intended for this client. Try to parse it as one of the available message types.
                        // this message is ENCRYPTED!
                        connectionHelloInfo = crypto.decrypt(message.registrationData, message.publicKey)
                        connectionHelloInfo!!.log(sessionId, logger)

                    }
                    Message.DONE_ACK -> {
                        connectionDone = true
                    }
                    else -> {
                        if (message.state != Message.HELLO_ACK) {
                            logger.error("[{}] ignored message that is not HELLO_ACK", sessionId)
                        } else if (message.state != Message.DONE_ACK) {
                            logger.error("[{}] ignored message that is not INIT_ACK", sessionId)
                        }

                        return@launch
                    }
                }
            }
        })
    }

    suspend fun handshakeHello(handshakeConnection: MediaDriverConnection, connectionTimeoutMS: Long) : ClientConnectionInfo {
        val registrationMessage = Message.helloFromClient(
                oneTimePad = oneTimePad,
                publicKey = config.settingsStore.getPublicKey()!!,
                registrationData = config.serialization.getKryoRegistrationDetails()
        )


        // Send the one-time pad to the server.
        endPoint.writeHandshakeMessage(handshakeConnection.publication, registrationMessage)
        sessionId = handshakeConnection.publication.sessionId()


        // block until we receive the connection information from the server

        failed = false
        var pollCount: Int
        val subscription = handshakeConnection.subscription
        val pollIdleStrategy = endPoint.config.pollIdleStrategy

        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < connectionTimeoutMS) {
            pollCount = subscription.poll(handler, 1024)

            if (failed) {
                // no longer necessary to hold this connection open
                handshakeConnection.close()
                throw ClientException("Server rejected this client")
            }

            if (connectionHelloInfo != null) {
                // we close the handshake connection after the DONE message
                break
            }

            // 0 means we idle. >0 means reset and don't idle (because there are likely more)
            pollIdleStrategy.idle(pollCount)
        }

        if (connectionHelloInfo == null) {
            // no longer necessary to hold this connection open
            handshakeConnection.close()
            throw ClientTimedOutException("Waiting for registration response from server")
        }

        return connectionHelloInfo!!
    }

    suspend fun handshakeDone(mediaConnection: UdpMediaDriverConnection, connectionTimeoutMS: Long): Boolean {
        val registrationMessage = Message.doneFromClient()

        // Send the done message to the server.
        endPoint.writeHandshakeMessage(mediaConnection.publication, registrationMessage)


        // block until we receive the connection information from the server

        failed = false
        var pollCount: Int
        val subscription = mediaConnection.subscription
        val pollIdleStrategy = endPoint.config.pollIdleStrategy

        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < connectionTimeoutMS) {
            pollCount = subscription.poll(handler, 1024)

            if (failed) {
                // no longer necessary to hold this connection open
                mediaConnection.close()
                throw ClientException("Server rejected this client")
            }

            if (connectionDone) {
                break
            }

            // 0 means we idle. >0 means reset and don't idle (because there are likely more)
            pollIdleStrategy.idle(pollCount)
        }

        if (!connectionDone) {
            // no longer necessary to hold this connection open
            mediaConnection.close()
            throw ClientTimedOutException("Waiting for registration response from server")
        }

        // no longer necessary to hold this connection open
        mediaConnection.close()

        return connectionDone
    }
}
