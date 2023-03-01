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

import com.esotericsoftware.kryo.KryoException
import dorkbox.bytes.OptimizeUtilsByteBuf
import dorkbox.collections.LockFreeHashMap
import dorkbox.network.connection.Connection
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.ListenerManager
import dorkbox.network.serialization.AeronInput
import dorkbox.network.serialization.AeronOutput
import dorkbox.network.serialization.KryoExtra
import io.aeron.Publication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KLogger
import org.agrona.MutableDirectBuffer
import org.agrona.concurrent.IdleStrategy
import java.security.SecureRandom

internal class StreamingManager<CONNECTION : Connection>(
    private val logger: KLogger,
    private val actionDispatch: CoroutineScope
) {
    private val streamingDataTarget = LockFreeHashMap<Long, StreamingControl>()
    private val streamingDataInMemory = LockFreeHashMap<Long, AeronOutput>()

    companion object {
        val random = SecureRandom()

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


    /**
    * Reassemble/figure out the internal message pieces. Processed always on the same thread
    */
    fun processControlMessage(
        message: StreamingControl,
        kryo: KryoExtra<CONNECTION>,
        endPoint: EndPoint<CONNECTION>,
        connection: CONNECTION
    ) {
        // NOTE: the stream session ID is a combination of the connection ID + random ID (on the receiving side),
        //      otherwise clients can abuse it and corrupt OTHER clients data!!
        val streamId = (connection.id.toLong() shl 4) or message.streamId.toLong()

        when (message.state) {
            StreamingState.START -> {
                streamingDataTarget[streamId] = message

                if (!message.isFile) {
                    streamingDataInMemory[streamId] = AeronOutput()
                } else {
                    // write the file to disk
                }
            }
            StreamingState.FINISHED -> {
                // get the data out and send messages!
                if (!message.isFile) {
                    val output = streamingDataInMemory.remove(streamId)
                    if (output != null) {
                        val streamedMessage: Any?

                        try {
                            val input = AeronInput(output.internalBuffer)
                            streamedMessage = kryo.read(input)
                        } catch (e: Exception) {
                            if (e is KryoException) {
                                // YIKES. this isn't good
                                // print the list of OUR registered message types, emit them (along with the "error" class index)
                                // send a message to the remote end, that we had an error for class XYZ, and have it emit ITS message types
//                                endPoint.serialization.logKryoMessages()
//
//
//                                val failSent = endPoint.writeUnsafe(tempWriteKryo, failMessage, publication, sendIdleStrategy, connection)
//                                if (!failSent) {
//                                    // something SUPER wrong!
//                                    // more critical error sending the message. we shouldn't retry or anything.
//                                    val errorMessage = "[${publication.sessionId()}] Abnormal failure while streaming content."
//
//                                    // either client or server. No other choices. We create an exception, because it's more useful!
//                                    val exception = endPoint.newException(errorMessage)
//
//                                    // +2 because we do not want to see the stack for the abstract `newException`
//                                    // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
//                                    // where we see who is calling "send()"
//                                    ListenerManager.cleanStackTrace(exception, 5)
//                                    throw exception
//                                } else {
//                                    // send it up!
//                                    throw e
//                                }


                            }

                            // something SUPER wrong!
                            // more critical error sending the message. we shouldn't retry or anything.
                            val errorMessage = "Error serializing message from received streaming content, stream $streamId"

                            // either client or server. No other choices. We create an exception, because it's more useful!
                            val exception = endPoint.newException(errorMessage, e)

                            // +2 because we do not want to see the stack for the abstract `newException`
                            // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
                            // where we see who is calling "send()"
                            ListenerManager.cleanStackTrace(exception, 2)
                            throw exception
                        }

                        if (streamedMessage != null) {
                            // NOTE: This MUST be on a new co-routine
                            actionDispatch.launch {
                                val listenerManager = endPoint.listenerManager

                                try {
                                    var hasListeners = listenerManager.notifyOnMessage(connection, streamedMessage)

                                    // each connection registers, and is polled INDEPENDENTLY for messages.
                                    hasListeners = hasListeners or connection.notifyOnMessage(streamedMessage)

                                    if (!hasListeners) {
                                        logger.error("No streamed message callbacks found for ${streamedMessage::class.java.name}")
                                    }
                                } catch (e: Exception) {
                                    logger.error("Error processing message ${streamedMessage::class.java.name}", e)
                                    listenerManager.notifyError(connection, e)
                                }
                            }
                        } else {
                            // something SUPER wrong!
                            // more critical error sending the message. we shouldn't retry or anything.
                            val errorMessage = "Error while processing streaming content, stream $streamId was null."

                            // either client or server. No other choices. We create an exception, because it's more useful!
                            val exception = endPoint.newException(errorMessage)

                            // +2 because we do not want to see the stack for the abstract `newException`
                            // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
                            // where we see who is calling "send()"
                            ListenerManager.cleanStackTrace(exception, 2)
                            throw exception
                        }
                    } else {
                        // something SUPER wrong!
                        // more critical error sending the message. we shouldn't retry or anything.
                        val errorMessage = "Error while receiving streaming content, stream $streamId not available."

                        // either client or server. No other choices. We create an exception, because it's more useful!
                        val exception = endPoint.newException(errorMessage)

                        // +2 because we do not want to see the stack for the abstract `newException`
                        // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
                        // where we see who is calling "send()"
                        ListenerManager.cleanStackTrace(exception, 2)
                        throw exception
                    }
                } else {
                    // we are a file, so process accordingly
                    println("processing file")
                    // we should save it WHERE exactly?

                }
            }
            StreamingState.FAILED -> {
                // clear all state
                // something SUPER wrong!
                // more critical error sending the message. we shouldn't retry or anything.
                val errorMessage = "Failure while receiving streaming content for stream $streamId"

                // either client or server. No other choices. We create an exception, because it's more useful!
                val exception = endPoint.newException(errorMessage)

                // +2 because we do not want to see the stack for the abstract `newException`
                // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
                // where we see who is calling "send()"
                ListenerManager.cleanStackTrace(exception, 2)
                throw exception
            }
            StreamingState.UNKNOWN ->  {
                // something SUPER wrong!
                // more critical error sending the message. we shouldn't retry or anything.
                val errorMessage = "Unknown failure while receiving streaming content for stream $streamId"

                // either client or server. No other choices. We create an exception, because it's more useful!
                val exception = endPoint.newException(errorMessage)

                // +2 because we do not want to see the stack for the abstract `newException`
                // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
                // where we see who is calling "send()"
                ListenerManager.cleanStackTrace(exception, 2)
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
            synchronized(streamingDataInMemory) {
                streamingDataInMemory.getOrPut(streamId) { AeronOutput() }!!.writeBytes(message.payload!!)
            }
        } else {
            // something SUPER wrong!
            // more critical error sending the message. we shouldn't retry or anything.
            val errorMessage = "Abnormal failure while receiving streaming content, stream $streamId not available."

            // either client or server. No other choices. We create an exception, because it's more useful!
            val exception = endPoint.newException(errorMessage)

            // +2 because we do not want to see the stack for the abstract `newException`
            // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
            // where we see who is calling "send()"
            ListenerManager.cleanStackTrace(exception, 5)
            throw exception
        }
    }

    private fun sendFailMessageAndThrow(
        e: Exception,
        streamSessionId: Int,
        publication: Publication,
        endPoint: EndPoint<CONNECTION>,
        kryoExtra: KryoExtra<Connection>,
        sendIdleStrategy: IdleStrategy,
        connection: Connection
    ) {
        val failMessage = StreamingControl(StreamingState.FAILED, streamSessionId)

        val failSent = endPoint.writeUnsafe(kryoExtra, failMessage, publication, sendIdleStrategy, connection)
        if (!failSent) {
            // something SUPER wrong!
            // more critical error sending the message. we shouldn't retry or anything.
            val errorMessage = "[${publication.sessionId()}] Abnormal failure while streaming content."

            // either client or server. No other choices. We create an exception, because it's more useful!
            val exception = endPoint.newException(errorMessage)

            // +2 because we do not want to see the stack for the abstract `newException`
            // +4 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
            // where we see who is calling "send()"
            ListenerManager.cleanStackTrace(exception, 6)
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
     * @param internalBuffer this is the ORIGINAL object data that is to be "chunked" and sent across the wire
     * @return true if ALL the message chunks were successfully sent by aeron, false otherwise. Exceptions are caught and rethrown!
     */
    fun send(
        publication: Publication,
        internalBuffer: MutableDirectBuffer,
        objectSize: Int,
        endPoint: EndPoint<CONNECTION>,
        tempWriteKryo: KryoExtra<Connection>,
        sendIdleStrategy: IdleStrategy,
        connection: Connection
    ): Boolean {

        // NOTE: our max object size for IN-MEMORY messages is an INT. For file transfer it's a LONG (so everything here is cast to a long)
        var remainingPayload = objectSize
        var payloadSent = 0

        // NOTE: the stream session ID is a combination of the connection ID + random ID (on the receiving side)
        val streamSessionId = random.nextInt()

        // tell the other side how much data we are sending
        val startMessage = StreamingControl(StreamingState.START, streamSessionId, objectSize.toLong())

        val startSent = endPoint.writeUnsafe(tempWriteKryo, startMessage, publication, sendIdleStrategy, connection)
        if (!startSent) {
            // more critical error sending the message. we shouldn't retry or anything.
            val errorMessage = "[${publication.sessionId()}] Error starting streaming content."

            // either client or server. No other choices. We create an exception, because it's more useful!
            val exception = endPoint.newException(errorMessage)

            // +2 because we do not want to see the stack for the abstract `newException`
            // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
            // where we see who is calling "send()"
            ListenerManager.cleanStackTrace(exception, 5)
            throw exception
        }


        // we do the FIRST chunk super-weird, because of the way we copy data around (we inject headers,
        // so the first message is SUPER tiny and is a COPY, the rest are no-copy.

        // This is REUSED to prevent garbage collection issues.
        val chunkData = StreamingData(streamSessionId)

        // payload size is for a PRODUCER, and not SUBSCRIBER, so we have to include this amount every time.
        // MINOR fragmentation by aeron is OK, since that will greatly speed up data transfer rates!

        // the maxPayloadLength MUST ABSOLUTELY be less that the max size + header!
        var sizeOfPayload = publication.maxMessageLength() - 200

        val header: ByteArray
        val headerSize: Int

        try {
            val objectBuffer = tempWriteKryo.write(connection, chunkData)
            headerSize = objectBuffer.position()
            header = ByteArray(headerSize)

            // we have to account for the header + the MAX optimized int size
            sizeOfPayload -= (headerSize + 5)

            // this size might be a LITTLE too big, but that's ok, since we only make this specific buffer once.
            val chunkBuffer = AeronOutput(headerSize + sizeOfPayload)

            // copy out our header info
            objectBuffer.internalBuffer.getBytes(0, header, 0, headerSize)

            // write out our header
            chunkBuffer.writeBytes(header)

            // write out the payload size using optimized data structures.
            val varIntSize = chunkBuffer.writeVarInt(sizeOfPayload, true)

            // write out the payload. Our resulting data written out is the ACTUAL MTU of aeron.
            internalBuffer.getBytes(0, chunkBuffer.internalBuffer, headerSize + varIntSize, sizeOfPayload)

            remainingPayload -= sizeOfPayload
            payloadSent += sizeOfPayload

            val success = endPoint.dataSend(
                publication,
                chunkBuffer.internalBuffer,
                0,
                headerSize + varIntSize + sizeOfPayload,
                sendIdleStrategy,
                connection
            )
            if (!success) {
                // something SUPER wrong!
                // more critical error sending the message. we shouldn't retry or anything.
                val errorMessage = "[${publication.sessionId()}] Abnormal failure while streaming content."

                // either client or server. No other choices. We create an exception, because it's more useful!
                val exception = endPoint.newException(errorMessage)

                // +2 because we do not want to see the stack for the abstract `newException`
                // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
                // where we see who is calling "send()"
                ListenerManager.cleanStackTrace(exception, 5)
                throw exception
            }
        } catch (e: Exception) {
            sendFailMessageAndThrow(e, streamSessionId, publication, endPoint, tempWriteKryo, sendIdleStrategy, connection)
            return false // doesn't actually get here because exceptions are thrown, but this makes the IDE happy.
        }

        // now send the chunks as fast as possible. Aeron will have us back-off if we send too quickly
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
                internalBuffer.putBytes(writeIndex, header)

                // write out the payload size using optimized data structures.
                writeVarInt(internalBuffer, writeIndex + headerSize, sizeOfPayload, true)

                // write out the payload
                endPoint.dataSend(
                    publication,
                    internalBuffer,
                    writeIndex,
                    headerSize + varIntSize + amountToSend,
                    sendIdleStrategy,
                    connection
                )

                payloadSent += amountToSend
            } catch (e: Exception) {
                val failMessage = StreamingControl(StreamingState.FAILED, streamSessionId)

                val failSent = endPoint.writeUnsafe(tempWriteKryo, failMessage, publication, sendIdleStrategy, connection)
                if (!failSent) {
                    // something SUPER wrong!
                    // more critical error sending the message. we shouldn't retry or anything.
                    val errorMessage = "[${publication.sessionId()}] Abnormal failure while streaming content."

                    // either client or server. No other choices. We create an exception, because it's more useful!
                    val exception = endPoint.newException(errorMessage)

                    // +2 because we do not want to see the stack for the abstract `newException`
                    // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
                    // where we see who is calling "send()"
                    ListenerManager.cleanStackTrace(exception, 5)
                    throw exception
                } else {
                    // send it up!
                    throw e
                }
            }
        }

        // send the last chunk of data
        val finishedMessage = StreamingControl(StreamingState.FINISHED, streamSessionId, payloadSent.toLong())

        return endPoint.writeUnsafe(tempWriteKryo, finishedMessage, publication, sendIdleStrategy, connection)
    }
}
