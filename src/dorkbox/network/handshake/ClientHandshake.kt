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
import dorkbox.network.exceptions.ClientException
import dorkbox.network.exceptions.ClientTimedOutException
import io.aeron.FragmentAssembler
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
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

    // a one-time key for connecting
    @Volatile
    var oneTimeKey = 0

    @Volatile
    private var connectionHelloInfo: ClientConnectionInfo? = null

    @Volatile
    private var connectionDone = false

    @Volatile
    private var needToRetry = false

    @Volatile
    private var failedMessage: String = ""

    @Volatile
    private var failed: Boolean = true

    init {
        // now we have a bi-directional connection with the server on the handshake "socket".
        handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
            // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

            val message = endPoint.readHandshakeMessage(buffer, offset, length, header)
            val sessionId = header.sessionId()

            // it must be a registration message
            if (message !is HandshakeMessage) {
                failedMessage = "[$sessionId] cancelled handshake for unrecognized message: $message"
                failed = true
                return@FragmentAssembler
            }

            // this is an error message
            if (message.state == HandshakeMessage.INVALID) {
                failedMessage = "[$sessionId] cancelled handshake for error: ${message.errorMessage}"
                failed = true
                return@FragmentAssembler
            }

            // this is a retry message
            // this can happen if there are multiple connections from the SAME ip address (ie: localhost)
            if (message.state == HandshakeMessage.RETRY) {
                needToRetry = true
                return@FragmentAssembler
            }

            if (oneTimeKey != message.oneTimeKey) {
                logger.error("[$message.sessionId] ignored message (one-time key: ${message.oneTimeKey}) intended for another client (mine is: ${oneTimeKey})")
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
                        failedMessage = "[$message.sessionId] canceled handshake for message without registration and/or public key info"
                        failed = true
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
                        failedMessage = "[$message.sessionId] canceled handshake for message without registration data"
                        failed = true
                    }
                }
                HandshakeMessage.DONE_ACK -> {
                    connectionDone = true
                }
                else -> {
                    failedMessage = "[$sessionId] cancelled handshake for message that is ${HandshakeMessage.toStateString(message.state)}"
                    failed = true
                }
            }
        }
    }

    // called from the connect thread
    fun handshakeHello(handshakeConnection: MediaDriverConnection, connectionTimeoutSec: Int) : ClientConnectionInfo {
        failed = false
        oneTimeKey = endPoint.crypto.secureRandom.nextInt()
        val publicKey = endPoint.storage.getPublicKey()!!

        // Send the one-time pad to the server.
        val publication = handshakeConnection.publication
        val subscription = handshakeConnection.subscription
        val pollIdleStrategy = endPoint.pollIdleStrategyHandShake

        try {
            endPoint.writeHandshakeMessage(publication, HandshakeMessage.helloFromClient(oneTimeKey, publicKey))
        } catch (e: Exception) {
            logger.error("Handshake error!", e)
            throw e
        }

        // block until we receive the connection information from the server
        var pollCount: Int

        val startTime = System.nanoTime()
        val timoutInNanos = TimeUnit.SECONDS.toNanos(connectionTimeoutSec.toLong())
        while (timoutInNanos == 0L || System.nanoTime() - startTime < timoutInNanos) {
            // NOTE: regarding fragment limit size. Repeated calls to '.poll' will reassemble a fragment.
            //   `.poll(handler, 4)` == `.poll(handler, 2)` + `.poll(handler, 2)`
            pollCount = subscription.poll(handler, 1)

            if (failed || connectionHelloInfo != null) {
                break
            }

            // 0 means we idle. >0 means reset and don't idle (because there are likely more)
            pollIdleStrategy.idle(pollCount)
        }

        if (failed) {
            // no longer necessary to hold this connection open (if not a failure, we close the handshake after the DONE message)
            handshakeConnection.close()
            throw ClientException(failedMessage)
        }
        if (connectionHelloInfo == null) {
            // no longer necessary to hold this connection open (if not a failure, we close the handshake after the DONE message)
            handshakeConnection.close()
            throw ClientTimedOutException("Waiting for registration response from server")
        }

        return connectionHelloInfo!!
    }

    // called from the connect thread
    fun handshakeDone(handshakeConnection: MediaDriverConnection, connectionTimeoutSec: Int): Boolean {
        val registrationMessage = HandshakeMessage.doneFromClient(oneTimeKey)

        // Send the done message to the server.
        try {
            endPoint.writeHandshakeMessage(handshakeConnection.publication, registrationMessage)
        } catch (e: Exception) {
            logger.error("Handshake error!", e)
            return false
        }

        // block until we receive the connection information from the server

        failed = false
        var pollCount: Int
        val subscription = handshakeConnection.subscription
        val pollIdleStrategy = endPoint.pollIdleStrategyHandShake

        val timoutInNanos = TimeUnit.SECONDS.toMillis(connectionTimeoutSec.toLong())
        var startTime = System.nanoTime()
        while (timoutInNanos == 0L || System.nanoTime() - startTime < timoutInNanos) {
            // NOTE: regarding fragment limit size. Repeated calls to '.poll' will reassemble a fragment.
            //   `.poll(handler, 4)` == `.poll(handler, 2)` + `.poll(handler, 2)`
            pollCount = subscription.poll(handler, 1)

            if (failed || connectionDone) {
                break
            }

            if (needToRetry) {
                needToRetry = false

                // start over with the timeout!
                startTime = System.nanoTime()
            }

            // 0 means we idle. >0 means reset and don't idle (because there are likely more)
            pollIdleStrategy.idle(pollCount)
        }

        // finished with the handshake, so always close the connection
        handshakeConnection.close()

        if (failed) {
            throw ClientException(failedMessage)
        }
        if (!connectionDone) {
            throw ClientTimedOutException("Waiting for registration response from server")
        }

        return connectionDone
    }
}
