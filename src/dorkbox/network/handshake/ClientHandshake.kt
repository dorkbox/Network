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
import dorkbox.network.connection.Connection
import dorkbox.network.connection.CryptoManagement
import dorkbox.network.connection.ListenerManager.Companion.cleanAllStackTrace
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTraceInternal
import dorkbox.network.exceptions.*
import dorkbox.util.Sys
import io.aeron.FragmentAssembler
import io.aeron.Image
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
import kotlinx.coroutines.delay
import mu.KLogger
import org.agrona.DirectBuffer

internal class ClientHandshake<CONNECTION: Connection>(
    private val client: Client<CONNECTION>,
    private val logger: KLogger
) {

    // @Volatile is used BECAUSE suspension of coroutines can continue on a DIFFERENT thread. We want to make sure that thread visibility is
    // correct when this happens. There are no race-conditions to be wary of.

    private val crypto = client.crypto
    private val handler: FragmentHandler

    private val handshaker = client.handshaker

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

            val logInfo = "$sessionId/$streamId:$clientAddressString"

            failedException = null
            needToRetry = false


            // ugh, this is verbose -- but necessary
            val message = try {
                val msg = handshaker.readMessage(buffer, offset, length)

                // VALIDATE:: a Registration object is the only acceptable message during the connection phase
                if (msg !is HandshakeMessage) {
                    throw ClientRejectedException("[$logInfo] Connection not allowed! unrecognized message: $msg") .apply { cleanAllStackTrace() }
                } else {
                    logger.trace { "[$logInfo] (${msg.connectKey}) received HS: $msg" }
                }
                msg
            } catch (e: Exception) {
                client.listenerManager.notifyError(ClientHandshakeException("[$logInfo] Error de-serializing handshake message!!", e))
                null
            } ?: return@FragmentAssembler



            // this is an error message
            if (message.state == HandshakeMessage.INVALID) {
                val cause = ServerException(message.errorMessage ?: "Unknown").apply { stackTrace = emptyArray() }
                failedException = ClientRejectedException("[$logInfo}] (${message.connectKey}) cancelled handshake", cause)
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
                logger.error("[$logInfo] ($connectKey) ignored handshake for ${message.connectKey} (Was for another client)")
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
                        failedException = ClientRejectedException("[$logInfo}] (${message.connectKey}) canceled handshake for message without registration and/or public key info")
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
                        failedException = ClientRejectedException("[$logInfo}] (${message.connectKey}) canceled handshake for message without registration and/or public key info")
                                .apply { cleanAllStackTrace() }
                    }
                }
                HandshakeMessage.DONE_ACK -> {
                    connectionDone = true
                }
                else -> {
                    val stateString = HandshakeMessage.toStateString(message.state)
                    failedException = ClientRejectedException("[$logInfo] (${message.connectKey}) cancelled handshake for message that is $stateString")
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
    fun hello(handshakeConnection: ClientHandshakeDriver, handshakeTimeoutNs: Long) : ClientConnectionInfo {
        val pubSub = handshakeConnection.pubSub

        // is our pub still connected??
        if (!pubSub.pub.isConnected) {
            throw ClientException("Handshake publication is not connected, and it is expected to be connected!")
        }

        // always make sure that we reset the state when we start (in the event of reconnects)
        reset()
        connectKey = getSafeConnectKey()

        try {
            // Send the one-time pad to the server.
            handshaker.writeMessage(pubSub.pub, handshakeConnection.details,
                                    HandshakeMessage.helloFromClient(
                                        connectKey = connectKey,
                                        publicKey = client.storage.publicKey,
                                        streamIdSub = pubSub.streamIdSub,
                                        portSub = pubSub.portSub
                                    ))
        } catch (e: Exception) {
            handshakeConnection.close()
            throw TransmitException("$handshakeConnection Handshake message error!", e)
        }

        // block until we receive the connection information from the server

        val startTime = System.nanoTime()
        while (System.nanoTime() - startTime < handshakeTimeoutNs) {
            // NOTE: regarding fragment limit size. Repeated calls to '.poll' will reassemble a fragment.
            //   `.poll(handler, 4)` == `.poll(handler, 2)` + `.poll(handler, 2)`
            pubSub.sub.poll(handler, 1)

            if (failedException != null || connectionHelloInfo != null) {
                break
            }

            Thread.sleep(100)
        }

        val failedEx = failedException
        if (failedEx != null) {
            handshakeConnection.close()

            failedEx.cleanStackTraceInternal()
            throw failedEx
        }

        if (connectionHelloInfo == null) {
            handshakeConnection.close()

            val exception = ClientTimedOutException("$handshakeConnection Waiting for registration response from server for more than ${Sys.getTimePrettyFull(handshakeTimeoutNs)}")
            throw exception
        }

        return connectionHelloInfo!!
    }

    // called from the connect thread
    // when exceptions are thrown, the handshake pub/sub will be closed
    fun done(
        handshakeConnection: ClientHandshakeDriver,
        clientConnection: ClientConnectionDriver,
        handshakeTimeoutNs: Long,
        logInfo: String
    ) {
        val pubSub = clientConnection.connectionInfo
        val handshakePubSub = handshakeConnection.pubSub

        // is our pub still connected??
        if (!pubSub.pub.isConnected) {
            throw ClientException("Handshake publication is not connected, and it is expected to be connected!")
        }

        // Send the done message to the server.
        try {
            handshaker.writeMessage(handshakeConnection.pubSub.pub, logInfo,
                                    HandshakeMessage.doneFromClient(
                                        connectKey = connectKey,
                                        sessionIdSub = handshakePubSub.sessionIdSub,
                                        streamIdSub = handshakePubSub.streamIdSub
                                    ))
        } catch (e: Exception) {
            handshakeConnection.close()
            throw TransmitException("$handshakeConnection Handshake message error!", e)
        }


        failedException = null
        connectionDone = false

        // block until we receive the connection information from the server
        var startTime = System.nanoTime()
        while (System.nanoTime() - startTime < handshakeTimeoutNs) {
            // NOTE: regarding fragment limit size. Repeated calls to '.poll' will reassemble a fragment.
            //   `.poll(handler, 4)` == `.poll(handler, 2)` + `.poll(handler, 2)`
            handshakePubSub.sub.poll(handler, 1)

            if (failedException != null || connectionDone) {
                break
            }

            if (needToRetry) {
                needToRetry = false

                // start over with the timeout!
                startTime = System.nanoTime()
            }

            Thread.sleep(100)
        }

        val failedEx = failedException
        if (failedEx != null) {
            handshakeConnection.close()

            throw failedEx
        }

        if (!connectionDone) {
            // since this failed, close everything
            handshakeConnection.close()

            val exception = ClientTimedOutException("Timed out waiting for registration response from server: ${Sys.getTimePrettyFull(handshakeTimeoutNs)}")
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
