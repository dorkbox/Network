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

package dorkbox.network.connection

import dorkbox.network.Configuration
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.CoroutineIdleStrategy
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTrace
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTraceInternal
import dorkbox.network.exceptions.ClientException
import dorkbox.network.exceptions.ServerException
import dorkbox.network.handshake.HandshakeMessage
import dorkbox.network.serialization.KryoExtra
import dorkbox.network.serialization.Serialization
import io.aeron.Publication
import mu.KLogger
import org.agrona.DirectBuffer

internal class Handshaker<CONNECTION : Connection>(
    private val logger: KLogger,
    config: Configuration,
    serialization: Serialization<CONNECTION>,
    private val listenerManager: ListenerManager<CONNECTION>,
    aeronDriver: AeronDriver,
    val newException: (String, Throwable?) -> Throwable
) {
    private val handshakeReadKryo: KryoExtra<CONNECTION>
    private val handshakeWriteKryo: KryoExtra<CONNECTION>
    private val handshakeSendIdleStrategy: CoroutineIdleStrategy

    private val writeTimeoutNS = (aeronDriver.lingerNs() * 1.2).toLong() // close enough. Just needs to be slightly longer

    init {
        handshakeReadKryo = serialization.newHandshakeKryo()
        handshakeWriteKryo = serialization.newHandshakeKryo()
        handshakeSendIdleStrategy = config.sendIdleStrategy.clone()
    }

    /**
     * NOTE: this **MUST** stay on the same co-routine that calls "send". This cannot be re-dispatched onto a different coroutine!
     *       CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
     *       Server -> will be network polling thread
     *       Client -> will be thread that calls `connect()`
     *
     * @return true if the message was successfully sent by aeron
     */
    @Suppress("DuplicatedCode")
    internal suspend fun writeMessage(publication: Publication, aeronLogInfo: String, message: HandshakeMessage) {
        // The handshake sessionId IS NOT globally unique
        logger.trace { "[$aeronLogInfo] (${message.connectKey}) send HS: $message" }

        try {
            val buffer = handshakeWriteKryo.write(message)
            val objectSize = buffer.position()
            val internalBuffer = buffer.internalBuffer

            var timeoutInNanos = 0L
            var startTime = 0L

            var result: Long
            while (true) {
                result = publication.offer(internalBuffer, 0, objectSize)
                if (result >= 0) {
                    // success!
                    return
                }

                /**
                 * Since the publication is not connected, we weren't able to send data to the remote endpoint.
                 *
                 * According to Aeron Docs, Pubs and Subs can "come and go", whatever that means. We just want to make sure that we
                 * don't "loop forever" if a publication is ACTUALLY closed, like on purpose.
                 */
                if (result == Publication.NOT_CONNECTED) {
                    if (timeoutInNanos == 0L) {
                        timeoutInNanos = writeTimeoutNS
                        startTime = System.nanoTime()
                    }

                    if (System.nanoTime() - startTime < timeoutInNanos) {
                        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
                        //  publication of any state to other threads and not be long running or re-entrant with the client.

                        // we should retry.
                        handshakeSendIdleStrategy.idle()
                        continue
                    } else if (!publication.isClosed) {
                        // more critical error sending the message. we shouldn't retry or anything.
                        // this exception will be a ClientException or a ServerException
                        val exception = newException(
                            "[$aeronLogInfo] Error sending message. (Connection in non-connected state longer than linger timeout. ${
                                EndPoint.errorCodeName(
                                    result
                                )
                            })",
                            null
                        )
                        exception.cleanStackTraceInternal()
                        listenerManager.notifyError(exception)
                        throw exception
                    }
                    else {
                        // publication was actually closed, so no bother throwing an error
                        return
                    }
                }

                /**
                 * The publication is not connected to a subscriber, this can be an intermittent state as subscribers come and go.
                 *  val NOT_CONNECTED: Long = -1
                 *
                 * The offer failed due to back pressure from the subscribers preventing further transmission.
                 *  val BACK_PRESSURED: Long = -2
                 *
                 * The offer failed due to an administration action and should be retried.
                 * The action is an operation such as log rotation which is likely to have succeeded by the next retry attempt.
                 *  val ADMIN_ACTION: Long = -3
                 */
                if (result >= Publication.ADMIN_ACTION) {
                    // we should retry.
                    handshakeSendIdleStrategy.idle()
                    continue
                }

                // more critical error sending the message. we shouldn't retry or anything.
                // this exception will be a ClientException or a ServerException
                val exception = newException("[$aeronLogInfo] Error sending handshake message. $message (${EndPoint.errorCodeName(result)})", null)
                exception.cleanStackTraceInternal()
                listenerManager.notifyError(exception)
                throw exception
            }
        } catch (e: Exception) {
            if (e is ClientException || e is ServerException) {
                throw e
            } else {
                val exception = newException("[$aeronLogInfo] Error serializing handshake message $message", e)
                exception.cleanStackTrace(2) // 2 because we do not want to see the stack for the abstract `newException`
                listenerManager.notifyError(exception)
                throw exception
            }
        } finally {
            handshakeSendIdleStrategy.reset()
        }
    }

    /**
     * NOTE: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
     *
     * @param buffer The buffer
     * @param offset The offset from the start of the buffer
     * @param length The number of bytes to extract
     *
     * @return the message
     */
    internal fun readMessage(buffer: DirectBuffer, offset: Int, length: Int, aeronLogInfo: String): Any? {
        return try {
            // NOTE: This ABSOLUTELY MUST be done on the same thread! This cannot be done on a new one, because the buffer could change!
            val message = handshakeReadKryo.read(buffer, offset, length) as HandshakeMessage

            logger.trace { "[$aeronLogInfo] (${message.connectKey}) received HS: $message" }

            message
        } catch (e: Exception) {
            // The handshake sessionId IS NOT globally unique
            logger.error(e) { "[$aeronLogInfo] Error de-serializing handshake message!!" }
            listenerManager.notifyError(e)
            null
        }
    }

}
