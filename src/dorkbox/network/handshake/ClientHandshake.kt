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
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.mediaDriver.MediaDriverClient
import dorkbox.network.connection.Connection
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

    init {
        // now we have a bi-directional connection with the server on the handshake "socket".
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

            val message = endPoint.readHandshakeMessage(buffer, offset, length, aeronLogInfo)

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
                    val cryptInput = crypto.cryptInput

                    if (registrationData != null) {
                        cryptInput.buffer = registrationData

                        val port = cryptInput.readInt()
                        val ackSessionId = cryptInput.readInt()
                        val ackStreamId = cryptInput.readInt()
                        val regDetailsSize = cryptInput.readInt()
                        val regDetails = cryptInput.readBytes(regDetailsSize)

                        // now read data off
                        connectionHelloInfo = ClientConnectionInfo(streamId = ackStreamId,
                                                                   sessionId = ackSessionId,
                                                                   port = port,
                                                                   kryoRegistrationDetails = regDetails)
                    } else {
                        failedException = ClientRejectedException("[$aeronLogInfo] (${message.connectKey}) canceled handshake for message without registration data")
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
        var key = crypto.secureRandom.nextLong()
        while (key == 0L) {
            key = crypto.secureRandom.nextLong()
        }

        return key
    }

    // called from the connect thread
    // when exceptions are thrown, the handshake pub/sub will be closed
    suspend fun hello(aeronDriver: AeronDriver, handshakeConnection: MediaDriverClient, connectionTimeoutSec: Int) : ClientConnectionInfo {
        failedException = null
        connectKey = getSafeConnectKey()
        val publicKey = endPoint.storage.getPublicKey()!!

        // Send the one-time pad to the server.
        val publication = handshakeConnection.publication
        val subscription = handshakeConnection.subscription

        try {
            endPoint.writeHandshakeMessage(publication, handshakeConnection.info,
                                           HandshakeMessage.helloFromClient(connectKey, publicKey,
                                                                            handshakeConnection.sessionId,
                                                                            handshakeConnection.subscriptionPort,
                                                                            handshakeConnection.subscription.streamId()))
        } catch (e: Exception) {
            aeronDriver.closeAndDeleteSubscription(subscription, "ClientHandshake")
            aeronDriver.closeAndDeletePublication(publication, "ClientHandshake")

            logger.error("$handshakeConnection Handshake error!", e)
            throw e
        }

        // block until we receive the connection information from the server
        var pollCount: Int
        pollIdleStrategy.reset()

        val timoutInNanos = TimeUnit.SECONDS.toNanos(connectionTimeoutSec.toLong()) + endPoint.aeronDriver.getLingerNs()
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
            aeronDriver.closeAndDeleteSubscription(subscription, "ClientHandshake")
            aeronDriver.closeAndDeletePublication(publication, "ClientHandshake")

            ListenerManager.cleanStackTraceInternal(failedEx)
            throw failedEx
        }

        if (connectionHelloInfo == null) {
            aeronDriver.closeAndDeleteSubscription(subscription, "ClientHandshake")
            aeronDriver.closeAndDeletePublication(publication, "ClientHandshake")

            val exception = ClientTimedOutException("$handshakeConnection Waiting for registration response from server")
            ListenerManager.cleanStackTraceInternal(exception)
            throw exception
        }

        return connectionHelloInfo!!
    }

    // called from the connect thread
    // when exceptions are thrown, the handshake pub/sub will be closed
    suspend fun done(aeronDriver: AeronDriver, handshakeConnection: MediaDriverClient, connectionTimeoutSec: Int, aeronLogInfo: String) {
        val registrationMessage = HandshakeMessage.doneFromClient(connectKey,
                                                                  handshakeConnection.port+1,
                                                                  handshakeConnection.subscription.streamId(),
                                                                  handshakeConnection.sessionId)

        // Send the done message to the server.
        try {
            endPoint.writeHandshakeMessage(handshakeConnection.publication, aeronLogInfo, registrationMessage)
        } catch (e: Exception) {
            aeronDriver.closeAndDeleteSubscription(handshakeConnection.subscription, "ClientHandshake")
            aeronDriver.closeAndDeletePublication(handshakeConnection.publication, "ClientHandshake")

            throw e
        }

        // block until we receive the connection information from the server

        failedException = null
        pollIdleStrategy.reset()

        var pollCount: Int

        val timoutInNanos = TimeUnit.SECONDS.toNanos(connectionTimeoutSec.toLong())
        var startTime = System.nanoTime()
        while (System.nanoTime() - startTime < timoutInNanos) {
            // NOTE: regarding fragment limit size. Repeated calls to '.poll' will reassemble a fragment.
            //   `.poll(handler, 4)` == `.poll(handler, 2)` + `.poll(handler, 2)`
            pollCount = handshakeConnection.subscription.poll(handler, 1)

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
            aeronDriver.closeAndDeleteSubscription(handshakeConnection.subscription, "ClientHandshake")
            aeronDriver.closeAndDeletePublication(handshakeConnection.publication, "ClientHandshake")

            throw failedEx
        }

        if (!connectionDone) {
            // since this failed, close everything
            aeronDriver.closeAndDeleteSubscription(handshakeConnection.subscription, "ClientHandshake")
            aeronDriver.closeAndDeletePublication(handshakeConnection.publication, "ClientHandshake")

            val exception = ClientTimedOutException("Waiting for registration response from server")
            exception.cleanStackTraceInternal()
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
