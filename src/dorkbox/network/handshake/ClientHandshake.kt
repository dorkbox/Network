package dorkbox.network.handshake

import dorkbox.network.Configuration
import dorkbox.network.aeron.client.ClientException
import dorkbox.network.aeron.client.ClientTimedOutException
import dorkbox.network.connection.Connection
import dorkbox.network.connection.CryptoManagement
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.ListenerManager
import dorkbox.network.connection.MediaDriverConnection
import dorkbox.network.connection.UdpMediaDriverConnection
import io.aeron.FragmentAssembler
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
import kotlinx.coroutines.launch
import mu.KLogger
import org.agrona.DirectBuffer
import java.security.SecureRandom

internal class ClientHandshake<CONNECTION: Connection>(private val logger: KLogger,
                                                       private val config: Configuration,
                                                       private val crypto: CryptoManagement,
                                                       private val listenerManager: ListenerManager<CONNECTION>) {
    // a one-time key for connecting
    private val oneTimePad = SecureRandom().nextInt()

    @Volatile
    var connectionHelloInfo: ClientConnectionInfo? = null

    @Volatile
    var connectionDone = false

    @Volatile
    private var failed: Exception? = null

    lateinit var handler: FragmentHandler
    lateinit var endPoint: EndPoint<*>
    var sessionId: Int = 0

    fun init(endPoint: EndPoint<*>) {
        this.endPoint = endPoint

        // now we have a bi-directional connection with the server on the handshake "socket".
        handler = FragmentAssembler(FragmentHandler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
            endPoint.actionDispatch.launch {
                val sessionId = header.sessionId()

                val message = endPoint.readHandshakeMessage(buffer, offset, length, header)
                logger.trace {
                    "[$sessionId] handshake response: $message"
                }

                // it must be a registration message
                if (message !is HandshakeMessage) {
                    failed = ClientException("[$sessionId] server returned unrecognized message: $message")
                    return@launch
                }

                // this is an error message
                if (message.sessionId == 0) {
                    failed = ClientException("[$sessionId] error: ${message.errorMessage}")
                    return@launch
                }


                if (this@ClientHandshake.sessionId != message.sessionId) {
                    failed = ClientException("[$message.sessionId] ignored message intended for another client (mine is: ${this@ClientHandshake.sessionId}")
                    return@launch
                }

                // it must be the correct state
                when (message.state) {
                    HandshakeMessage.HELLO_ACK -> {
                        // The message was intended for this client. Try to parse it as one of the available message types.
                        // this message is ENCRYPTED!
                        connectionHelloInfo = crypto.decrypt(message.registrationData, message.publicKey)
                        connectionHelloInfo!!.log(sessionId, logger)

                    }
                    HandshakeMessage.DONE_ACK -> {
                        connectionDone = true
                    }
                    else -> {
                        if (message.state != HandshakeMessage.HELLO_ACK) {
                            failed = ClientException("[$sessionId] ignored message that is not HELLO_ACK")
                        } else if (message.state != HandshakeMessage.DONE_ACK) {
                            failed = ClientException("[$sessionId] ignored message that is not DONE_ACK")
                        }

                        return@launch
                    }
                }
            }
        })
    }

    suspend fun handshakeHello(handshakeConnection: MediaDriverConnection, connectionTimeoutMS: Long) : ClientConnectionInfo {
        val registrationMessage = HandshakeMessage.helloFromClient(
                oneTimePad = oneTimePad,
                publicKey = config.settingsStore.getPublicKey()!!,
                registrationData = config.serialization.getKryoRegistrationDetails()
        )


        // Send the one-time pad to the server.
        endPoint.writeHandshakeMessage(handshakeConnection.publication, registrationMessage)
        sessionId = handshakeConnection.publication.sessionId()


        // block until we receive the connection information from the server

        failed = null
        var pollCount: Int
        val subscription = handshakeConnection.subscription
        val pollIdleStrategy = endPoint.config.pollIdleStrategy

        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < connectionTimeoutMS) {
            pollCount = subscription.poll(handler, 1024)

            if (failed != null) {
                // no longer necessary to hold this connection open
                handshakeConnection.close()
                throw failed as Exception
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
        val registrationMessage = HandshakeMessage.doneFromClient()

        // Send the done message to the server.
        endPoint.writeHandshakeMessage(mediaConnection.publication, registrationMessage)


        // block until we receive the connection information from the server

        failed = null
        var pollCount: Int
        val subscription = mediaConnection.subscription
        val pollIdleStrategy = endPoint.config.pollIdleStrategy

        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < connectionTimeoutMS) {
            pollCount = subscription.poll(handler, 1024)

            if (failed != null) {
                // no longer necessary to hold this connection open
                mediaConnection.close()
                throw failed as Exception
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

        return connectionDone
    }
}
