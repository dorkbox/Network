/*
 * Copyright 2020 dorkbox, llc
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
import dorkbox.network.aeron.MediaDriverConnection
import dorkbox.network.connection.Connection
import dorkbox.network.connection.CryptoManagement
import dorkbox.network.connection.ListenerManager
import dorkbox.network.exceptions.ClientRejectedException
import dorkbox.network.exceptions.ClientTimedOutException
import dorkbox.network.exceptions.ServerException
import io.aeron.FragmentAssembler
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
import kotlinx.coroutines.delay
import mu.KLogger
import org.agrona.DirectBuffer
import java.util.concurrent.*

internal class ClientHandshake<CONNECTION: Connection>(
    private val crypto: CryptoManagement,
    private val endPoint: Client<CONNECTION>,
    private val logger: KLogger
) {

    // @Volatile is used BECAUSE suspension of coroutines can continue on a DIFFERENT thread. We want to make sure that thread visibility is
    // correct when this happens. There are no race-conditions to be wary of.

    private val handler: FragmentHandler

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

            val message = endPoint.readHandshakeMessage(buffer, offset, length, header)

            failedException = null
            needToRetry = false

            // it must be a registration message
            if (message !is HandshakeMessage) {
                failedException = ClientRejectedException("[${header.sessionId()}] cancelled handshake for unrecognized message: $message")
                return@FragmentAssembler
            }

            // this is an error message
            if (message.state == HandshakeMessage.INVALID) {
                val cause = ServerException(message.errorMessage ?: "Unknown").apply { stackTrace = stackTrace.copyOfRange(0, 1) }
                failedException = ClientRejectedException("[${message.connectKey}] cancelled handshake", cause)
                return@FragmentAssembler
            }

            // this is a retry message
            // this can happen if there are multiple connections from the SAME ip address (ie: localhost)
            if (message.state == HandshakeMessage.RETRY) {
                needToRetry = true
                return@FragmentAssembler
            }

            if (connectKey != message.connectKey) {
                logger.error("Ignored handshake (client connect key: ${message.connectKey}) intended for another client (mine is:" +
                                     " ${connectKey})")
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
                        failedException = ClientRejectedException("[${message.connectKey}] canceled handshake for message without registration and/or public key info")
                    }
                }
                HandshakeMessage.HELLO_ACK_IPC -> {
                    // The message was intended for this client. Try to parse it as one of the available message types.
                    // this message is ENCRYPTED!
                    val cryptInput = crypto.cryptInput

                    if (registrationData != null) {
                        cryptInput.buffer = registrationData

                        val sessId = cryptInput.readInt()
                        val streamSubId = cryptInput.readInt()
                        val streamPubId = cryptInput.readInt()
                        val regDetailsSize = cryptInput.readInt()
                        val regDetails = cryptInput.readBytes(regDetailsSize)

                        // now read data off
                        connectionHelloInfo = ClientConnectionInfo(sessionId = sessId,
                                                                   subscriptionPort = streamSubId,
                                                                   publicationPort = streamPubId,
                                                                   kryoRegistrationDetails = regDetails)
                    } else {
                        failedException = ClientRejectedException("[${message.connectKey}] canceled handshake for message without registration data")
                    }
                }
                HandshakeMessage.DONE_ACK -> {
                    connectionDone = true
                }
                else -> {
                    val stateString = HandshakeMessage.toStateString(message.state)
                    failedException = ClientRejectedException("[${message.connectKey}] cancelled handshake for message that is $stateString")
                }
            }
        }
    }

    /**
     * Make sure that NON-ZERO is returned
     */
    private fun getSafeConnectKey(): Long {
        var key = endPoint.crypto.secureRandom.nextLong()
        while (key == 0L) {
            key = endPoint.crypto.secureRandom.nextLong()
        }

        return key
    }

    // called from the connect thread
    fun hello(handshakeConnection: MediaDriverConnection, connectionTimeoutSec: Int) : ClientConnectionInfo {
        failedException = null
        connectKey = getSafeConnectKey()
        val publicKey = endPoint.storage.getPublicKey()!!

        // Send the one-time pad to the server.
        val publication = handshakeConnection.publication
        val subscription = handshakeConnection.subscription
        val pollIdleStrategy = endPoint.pollIdleStrategyHandShake

        try {
            endPoint.writeHandshakeMessage(publication, HandshakeMessage.helloFromClient(connectKey, publicKey))
        } catch (e: Exception) {
            publication.close()
            subscription.close()

            logger.error("Handshake error!", e)
            throw e
        }

        // block until we receive the connection information from the server
        var pollCount: Int

        val startTime = System.nanoTime()
        val timoutInNanos = TimeUnit.SECONDS.toNanos(connectionTimeoutSec.toLong())
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
            publication.close()
            subscription.close()

            // no longer necessary to hold this connection open (if not a failure, we close the handshake after the DONE message)
            handshakeConnection.close()
            ListenerManager.cleanStackTraceInternal(failedEx)
            throw failedEx
        }

        if (connectionHelloInfo == null) {
            publication.close()
            subscription.close()

            // no longer necessary to hold this connection open (if not a failure, we close the handshake after the DONE message)
            handshakeConnection.close()

            val exception = ClientTimedOutException("Waiting for registration response from server")
            ListenerManager.cleanStackTraceInternal(exception)
            throw exception
        }

        return connectionHelloInfo!!
    }

    // called from the connect thread
    suspend fun done(handshakeConnection: MediaDriverConnection, connectionTimeoutSec: Int): Boolean {
        val registrationMessage = HandshakeMessage.doneFromClient(connectKey)

        // Send the done message to the server.
        try {
            endPoint.writeHandshakeMessage(handshakeConnection.publication, registrationMessage)
        } catch (e: Exception) {
            handshakeConnection.close()

            return false
        }

        // block until we receive the connection information from the server

        failedException = null
        var pollCount: Int
        val subscription = handshakeConnection.subscription
        val pollIdleStrategy = endPoint.pollIdleStrategyHandShake

        val timoutInNanos = TimeUnit.SECONDS.toNanos(connectionTimeoutSec.toLong())
        var startTime = System.nanoTime()
        while (System.nanoTime() - startTime < timoutInNanos) {
            // NOTE: regarding fragment limit size. Repeated calls to '.poll' will reassemble a fragment.
            //   `.poll(handler, 4)` == `.poll(handler, 2)` + `.poll(handler, 2)`
            pollCount = subscription.poll(handler, 1)

            if (failedException != null || connectionDone) {
                break
            }

            if (needToRetry) {
                needToRetry = false

                // start over with the timeout!
                startTime = System.nanoTime()
            }

            delay(100)

            // 0 means we idle. >0 means reset and don't idle (because there are likely more)
            pollIdleStrategy.idle(pollCount)
        }

        // finished with the handshake, so always close the connection
        handshakeConnection.close()

        val failedEx = failedException
        if (failedEx != null) {
            throw failedEx
        }

        if (!connectionDone) {
            val exception = ClientTimedOutException("Waiting for registration response from server")
            ListenerManager.cleanStackTraceInternal(exception)
            throw exception
        }

        logger.error{"[${subscription.streamId()}] handshake done"}

        return connectionDone
    }

    fun reset() {
        connectKey = 0L
        connectionHelloInfo = null
        connectionDone = false
        needToRetry = false
        failedException = null
    }
}
