/*
 * Copyright 2023 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network.handshake

import dorkbox.network.Client
import dorkbox.network.aeron.mediaDriver.ClientConnectionDriver
import dorkbox.network.aeron.mediaDriver.ClientHandshakeDriver
import dorkbox.network.connection.Connection
import dorkbox.network.connection.CryptoManagement
import dorkbox.network.connection.ListenerManager.Companion.cleanAllStackTrace
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTraceInternal
import dorkbox.network.exceptions.ClientRejectedException
import dorkbox.network.exceptions.ClientTimedOutException
import dorkbox.network.exceptions.ServerException
import io.aeron.FragmentAssembler
import io.aeron.Image
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
import kotlinx.coroutines.delay
import mu.KLogger
import org.agrona.DirectBuffer
import java.util.concurrent.*

internal class ClientHandshake<CONNECTION: Connection>(
    private val endPoint: Client<CONNECTION>,
    private val logger: KLogger
) {

    // @Volatile is used BECAUSE suspension of coroutines can continue on a DIFFERENT thread. We want to make sure that thread visibility is
    // correct when this happens. There are no race-conditions to be wary of.

    private val crypto = endPoint.crypto
    private val handler: FragmentHandler

    private val pollIdleStrategy = endPoint.config.pollIdleStrategy.clone()

    private val handshaker = endPoint.handshaker

    // used to keep track and associate UDP/IPC handshakes between client/server
    @Volatile
    var connectKey = 0L

    @Volatile
    private var connectionHelloInfo: ClientConnectionInfo? = null

    @Volatile
    private var connectionDone = false

    @Volatile
    private var needToRetry = false

    @Volatile
    private var failedException: Exception? = null

    @Volatile
    private var shutdown = false

    init {
        // NOTE: subscriptions (ie: reading from buffers, etc) are not thread safe!  Because it is ambiguous HOW EXACTLY they are unsafe,
        //  we exclusively read from the DirectBuffer on a single thread.

        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
        //  publication of any state to other threads and not be:
        //   - long running
        //   - re-entrant with the client
        handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
            // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!
            val sessionId = header.sessionId()
            val streamId = header.streamId()

            // note: this address will ALWAYS be an IP:PORT combo  OR  it will be aeron:ipc  (if IPC, it will be a different handler!)
            val remoteIpAndPort = (header.context() as Image).sourceIdentity()

            // split
            val splitPoint = remoteIpAndPort.lastIndexOf(':')
            val clientAddressString = remoteIpAndPort.substring(0, splitPoint)

            val aeronLogInfo = "$streamId/$sessionId : $clientAddressString"

            val message = handshaker.readMessage(buffer, offset, length, aeronLogInfo)

            failedException = null
            needToRetry = false

            // it must be a registration message
            if (message !is HandshakeMessage) {
                failedException = ClientRejectedException("[$aeronLogInfo] cancelled handshake for unrecognized message: $message")
                    .apply { cleanAllStackTrace() }
                return@FragmentAssembler
            }

            // this is an error message
            if (message.state == HandshakeMessage.INVALID) {
                val cause = ServerException(message.errorMessage ?: "Unknown").apply { stackTrace = stackTrace.copyOfRange(0, 1) }
                failedException = ClientRejectedException("[$aeronLogInfo}] (${message.connectKey}) cancelled handshake", cause)
                    .apply { cleanAllStackTrace() }
                return@FragmentAssembler
            }

            // this is a retry message
            // this can happen if there are multiple connections from the SAME ip address (ie: localhost)
            if (message.state == HandshakeMessage.RETRY) {
                needToRetry = true
                return@FragmentAssembler
            }

            if (connectKey != message.connectKey) {
                logger.error("[$aeronLogInfo] ($connectKey) ignored handshake for ${message.connectKey} (Was for another client)")
                return@FragmentAssembler
            }

            // it must be the correct state
            val registrationData = message.registrationData

            when (message.state) {
                HandshakeMessage.HELLO_ACK -> {
                    // The message was intended for this client. Try to parse it as one of the available message types.
                    // this message is ENCRYPTED!
                    val serverPublicKeyBytes = message.publicKey

                    if (registrationData != null && serverPublicKeyBytes != null) {
                        connectionHelloInfo = crypto.decrypt(registrationData, serverPublicKeyBytes)
                    } else {
                        failedException = ClientRejectedException("[$aeronLogInfo}] (${message.connectKey}) canceled handshake for message without registration and/or public key info")
                            .apply { cleanAllStackTrace() }
                    }
                }
                HandshakeMessage.HELLO_ACK_IPC -> {
                    // The message was intended for this client. Try to parse it as one of the available message types.
                    // this message is NOT-ENCRYPTED!
                    val serverPublicKeyBytes = message.publicKey

                    if (registrationData != null && serverPublicKeyBytes != null) {
                        connectionHelloInfo = crypto.nocrypt(registrationData, serverPublicKeyBytes)
                    } else {
                        failedException = ClientRejectedException("[$aeronLogInfo}] (${message.connectKey}) canceled handshake for message without registration and/or public key info")
                                .apply { cleanAllStackTrace() }
                    }
                }
                HandshakeMessage.DONE_ACK -> {
                    connectionDone = true
                }
                else -> {
                    val stateString = HandshakeMessage.toStateString(message.state)
                    failedException = ClientRejectedException("[$aeronLogInfo] (${message.connectKey}) cancelled handshake for message that is $stateString")
                        .apply { cleanAllStackTrace() }
                }
            }
        }
    }

    /**
     * Make sure that NON-ZERO is returned
     */
    private fun getSafeConnectKey(): Long {
        var key = CryptoManagement.secureRandom.nextLong()
        while (key == 0L) {
            key = CryptoManagement.secureRandom.nextLong()
        }

        return key
    }

    // called from the connect thread
    // when exceptions are thrown, the handshake pub/sub will be closed
    suspend fun hello(handshakeConnection: ClientHandshakeDriver, handshakeTimeoutSec: Int) : ClientConnectionInfo {
        // always make sure that we reset the state when we start (in the event of reconnects)
        reset()
        connectKey = getSafeConnectKey()

        val publicKey = endPoint.storage.getPublicKey()!!

        // Send the one-time pad to the server.
        val publication = handshakeConnection.pubSub.pub
        val subscription = handshakeConnection.pubSub.sub

        try {
            handshaker.writeMessage(publication, handshakeConnection.details,
                                    HandshakeMessage.helloFromClient(
                                        connectKey = connectKey,
                                        publicKey = publicKey,
                                        sessionIdSub = handshakeConnection.pubSub.sessionIdSub,
                                        streamIdSub = handshakeConnection.pubSub.streamIdSub,
                                        portSub = handshakeConnection.pubSub.portSub
                                    ))
        } catch (e: Exception) {
            handshakeConnection.close()

            logger.error("$handshakeConnection Handshake error!", e)
            throw e
        }

        // block until we receive the connection information from the server
        var pollCount: Int
        pollIdleStrategy.reset()

        val timoutInNanos = TimeUnit.SECONDS.toNanos(handshakeTimeoutSec.toLong()) + endPoint.aeronDriver.lingerNs()
        val startTime = System.nanoTime()
        while (System.nanoTime() - startTime < timoutInNanos) {
            // NOTE: regarding fragment limit size. Repeated calls to '.poll' will reassemble a fragment.
            //   `.poll(handler, 4)` == `.poll(handler, 2)` + `.poll(handler, 2)`
            pollCount = subscription.poll(handler, 1)

            if (failedException != null || connectionHelloInfo != null) {
                break
            }

            // 0 means we idle. >0 means reset and don't idle (because there are likely more)
            pollIdleStrategy.idle(pollCount)
        }

        val failedEx = failedException
        if (failedEx != null) {
            handshakeConnection.close()

            failedEx.cleanStackTraceInternal()
            throw failedEx
        }

        if (connectionHelloInfo == null) {
            handshakeConnection.close()

            val timeout = TimeUnit.NANOSECONDS.toSeconds(endPoint.aeronDriver.lingerNs()) + handshakeTimeoutSec
            val exception = ClientTimedOutException("$handshakeConnection Waiting for registration response from server for more than $timeout seconds")
            throw exception
        }

        return connectionHelloInfo!!
    }

    // called from the connect thread
    // when exceptions are thrown, the handshake pub/sub will be closed
    suspend fun done(
        handshakeConnection: ClientHandshakeDriver,
        clientConnection: ClientConnectionDriver,
        handshakeTimeoutSec: Int,
        aeronLogInfo: String
    ) {
        val registrationMessage = HandshakeMessage.doneFromClient(
                                                                        connectKey,
                                                                        handshakeConnection.pubSub.portSub,
                                                                        clientConnection.connectionInfo.streamIdSub,
                                                                        clientConnection.connectionInfo.sessionIdSub)

        // Send the done message to the server.
        try {
            handshaker.writeMessage(handshakeConnection.pubSub.pub, aeronLogInfo, registrationMessage)
        } catch (e: Exception) {
            handshakeConnection.close()

            throw e
        }

        // block until we receive the connection information from the server

        failedException = null
        pollIdleStrategy.reset()

        var pollCount: Int

        val timoutInNanos = TimeUnit.SECONDS.toNanos(handshakeTimeoutSec.toLong())
        var startTime = System.nanoTime()
        while (System.nanoTime() - startTime < timoutInNanos) {
            // NOTE: regarding fragment limit size. Repeated calls to '.poll' will reassemble a fragment.
            //   `.poll(handler, 4)` == `.poll(handler, 2)` + `.poll(handler, 2)`
            pollCount = clientConnection.connectionInfo.sub.poll(handler, 1)

            if (failedException != null || connectionDone) {
                break
            }

            if (needToRetry) {
                needToRetry = false

                // start over with the timeout!
                startTime = System.nanoTime()
            }

            delay(100L)

            // 0 means we idle. >0 means reset and don't idle (because there are likely more)
            pollIdleStrategy.idle(pollCount)
        }

        val failedEx = failedException
        if (failedEx != null) {
            handshakeConnection.close()

            throw failedEx
        }

        if (!connectionDone) {
            // since this failed, close everything
            handshakeConnection.close()

            val exception = ClientTimedOutException("Waiting for registration response from server")
            throw exception
        }
    }

    fun reset() {
        connectKey = 0L
        connectionHelloInfo = null
        connectionDone = false
        needToRetry = false
        failedException = null
    }
}
