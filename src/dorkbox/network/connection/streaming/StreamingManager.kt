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
import dorkbox.bytes.OptimizeUtilsByteArray
import dorkbox.bytes.OptimizeUtilsByteBuf
import dorkbox.collections.LockFreeLongMap
import dorkbox.network.Configuration
import dorkbox.network.connection.Connection
import dorkbox.network.connection.CryptoManagement
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.ListenerManager.Companion.cleanAllStackTrace
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTrace
import dorkbox.network.serialization.AeronInput
import dorkbox.network.serialization.AeronOutput
import dorkbox.network.serialization.KryoWriter
import dorkbox.os.OS
import dorkbox.util.Sys
import io.aeron.Publication
import org.agrona.MutableDirectBuffer
import org.agrona.concurrent.IdleStrategy
import org.agrona.concurrent.UnsafeBuffer
import org.slf4j.Logger
import java.io.File
import java.io.FileInputStream

internal class StreamingManager<CONNECTION : Connection>(private val logger: Logger, val config: Configuration) {

    companion object {
        private const val KILOBYTE = 1024
        private const val MEGABYTE = 1024 * KILOBYTE
        private const val GIGABYTE = 1024 * MEGABYTE
        private const val TERABYTE = 1024L * GIGABYTE

        @Suppress("UNUSED_CHANGED_VALUE", "SameParameterValue")
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


    private val streamingDataTarget = LockFreeLongMap<StreamingControl>()
    private val streamingDataInMemory = LockFreeLongMap<StreamingWriter>()


    /**
     * What is the max stream size that can exist in memory when deciding if data blocks are in memory or temp-file on disk
     */
    private val maxStreamSizeInMemoryInBytes = config.maxStreamSizeInMemoryMB * MEGABYTE

    fun getFile(connection: CONNECTION, endPoint: EndPoint<CONNECTION>, messageStreamId: Int): File {
        // NOTE: the stream session ID is a combination of the connection ID + random ID (on the receiving side),
        //      otherwise clients can abuse it and corrupt OTHER clients data!!
        val streamId = (connection.id.toLong() shl 4) or messageStreamId.toLong()

        val output = streamingDataInMemory[streamId]
        return if (output is FileWriter) {
            streamingDataInMemory.remove(streamId)
            output.file
        } else {
            // something SUPER wrong!
            // more critical error sending the message. we shouldn't retry or anything.
            val errorMessage = "Error while reading file output, stream $streamId was of the wrong type!"

            // either client or server. No other choices. We create an exception, because it's more useful!
            throw endPoint.newException(errorMessage)
        }
    }

    /**
     * NOTE: MUST BE ON THE AERON THREAD!
     *
    * Reassemble/figure out the internal message pieces. Processed always on the same thread
    */
    fun processControlMessage(
        message: StreamingControl,
        endPoint: EndPoint<CONNECTION>,
        connection: CONNECTION
    ) {
        // NOTE: the stream session ID is a combination of the connection ID + random ID (on the receiving side),
        //      otherwise clients can abuse it and corrupt OTHER clients data!!
        val streamId = (connection.id.toLong() shl 4) or message.streamId.toLong()

        when (message.state) {
            StreamingState.START -> {
                // message.totalSize > maxInMemory  OR  if we are a file, then write to a temp file INSTEAD
                if (message.isFile || message.totalSize > maxStreamSizeInMemoryInBytes) {
                    var fileName = "${config.appId}_${streamId}_${connection.id}.tmp"

                    var tempFileLocation = OS.TEMP_DIR.resolve(fileName)
                    while (tempFileLocation.canRead()) {
                        fileName = "${config.appId}_${streamId}_${connection.id}_${CryptoManagement.secureRandom.nextInt()}.tmp"
                        tempFileLocation = OS.TEMP_DIR.resolve(fileName)
                    }
                    tempFileLocation.deleteOnExit()

                    val prettySize = Sys.getSizePretty(message.totalSize)

                    if (endPoint.logger.isInfoEnabled) {
                        endPoint.logger.info("Saving $prettySize of streaming data [${streamId}] to: $tempFileLocation")
                    }
                    streamingDataInMemory[streamId] = FileWriter(message.totalSize.toInt(), tempFileLocation)
                } else {
                    if (endPoint.logger.isInfoEnabled) {
                        endPoint.logger.info("Saving streaming data [${streamId}] in memory")
                    }
                    // .toInt is safe because we know the total size is < than maxStreamSizeInMemoryInBytes
                    streamingDataInMemory[streamId] = AeronWriter(message.totalSize.toInt())
                }

                // this must be last
                streamingDataTarget[streamId] = message
            }

            StreamingState.FINISHED -> {
                // NOTE: cannot be on a coroutine before kryo usage!

                if (message.isFile) {
                    // we do not do anything with this file yet! The serializer has to return this instance!
                    val output = streamingDataInMemory[streamId]

                    if (output is FileWriter) {
                        output.finishAndClose()
                        // we don't need to do anything else (no de-serialization into an object) because we are already our target object
                        return
                    } else {
                        // something SUPER wrong!
                        // more critical error sending the message. we shouldn't retry or anything.
                        val errorMessage = "Error while processing streaming content, stream $streamId was supposed to be a FileWriter."

                        // either client or server. No other choices. We create an exception, because it's more useful!
                        throw endPoint.newException(errorMessage)
                    }
                }

                // get the data out and send messages!
                val output = streamingDataInMemory.remove(streamId)

                val input = when (output) {
                    is AeronWriter -> {
                        // the position can be wrong, especially if there are multiple threads setting the data
                        output.setPosition(output.size)
                        AeronInput(output.internalBuffer)
                    }
                    is FileWriter -> {
                        // if we are too large to fit in memory while streaming, we store it on disk.
                        output.finishAndClose()

                        val fileInputStream = FileInputStream(output.file)
                        Input(fileInputStream)
                    }
                    else -> {
                        null
                    }
                }

                val streamedMessage = if (input != null) {
                        val kryo = endPoint.serialization.takeRead()
                        try {
                            kryo.read(connection, input)
                        } catch (e: Exception) {
                            // something SUPER wrong!
                            // more critical error sending the message. we shouldn't retry or anything.
                            val errorMessage = "Error deserializing message from received streaming content, stream $streamId"

                            // either client or server. No other choices. We create an exception, because it's more useful!
                            throw endPoint.newException(errorMessage, e)
                        } finally {
                            endPoint.serialization.putRead(kryo)
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


                // this can be a regular message or an RMI message. Redispatch!
                endPoint.processMessageFromChannel(connection, streamedMessage)
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
     * NOTE: MUST BE ON THE AERON THREAD BECAUSE THIS MUST BE SINGLE THREADED!!!
     *
     * Reassemble/figure out the internal message pieces
     *
     * NOTE sending a huge file can cause other network traffic delays!
     */
    fun processDataMessage(message: StreamingData, endPoint: EndPoint<CONNECTION>, connection: CONNECTION) {
        // the receiving data will ALWAYS come sequentially, but there might be OTHER streaming data received meanwhile.
        // NOTE: the stream session ID is a combination of the connection ID + random ID (on the receiving side)
        val streamId = (connection.id.toLong() shl 4) or message.streamId.toLong()

        val dataWriter = streamingDataInMemory[streamId]
        if (dataWriter != null) {
            dataWriter.writeBytes(message.startPosition, message.payload!!)
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
        val failMessage = StreamingControl(StreamingState.FAILED, false, streamSessionId)

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
     * @param originalBuffer this is the ORIGINAL object data that is to be blocks sent across the wire
     *
     * @return true if ALL the message blocks were successfully sent by aeron, false otherwise. Exceptions are caught and rethrown!
     */
    fun send(
        publication: Publication,
        originalBuffer: MutableDirectBuffer,
        maxMessageSize: Int,
        objectSize: Int,
        endPoint: EndPoint<CONNECTION>,
        kryo: KryoWriter<CONNECTION>,
        sendIdleStrategy: IdleStrategy,
        connection: CONNECTION
    ): Boolean {
        // NOTE: our max object size for IN-MEMORY messages is an INT. For file transfer it's a LONG (so everything here is cast to a long)
        var remainingPayload = objectSize
        var payloadSent = 0


        // NOTE: the stream session ID is a combination of the connection ID + random ID (on the receiving side)
        val streamSessionId = CryptoManagement.secureRandom.nextInt()

        // tell the other side how much data we are sending
        val startMessage = StreamingControl(StreamingState.START, false, streamSessionId, remainingPayload.toLong())

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

        // payload size is for a PRODUCER, and not SUBSCRIBER, so we have to include this amount every time.

        var sizeOfBlockData = maxMessageSize

        val header: ByteArray
        val headerSize: Int

        try {
            // This is REUSED to prevent garbage collection issues.
            val blockData = StreamingData(streamSessionId)
            val objectBuffer = kryo.write(connection, blockData)
            headerSize = objectBuffer.position()
            header = ByteArray(headerSize)

            // we have to account for the header + the MAX optimized int size (position and data-length)
            val dataSize = headerSize + 5 + 5
            sizeOfBlockData -= dataSize

            // this size might be a LITTLE too big, but that's ok, since we only make this specific buffer once.
            val blockBuffer = AeronOutput(dataSize)

            // copy out our header info
            objectBuffer.internalBuffer.getBytes(0, header, 0, headerSize)

            // write out our header
            blockBuffer.writeBytes(header)

            // write out the start-position (of the payload). First start-position is always 0
            val positionIntSize = blockBuffer.writeVarInt(0, true)

            // write out the payload size
            val payloadIntSize = blockBuffer.writeVarInt(sizeOfBlockData, true)

            // write out the payload. Our resulting data written out is the ACTUAL MTU of aeron.
            originalBuffer.getBytes(0, blockBuffer.internalBuffer, headerSize + positionIntSize + payloadIntSize, sizeOfBlockData)

            remainingPayload -= sizeOfBlockData
            payloadSent += sizeOfBlockData

            // we reuse/recycle objects, so the payload size is not EXACTLY what is specified
            val reusedPayloadSize = headerSize + positionIntSize + payloadIntSize + sizeOfBlockData

            val success = endPoint.aeronDriver.send(
                publication = publication,
                internalBuffer = blockBuffer.internalBuffer,
                bufferClaim = kryo.bufferClaim,
                offset = 0,
                objectSize = reusedPayloadSize,
                sendIdleStrategy = sendIdleStrategy,
                connection = connection,
                abortEarly = false,
                listenerManager = endPoint.listenerManager
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
            val amountToSend = if (remainingPayload < sizeOfBlockData) {
                remainingPayload
            } else {
                sizeOfBlockData
            }

            remainingPayload -= amountToSend


            // to properly do this, we have to be careful with the underlying protocol, in order to avoid copying the buffer multiple times.
            // the data that will be sent is object data + buffer data. We are sending the SAME parent buffer, just at different spots and
            // with different headers -- so we don't copy out the data repeatedly

            // fortunately, the way that serialization works, we can safely ADD data to the tail and then appropriately read it off
            // on the receiving end without worry.

/// TODO: Compression/encryption??

            try {
                val positionIntSize = OptimizeUtilsByteBuf.intLength(payloadSent, true)
                val payloadIntSize = OptimizeUtilsByteBuf.intLength(amountToSend, true)
                val writeIndex = payloadSent - headerSize - positionIntSize - payloadIntSize

                // write out our header data (this will OVERWRITE previous data!)
                originalBuffer.putBytes(writeIndex, header)

                // write out the payload start position
                writeVarInt(originalBuffer, writeIndex + headerSize, payloadSent, true)

                // write out the payload size
                writeVarInt(originalBuffer, writeIndex + headerSize + positionIntSize, amountToSend, true)

                // we reuse/recycle objects, so the payload size is not EXACTLY what is specified
                val reusedPayloadSize = headerSize + payloadIntSize + positionIntSize + amountToSend

                // write out the payload
                val success = endPoint.aeronDriver.send(
                    publication = publication,
                    internalBuffer = originalBuffer,
                    bufferClaim = kryo.bufferClaim,
                    offset = writeIndex,
                    objectSize = reusedPayloadSize,
                    sendIdleStrategy = sendIdleStrategy,
                    connection = connection,
                    abortEarly = false,
                    listenerManager = endPoint.listenerManager
                )

                if (!success) {
                    // critical errors have an exception. Normal "the connection is closed" do not.
                    return false
                }

                payloadSent += amountToSend
            } catch (e: Exception) {
                val failMessage = StreamingControl(StreamingState.FAILED, false, streamSessionId)

                val failSent = endPoint.writeUnsafe(failMessage, publication, sendIdleStrategy, connection, kryo)
                if (!failSent) {
                    // something SUPER wrong!
                    // more critical error sending the message. we shouldn't retry or anything.
                    val errorMessage = "[${publication.sessionId()}] Abnormal failure with exception while streaming content."

                    // either client or server. No other choices. We create an exception, because it's more useful!
                    val exception = endPoint.newException(errorMessage, e)
                    exception.cleanAllStackTrace()
                    throw exception
                } else {
                    // send it up!
                    throw e
                }
            }
        }

        // send the last block of data
        val finishedMessage = StreamingControl(StreamingState.FINISHED, false, streamSessionId, payloadSent.toLong())

        return endPoint.writeUnsafe(finishedMessage, publication, sendIdleStrategy, connection, kryo)
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
     * @param streamSessionId the stream session ID is a combination of the connection ID + random ID (on the receiving side)
     *
     * @return true if ALL the message blocks were successfully sent by aeron, false otherwise. Exceptions are caught and rethrown!
     */
    @Suppress("SameParameterValue")
    fun sendFile(
        file: File,
        publication: Publication,
        endPoint: EndPoint<CONNECTION>,
        kryo: KryoWriter<CONNECTION>,
        sendIdleStrategy: IdleStrategy,
        connection: CONNECTION,
        streamSessionId: Int
    ): Boolean {
        val maxMessageSize = connection.maxMessageSize.toLong()
        val fileInputStream = file.inputStream()

        // if the message is a file, we xfer the file AS a file, and leave it as a temp file (with a file reference to it) on the remote endpoint
        // the temp file will be unique.

        // NOTE: our max object size for IN-MEMORY messages is an INT. For file transfer it's a LONG (so everything here is cast to a long)
        var remainingPayload = file.length()
        var payloadSent = 0

        // tell the other side how much data we are sending
        val startMessage = StreamingControl(StreamingState.START, true, streamSessionId, remainingPayload)

        val startSent = endPoint.writeUnsafe(startMessage, publication, sendIdleStrategy, connection, kryo)
        if (!startSent) {
            fileInputStream.close()

            // more critical error sending the message. we shouldn't retry or anything.
            val errorMessage = "[${publication.sessionId()}] Error starting streaming file."

            // either client or server. No other choices. We create an exception, because it's more useful!
            val exception = endPoint.newException(errorMessage)

            // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
            // where we see who is calling "send()"
            exception.cleanStackTrace(3)
            throw exception
        }



        // we do the FIRST block super-weird, because of the way we copy data around (we inject headers),
        // so the first message is SUPER tiny and is a COPY, the rest are no-copy.

        // payload size is for a PRODUCER, and not SUBSCRIBER, so we have to include this amount every time.

        // we don't know which is larger, the max message size or the file size!
        var sizeOfBlockData = maxMessageSize.coerceAtMost(remainingPayload).toInt()

        val headerSize: Int

        val buffer: ByteArray
        val blockBuffer: UnsafeBuffer

        try {
            // This is REUSED to prevent garbage collection issues.
            val blockData = StreamingData(streamSessionId)
            val objectBuffer = kryo.write(connection, blockData)
            headerSize = objectBuffer.position()

            // we have to account for the header + the MAX optimized int size (position and data-length)
            val dataSize = headerSize + 5 + 5
            sizeOfBlockData -= dataSize

            // this size might be a LITTLE too big, but that's ok, since we only make this specific buffer once.
            buffer = ByteArray(sizeOfBlockData + dataSize)
            blockBuffer = UnsafeBuffer(buffer)

            // copy out our header info (this skips the header object)
            objectBuffer.internalBuffer.getBytes(0, buffer, 0, headerSize)

            // write out the start-position (of the payload). First start-position is always 0
            val positionIntSize = OptimizeUtilsByteArray.writeInt(buffer, 0, true, headerSize)

            // write out the payload size
            val payloadIntSize = OptimizeUtilsByteArray.writeInt(buffer, sizeOfBlockData, true, headerSize + positionIntSize)

            // write out the payload. Our resulting data written out is the ACTUAL MTU of aeron.
            val readBytes = fileInputStream.read(buffer, headerSize + positionIntSize + payloadIntSize, sizeOfBlockData)
            if (readBytes != sizeOfBlockData) {
                // something SUPER wrong!
                // more critical error sending the message. we shouldn't retry or anything.
                val errorMessage = "[${publication.sessionId()}] Abnormal failure while streaming file (read bytes was wrong! ${readBytes} - ${sizeOfBlockData}."

                // either client or server. No other choices. We create an exception, because it's more useful!
                val exception = endPoint.newException(errorMessage)

                // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
                // where we see who is calling "send()"
                exception.cleanStackTrace(3)
                throw exception
            }

            remainingPayload -= sizeOfBlockData
            payloadSent += sizeOfBlockData

            // we reuse/recycle objects, so the payload size is not EXACTLY what is specified
            val reusedPayloadSize = headerSize + positionIntSize + payloadIntSize + sizeOfBlockData

            val success = endPoint.aeronDriver.send(
                publication = publication,
                internalBuffer = blockBuffer,
                bufferClaim = kryo.bufferClaim,
                offset = 0,
                objectSize = reusedPayloadSize,
                sendIdleStrategy = sendIdleStrategy,
                connection = connection,
                abortEarly = false,
                listenerManager = endPoint.listenerManager
            )

            if (!success) {
                // something SUPER wrong!
                // more critical error sending the message. we shouldn't retry or anything.
                val errorMessage = "[${publication.sessionId()}] Abnormal failure while streaming file."

                // either client or server. No other choices. We create an exception, because it's more useful!
                val exception = endPoint.newException(errorMessage)

                // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
                // where we see who is calling "send()"
                exception.cleanStackTrace(3)
                throw exception
            }
        } catch (e: Exception) {
            fileInputStream.close()

            sendFailMessageAndThrow(e, streamSessionId, publication, endPoint, sendIdleStrategy, connection, kryo)
            return false // doesn't actually get here because exceptions are thrown, but this makes the IDE happy.
        }


        val aeronDriver = endPoint.aeronDriver
        val listenerManager = endPoint.listenerManager

        // now send the block as fast as possible. Aeron will have us back-off if we send too quickly
        while (remainingPayload > 0) {
            val amountToSend = if (remainingPayload < sizeOfBlockData) {
                remainingPayload.toInt()
            } else {
                sizeOfBlockData
            }

            remainingPayload -= amountToSend


            // to properly do this, we have to be careful with the underlying protocol, in order to avoid copying the buffer multiple times.
            // the data that will be sent is object data + buffer data. We are sending the SAME parent buffer, just at different spots and
            // with different headers -- so we don't copy out the data repeatedly

            // fortunately, the way that serialization works, we can safely ADD data to the tail and then appropriately read it off
            // on the receiving end without worry.

/// TODO: Compression/encryption??

            try {
                // write out the payload start position
                val positionIntSize = OptimizeUtilsByteArray.writeInt(buffer, payloadSent, true, headerSize)
                // write out the payload size
                val payloadIntSize = OptimizeUtilsByteArray.writeInt(buffer, amountToSend, true, headerSize + positionIntSize)

                // write out the payload. Our resulting data written out is the ACTUAL MTU of aeron.
                val readBytes = fileInputStream.read(buffer, headerSize + positionIntSize + payloadIntSize, amountToSend)
                if (readBytes != amountToSend) {
                    // something SUPER wrong!
                    // more critical error sending the message. we shouldn't retry or anything.
                    val errorMessage = "[${publication.sessionId()}] Abnormal failure while streaming file (read bytes was wrong! ${readBytes} - ${amountToSend}."

                    // either client or server. No other choices. We create an exception, because it's more useful!
                    val exception = endPoint.newException(errorMessage)

                    // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
                    // where we see who is calling "send()"
                    exception.cleanStackTrace(3)
                    throw exception
                }

                // we reuse/recycle objects, so the payload size is not EXACTLY what is specified
                val reusedPayloadSize = headerSize + positionIntSize + payloadIntSize + amountToSend

                // write out the payload
                aeronDriver.send(
                    publication = publication,
                    internalBuffer = blockBuffer,
                    bufferClaim = kryo.bufferClaim,
                    offset = 0, // 0 because we are not reading the entire file at once
                    objectSize = reusedPayloadSize,
                    sendIdleStrategy = sendIdleStrategy,
                    connection = connection,
                    abortEarly = false,
                    listenerManager = listenerManager
                )

                payloadSent += amountToSend
            } catch (e: Exception) {
                fileInputStream.close()

                val failMessage = StreamingControl(StreamingState.FAILED, false, streamSessionId)

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

        fileInputStream.close()

        // send the last block of data
        val finishedMessage = StreamingControl(StreamingState.FINISHED, true, streamSessionId, payloadSent.toLong())

        return endPoint.writeUnsafe(finishedMessage, publication, sendIdleStrategy, connection, kryo)
    }
}
