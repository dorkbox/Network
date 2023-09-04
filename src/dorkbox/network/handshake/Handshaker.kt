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

import dorkbox.network.Configuration
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.connection.Connection
import dorkbox.network.connection.ListenerManager
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTrace
import dorkbox.network.exceptions.ClientException
import dorkbox.network.exceptions.ServerException
import dorkbox.network.serialization.KryoReader
import dorkbox.network.serialization.KryoWriter
import dorkbox.network.serialization.Serialization
import io.aeron.Publication
import io.aeron.logbuffer.FrameDescriptor
import mu.KLogger
import org.agrona.DirectBuffer
import org.agrona.concurrent.IdleStrategy

internal class Handshaker<CONNECTION : Connection>(
    private val logger: KLogger,
    config: Configuration,
    serialization: Serialization<CONNECTION>,
    private val listenerManager: ListenerManager<CONNECTION>,
    val aeronDriver: AeronDriver,
    val newException: (String, Throwable?) -> Throwable
) {
    private val handshakeReadKryo: KryoReader<CONNECTION>
    private val handshakeWriteKryo: KryoWriter<CONNECTION>
    private val handshakeSendIdleStrategy: IdleStrategy

    init {
        val maxMessageSize = FrameDescriptor.computeMaxMessageLength(config.publicationTermBufferLength)

        // All registration MUST happen in-order of when the register(*) method was called, otherwise there are problems.

        handshakeReadKryo = KryoReader(maxMessageSize)
        handshakeWriteKryo = KryoWriter(maxMessageSize)

        serialization.newHandshakeKryo(handshakeReadKryo)
        serialization.newHandshakeKryo(handshakeWriteKryo)

        handshakeSendIdleStrategy = config.sendIdleStrategy
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
    internal fun writeMessage(publication: Publication, logInfo: String, message: HandshakeMessage): Boolean {
        // The handshake sessionId IS NOT globally unique
        logger.trace { "[$logInfo] (${message.connectKey}) send HS: $message" }

        try {
            val buffer = handshakeWriteKryo.write(message)

            return aeronDriver.send(publication, buffer, logInfo, listenerManager, handshakeSendIdleStrategy)
        } catch (e: Exception) {
            // if the driver is closed due to a network disconnect or a remote-client termination, we also must close the connection.
            if (aeronDriver.internal.mustRestartDriverOnError) {
                // we had a HARD network crash/disconnect, we close the driver and then reconnect automatically
                //NOTE: notifyDisconnect IS NOT CALLED!
            }
            else if (e is ClientException || e is ServerException) {
                throw e
            }
            else {
                val exception = newException("[$logInfo] Error serializing handshake message $message", e)
                exception.cleanStackTrace(2) // 2 because we do not want to see the stack for the abstract `newException`
                listenerManager.notifyError(exception)
                throw exception
            }

            return false
        } finally {
            handshakeSendIdleStrategy.reset()
        }
    }

    /**
     * NOTE: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
     *
     * THROWS EXCEPTION IF INVALID READS!
     *
     * @param buffer The buffer
     * @param offset The offset from the start of the buffer
     * @param length The number of bytes to extract
     *
     * @return the message
     */
    internal fun readMessage(buffer: DirectBuffer, offset: Int, length: Int): Any? {
        // NOTE: This ABSOLUTELY MUST be done on the same thread! This cannot be done on a new one, because the buffer could change!
       return handshakeReadKryo.read(buffer, offset, length)
    }
}
