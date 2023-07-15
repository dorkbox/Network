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

@file:Suppress("DuplicatedCode")

package dorkbox.network.connection.streaming

import com.esotericsoftware.kryo.io.Input
import dorkbox.bytes.OptimizeUtilsByteBuf
import dorkbox.collections.LockFreeHashMap
import dorkbox.network.Configuration
import dorkbox.network.connection.Connection
import dorkbox.network.connection.CryptoManagement
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTrace
import dorkbox.network.exceptions.StreamingException
import dorkbox.network.serialization.AeronInput
import dorkbox.network.serialization.AeronOutput
import dorkbox.network.serialization.KryoReader
import dorkbox.network.serialization.KryoWriter
import dorkbox.os.OS
import dorkbox.util.Sys
import io.aeron.Publication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KLogger
import org.agrona.ExpandableDirectByteBuffer
import org.agrona.MutableDirectBuffer
import org.agrona.concurrent.IdleStrategy
import java.io.FileInputStream

internal class StreamingManager<CONNECTION : Connection>(
    private val logger: KLogger, private val messageDispatch: CoroutineScope, val config: Configuration
) {

    companion object {
        private const val KILOBYTE = 1024
        private const val MEGABYTE = 1024 * KILOBYTE
        private const val GIGABYTE = 1024 * MEGABYTE
        private const val TERABYTE = 1024L * GIGABYTE

        @Suppress("UNUSED_CHANGED_VALUE")
        private fun writeVarInt(internalBuffer: MutableDirectBuffer, position: Int, value: Int, optimizePositive: Boolean): Int {
            var p = position
            var newValue = value
            if (!optimizePositive) newValue = newValue shl 1 xor (newValue shr 31)
            if (newValue ushr 7 == 0) {
                internalBuffer.putByte(p++, newValue.toByte())
                return 1
            }
            if (newValue ushr 14 == 0) {
                internalBuffer.putByte(p++, (newValue and 0x7F or 0x80).toByte())
                internalBuffer.putByte(p++, (newValue ushr 7).toByte())
                return 2
            }
            if (newValue ushr 21 == 0) {
                val byteBuf = internalBuffer
                byteBuf.putByte(p++, (newValue and 0x7F or 0x80).toByte())
                byteBuf.putByte(p++, (newValue ushr 7 or 0x80).toByte())
                byteBuf.putByte(p++, (newValue ushr 14).toByte())
                return 3
            }
            if (newValue ushr 28 == 0) {
                val byteBuf = internalBuffer
                byteBuf.putByte(p++, (newValue and 0x7F or 0x80).toByte())
                byteBuf.putByte(p++, (newValue ushr 7 or 0x80).toByte())
                byteBuf.putByte(p++, (newValue ushr 14 or 0x80).toByte())
                byteBuf.putByte(p++, (newValue ushr 21).toByte())
                return 4
            }
            val byteBuf = internalBuffer
            byteBuf.putByte(p++, (newValue and 0x7F or 0x80).toByte())
            byteBuf.putByte(p++, (newValue ushr 7 or 0x80).toByte())
            byteBuf.putByte(p++, (newValue ushr 14 or 0x80).toByte())
            byteBuf.putByte(p++, (newValue ushr 21 or 0x80).toByte())
            byteBuf.putByte(p++, (newValue ushr 28).toByte())
            return 5
        }
    }


    private val streamingDataTarget = LockFreeHashMap<Long, StreamingControl>()
    private val streamingDataInMemory = LockFreeHashMap<Long, StreamingWriter>()


    /**
     * What is the max stream size that can exist in memory when deciding if data blocks are in memory or temp-file on disk
     */
    private val maxStreamSizeInMemoryInBytes = config.maxStreamSizeInMemoryMB * MEGABYTE


    /**
     * NOTE: MUST BE ON THE AERON THREAD!
     *
    * Reassemble/figure out the internal message pieces. Processed always on the same thread
    */
    fun processControlMessage(
        message: StreamingControl,
        kryo: KryoReader<CONNECTION>,
        endPoint: EndPoint<CONNECTION>,
        connection: CONNECTION
    ) {
        // NOTE: the stream session ID is a combination of the connection ID + random ID (on the receiving side),
        //      otherwise clients can abuse it and corrupt OTHER clients data!!
        val streamId = (connection.id.toLong() shl 4) or message.streamId.toLong()

        when (message.state) {
            StreamingState.START -> {
                // message.totalSize > maxInMemory, then write to a temp file INSTEAD
                if (message.totalSize > maxStreamSizeInMemoryInBytes) {
                    val fileName = "${config.appId}_${streamId}_${connection.id}.tmp"
                    val tempFileLocation = OS.TEMP_DIR.resolve(fileName)

                    val prettySize = Sys.getSizePretty(message.totalSize)

                    endPoint.logger.info { "Saving $prettySize of streaming data [${streamId}] to: $tempFileLocation" }
                    streamingDataInMemory[streamId] = FileWriter(tempFileLocation)
                } else {
                    endPoint.logger.info { "Saving streaming data [${streamId}] in memory" }
                    // .toInt is safe because we know the total size is < than maxStreamSizeInMemoryInBytes
                    streamingDataInMemory[streamId] = AeronWriter(message.totalSize.toInt())
                }

                // this must be last
                streamingDataTarget[streamId] = message
            }
            StreamingState.FINISHED -> {
                // NOTE: cannot be on a coroutine before kryo usage!

                // get the data out and send messages!
                val output = streamingDataInMemory.remove(streamId)
                val input = when (output) {
                    is AeronWriter -> {
                        AeronInput(output.internalBuffer)
                    }
                    is FileWriter -> {
                        output.flush()
                        output.close()

                        val fileName = "${config.appId}_${streamId}_${connection.id}.tmp"
                        val tempFileLocation = OS.TEMP_DIR.resolve(fileName)

                        val fileInputStream = FileInputStream(tempFileLocation)
                        Input(fileInputStream)
                    }
                    else -> {
                        null
                    }
                }

                val streamedMessage = if (input != null) {
                    try {
                        kryo.read(input)
                    } catch (e: Exception) {
                        // something SUPER wrong!
                        // more critical error sending the message. we shouldn't retry or anything.
                        val errorMessage = "Error deserializing message from received streaming content, stream $streamId"

                        // either client or server. No other choices. We create an exception, because it's more useful!
                        throw endPoint.newException(errorMessage, e)
                    } finally {
                        if (output is FileWriter) {
                            val fileName = "${config.appId}_${streamId}_${connection.id}.tmp"
                            val tempFileLocation = OS.TEMP_DIR.resolve(fileName)
                            tempFileLocation.delete()
                        }
                    }
                } else {
                   null
                }

                if (streamedMessage == null) {
                    if (output is FileWriter) {
                        val fileName = "${config.appId}_${streamId}_${connection.id}.tmp"
                        val tempFileLocation = OS.TEMP_DIR.resolve(fileName)
                        tempFileLocation.delete()
                    }

                    // something SUPER wrong!
                    // more critical error sending the message. we shouldn't retry or anything.
                    val errorMessage = "Error while processing streaming content, stream $streamId was null."

                    // either client or server. No other choices. We create an exception, because it's more useful!
                    throw endPoint.newException(errorMessage)
                }


                // NOTE: This MUST be on a new co-routine
                messageDispatch.launch {
                    val listenerManager = endPoint.listenerManager

                    try {
                        var hasListeners = listenerManager.notifyOnMessage(connection, streamedMessage)

                        // each connection registers, and is polled INDEPENDENTLY for messages.
                        hasListeners = hasListeners or connection.notifyOnMessage(streamedMessage)

                        if (!hasListeners) {
                            logger.error("No streamed message callbacks found for ${streamedMessage::class.java.name}")
                        }
                    } catch (e: Exception) {
                        val newException = StreamingException("Error processing message ${streamedMessage::class.java.name}", e)
                        listenerManager.notifyError(connection, newException)
                    }
                }
            }
            StreamingState.FAILED -> {
                val output = streamingDataInMemory.remove(streamId)
                if (output is FileWriter) {
                    val fileName = "${config.appId}_${streamId}_${connection.id}.tmp"
                    val tempFileLocation = OS.TEMP_DIR.resolve(fileName)
                    tempFileLocation.delete()
                }

                // clear all state
                // something SUPER wrong!
                // more critical error sending the message. we shouldn't retry or anything.
                val errorMessage = "Failure while receiving streaming content for stream $streamId"

                // either client or server. No other choices. We create an exception, because it's more useful!
                val exception = endPoint.newException(errorMessage)

                // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
                // where we see who is calling "send()"
                exception.cleanStackTrace(3)
                throw exception
            }
            StreamingState.UNKNOWN ->  {
                val output = streamingDataInMemory.remove(streamId)
                if (output is FileWriter) {
                    val fileName = "${config.appId}_${streamId}_${connection.id}.tmp"
                    val tempFileLocation = OS.TEMP_DIR.resolve(fileName)
                    tempFileLocation.delete()
                }

                // something SUPER wrong!
                // more critical error sending the message. we shouldn't retry or anything.
                val errorMessage = "Unknown failure while receiving streaming content for stream $streamId"

                // either client or server. No other choices. We create an exception, because it's more useful!
                val exception = endPoint.newException(errorMessage)

                // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
                // where we see who is calling "send()"
                exception.cleanStackTrace(3)
                throw exception
            }
        }
    }

    /**
     * NOTE: MUST BE ON THE AERON THREAD!
     *
     * Reassemble/figure out the internal message pieces
     *
     * NOTE sending a huge file can prevent other other network traffic from arriving until it's done!
     */
    fun processDataMessage(message: StreamingData, endPoint: EndPoint<CONNECTION>, connection: CONNECTION) {
        // the receiving data will ALWAYS come sequentially, but there might be OTHER streaming data received meanwhile.
        // NOTE: the stream session ID is a combination of the connection ID + random ID (on the receiving side)
        val streamId = (connection.id.toLong() shl 4) or message.streamId.toLong()

        val controlMessage = streamingDataTarget[streamId]
        if (controlMessage != null) {
            streamingDataInMemory[streamId]!!.writeBytes(message.payload!!)
        } else {
            // something SUPER wrong!
            // more critical error sending the message. we shouldn't retry or anything.
            val errorMessage = "Abnormal failure while receiving streaming content, stream $streamId not available."

            // either client or server. No other choices. We create an exception, because it's more useful!
            val exception = endPoint.newException(errorMessage)

            // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
            // where we see who is calling "send()"
            exception.cleanStackTrace(3)
            throw exception
        }
    }

    private fun sendFailMessageAndThrow(
        e: Exception,
        streamSessionId: Int,
        publication: Publication,
        endPoint: EndPoint<CONNECTION>,
        sendIdleStrategy: IdleStrategy,
        connection: CONNECTION,
        kryo: KryoWriter<CONNECTION>
    ) {
        val failMessage = StreamingControl(StreamingState.FAILED, streamSessionId)

        val failSent = endPoint.writeUnsafe(failMessage, publication, sendIdleStrategy, connection, kryo)
        if (!failSent) {
            // something SUPER wrong!
            // more critical error sending the message. we shouldn't retry or anything.
            val errorMessage = "[${publication.sessionId()}] Abnormal failure while streaming content."

            // either client or server. No other choices. We create an exception, because it's more useful!
            val exception = endPoint.newException(errorMessage)

            // +4 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
            // where we see who is calling "send()"
            exception.cleanStackTrace(4)
            throw exception
        } else {
            // send it up!
            throw e
        }
    }

    /**
     * This is called ONLY when a message is too large to send across the network in a single message (large data messages should
     * be split into smaller ones anyways!)
     *
     * NOTE: this **MUST** stay on the same co-routine that calls "send". This cannot be re-dispatched onto a different coroutine!
     *
     * We don't write max possible length per message, we write out MTU (payload) length (so aeron doesn't fragment the message).
     * The max possible length is WAY, WAY more than the max payload length.
     *
     * @param internalBuffer this is the ORIGINAL object data that is to be blocks sent across the wire
     * @return true if ALL the message blocks were successfully sent by aeron, false otherwise. Exceptions are caught and rethrown!
     */
    fun send(
        publication: Publication,
        internalBuffer: MutableDirectBuffer,
        maxMessageSize: Int,
        objectSize: Int,
        endPoint: EndPoint<CONNECTION>,
        kryo: KryoWriter<CONNECTION>,
        sendIdleStrategy: IdleStrategy,
        connection: CONNECTION
    ): Boolean {
        // this buffer is the exact size as our internal buffer, so it is unnecessary to have multiple kryo instances
        val originalBuffer = ExpandableDirectByteBuffer(objectSize) // this can grow, so it's fine to lock it to this size!

        // we have to save out our internal buffer, so we can reuse the kryo instance!
        originalBuffer.putBytes(0, internalBuffer, 0, objectSize)


        // NOTE: our max object size for IN-MEMORY messages is an INT. For file transfer it's a LONG (so everything here is cast to a long)
        var remainingPayload = objectSize
        var payloadSent = 0

        // NOTE: the stream session ID is a combination of the connection ID + random ID (on the receiving side)
        val streamSessionId = CryptoManagement.secureRandom.nextInt()

        // tell the other side how much data we are sending
        val startMessage = StreamingControl(StreamingState.START, streamSessionId, objectSize.toLong())

        val startSent = endPoint.writeUnsafe(startMessage, publication, sendIdleStrategy, connection, kryo)
        if (!startSent) {
            // more critical error sending the message. we shouldn't retry or anything.
            val errorMessage = "[${publication.sessionId()}] Error starting streaming content."

            // either client or server. No other choices. We create an exception, because it's more useful!
            val exception = endPoint.newException(errorMessage)

            // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
            // where we see who is calling "send()"
            exception.cleanStackTrace(3)
            throw exception
        }


        // we do the FIRST block super-weird, because of the way we copy data around (we inject headers,
        // so the first message is SUPER tiny and is a COPY, the rest are no-copy.

        // This is REUSED to prevent garbage collection issues.
        val blockData = StreamingData(streamSessionId)

        // payload size is for a PRODUCER, and not SUBSCRIBER, so we have to include this amount every time.
        // MINOR fragmentation by aeron is OK, since that will greatly speed up data transfer rates!

        var sizeOfPayload = maxMessageSize

        val header: ByteArray
        val headerSize: Int

        try {
            val objectBuffer = kryo.write(connection, blockData)
            headerSize = objectBuffer.position()
            header = ByteArray(headerSize)

            // we have to account for the header + the MAX optimized int size
            sizeOfPayload -= (headerSize + 5)

            // this size might be a LITTLE too big, but that's ok, since we only make this specific buffer once.
            val blockBuffer = AeronOutput(headerSize + sizeOfPayload)

            // copy out our header info
            objectBuffer.internalBuffer.getBytes(0, header, 0, headerSize)

            // write out our header
            blockBuffer.writeBytes(header)

            // write out the payload size using optimized data structures.
            val varIntSize = blockBuffer.writeVarInt(sizeOfPayload, true)

            // write out the payload. Our resulting data written out is the ACTUAL MTU of aeron.
            originalBuffer.getBytes(0, blockBuffer.internalBuffer, headerSize + varIntSize, sizeOfPayload)

            remainingPayload -= sizeOfPayload
            payloadSent += sizeOfPayload

            val success = endPoint.dataSend(
                publication,
                blockBuffer.internalBuffer,
                0,
                headerSize + varIntSize + sizeOfPayload,
                sendIdleStrategy,
                connection,
                false
            )
            if (!success) {
                // something SUPER wrong!
                // more critical error sending the message. we shouldn't retry or anything.
                val errorMessage = "[${publication.sessionId()}] Abnormal failure while streaming content."

                // either client or server. No other choices. We create an exception, because it's more useful!
                val exception = endPoint.newException(errorMessage)

                // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
                // where we see who is calling "send()"
                exception.cleanStackTrace(3)
                throw exception
            }
        } catch (e: Exception) {
            sendFailMessageAndThrow(e, streamSessionId, publication, endPoint, sendIdleStrategy, connection, kryo)
            return false // doesn't actually get here because exceptions are thrown, but this makes the IDE happy.
        }

        // now send the block as fast as possible. Aeron will have us back-off if we send too quickly
        while (remainingPayload > 0) {
            val amountToSend = if (remainingPayload < sizeOfPayload) {
                remainingPayload
            } else {
                sizeOfPayload
            }

            remainingPayload -= amountToSend


            // to properly do this, we have to be careful with the underlying protocol, in order to avoid copying the buffer multiple times.
            // the data that will be sent is object data + buffer data. We are sending the SAME parent buffer, just at different spots and
            // with different headers -- so we don't copy out the data repeatedly

            // fortunately, the way that serialization works, we can safely ADD data to the tail and then appropriately read it off
            // on the receiving end without worry.

            try {
                val varIntSize = OptimizeUtilsByteBuf.intLength(sizeOfPayload, true)
                val writeIndex = payloadSent - headerSize - varIntSize

                // write out our header data (this will OVERWRITE previous data!)
                originalBuffer.putBytes(writeIndex, header)

                // write out the payload size using optimized data structures.
                writeVarInt(originalBuffer, writeIndex + headerSize, sizeOfPayload, true)

                // write out the payload
                endPoint.dataSend(
                    publication,
                    originalBuffer,
                    writeIndex,
                    headerSize + varIntSize + amountToSend,
                    sendIdleStrategy,
                    connection,
                    false
                )

                payloadSent += amountToSend
            } catch (e: Exception) {
                val failMessage = StreamingControl(StreamingState.FAILED, streamSessionId)

                val failSent = endPoint.writeUnsafe(failMessage, publication, sendIdleStrategy, connection, kryo)
                if (!failSent) {
                    // something SUPER wrong!
                    // more critical error sending the message. we shouldn't retry or anything.
                    val errorMessage = "[${publication.sessionId()}] Abnormal failure while streaming content."

                    // either client or server. No other choices. We create an exception, because it's more useful!
                    val exception = endPoint.newException(errorMessage)

                    // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
                    // where we see who is calling "send()"
                    exception.cleanStackTrace(3)
                    throw exception
                } else {
                    // send it up!
                    throw e
                }
            }
        }

        // send the last block of data
        val finishedMessage = StreamingControl(StreamingState.FINISHED, streamSessionId, payloadSent.toLong())

        return endPoint.writeUnsafe(finishedMessage, publication, sendIdleStrategy, connection, kryo)
    }
}
