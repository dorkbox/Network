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

import dorkbox.network.aeron.MediaDriverConnection
import dorkbox.network.connection.Connection
import dorkbox.network.connection.CryptoManagement
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.ListenerManager
import dorkbox.network.exceptions.ClientException
import dorkbox.network.exceptions.ClientTimedOutException
import io.aeron.FragmentAssembler
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer

internal class ClientHandshake<CONNECTION: Connection>(private val crypto: CryptoManagement, private val endPoint: EndPoint<CONNECTION>) {

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
    private var failed: Exception? = null

    init {
        // now we have a bi-directional connection with the server on the handshake "socket".
        handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
            // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

            val message = endPoint.readHandshakeMessage(buffer, offset, length, header)
            val sessionId = header.sessionId()

            // it must be a registration message
            if (message !is HandshakeMessage) {
                val exception = ClientException("[$sessionId] cancelled handshake for unrecognized message: $message")
                ListenerManager.noStackTrace(exception)
                failed = exception
                return@FragmentAssembler
            }

            // this is an error message
            if (message.state == HandshakeMessage.INVALID) {
                val exception = ClientException("[$sessionId] cancelled handshake for error: ${message.errorMessage}")
                ListenerManager.noStackTrace(exception)
                failed = exception
                return@FragmentAssembler
            }

            // this is an retry message
            // this can happen if there are multiple connections from the SAME ip address (ie: localhost)
            if (message.state == HandshakeMessage.RETRY) {
                needToRetry = true
                return@FragmentAssembler
            }

            if (oneTimeKey != message.oneTimeKey) {
                val exception = ClientException("[$message.sessionId] ignored message (one-time key: ${message.oneTimeKey}) intended for another client (mine is: ${oneTimeKey})")
                ListenerManager.noStackTrace(exception)
                endPoint.listenerManager.notifyError(exception)
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
                        val exception = ClientException("[$message.sessionId] canceled handshake for message without registration and/or public key info")
                        ListenerManager.noStackTrace(exception)
                        failed = exception
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
                        val exception = ClientException("[$message.sessionId] canceled handshake for message without registration data")
                        ListenerManager.noStackTrace(exception)
                        failed = exception
                    }
                }
                HandshakeMessage.DONE_ACK -> {
                    connectionDone = true
                }
                else -> {
                    val exception = ClientException("[$sessionId] cancelled handshake for message that is ${HandshakeMessage.toStateString(message.state)}")
                    ListenerManager.noStackTrace(exception)
                    failed = exception
                }
            }
        }
    }

    // called from the connect thread
    suspend fun handshakeHello(handshakeConnection: MediaDriverConnection, connectionTimeoutMS: Long) : ClientConnectionInfo {
        failed = null
        oneTimeKey = endPoint.crypto.secureRandom.nextInt()
        val publicKey = endPoint.storage.getPublicKey()!!

        // Send the one-time pad to the server.
        val publication = handshakeConnection.publication
        val subscription = handshakeConnection.subscription
        val pollIdleStrategy = endPoint.config.pollIdleStrategy



        endPoint.writeHandshakeMessage(publication, HandshakeMessage.helloFromClient(oneTimeKey, publicKey))

        // block until we receive the connection information from the server
        var pollCount: Int

        val startTime = System.currentTimeMillis()
        while (connectionTimeoutMS == 0L || System.currentTimeMillis() - startTime < connectionTimeoutMS) {
            // NOTE: regarding fragment limit size. Repeated calls to '.poll' will reassemble a fragment.
            //   `.poll(handler, 4)` == `.poll(handler, 2)` + `.poll(handler, 2)`
            pollCount = subscription.poll(handler, 1)

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

    // called from the connect thread
    suspend fun handshakeDone(handshakeConnection: MediaDriverConnection, connectionTimeoutMS: Long): Boolean {
        val registrationMessage = HandshakeMessage.doneFromClient(oneTimeKey)

        // Send the done message to the server.
        endPoint.writeHandshakeMessage(handshakeConnection.publication, registrationMessage)


        // block until we receive the connection information from the server

        failed = null
        var pollCount: Int
        val subscription = handshakeConnection.subscription
        val pollIdleStrategy = endPoint.config.pollIdleStrategy

        var startTime = System.currentTimeMillis()
        while (connectionTimeoutMS == 0L || System.currentTimeMillis() - startTime < connectionTimeoutMS) {
            // NOTE: regarding fragment limit size. Repeated calls to '.poll' will reassemble a fragment.
            //   `.poll(handler, 4)` == `.poll(handler, 2)` + `.poll(handler, 2)`
            pollCount = subscription.poll(handler, 1)

            if (failed != null) {
                // no longer necessary to hold this connection open
                handshakeConnection.close()
                throw failed as Exception
            }

            if (needToRetry) {
                needToRetry = false

                // start over with the timeout!
                startTime = System.currentTimeMillis()
            }

            if (connectionDone) {
                break
            }

            // 0 means we idle. >0 means reset and don't idle (because there are likely more)
            pollIdleStrategy.idle(pollCount)
        }

        // no longer necessary to hold this connection open
        handshakeConnection.close()

        if (!connectionDone) {
            throw ClientTimedOutException("Waiting for registration response from server")
        }

        return connectionDone
    }
}
