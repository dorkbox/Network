/*
 * Copyright 2014-2022 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network.aeron

import io.aeron.driver.status.PerImageIndicator
import io.aeron.driver.status.PublisherLimit
import io.aeron.driver.status.PublisherPos
import io.aeron.driver.status.ReceiverHwm
import io.aeron.driver.status.ReceiverPos
import io.aeron.driver.status.SenderLimit
import io.aeron.driver.status.SenderPos
import io.aeron.driver.status.StreamCounter
import io.aeron.driver.status.SubscriberPos
import org.agrona.DirectBuffer
import org.agrona.concurrent.status.CountersReader
import java.util.*

/**
 * Tool for taking a snapshot of Aeron streams backlog information and some explanation for the
 * [StreamStat] counters.
 *
 *
 * Each stream managed by the [io.aeron.driver.MediaDriver] will be sampled and printed out on [System.out].
 */
class BacklogStat
/**
 * Construct by using a [CountersReader] which can be obtained from [Aeron.countersReader].
 *
 * @param counters to read for tracking positions.
 */
    (private val counters: CountersReader) {
    /**
     * Take a snapshot of all the backlog information and group by stream.
     *
     * @return a snapshot of all the backlog information and group by stream.
     */
    fun snapshot(): Map<StreamCompositeKey, StreamBacklog> {
        val streams: MutableMap<StreamCompositeKey, StreamBacklog> = HashMap()
        counters.forEach { counterId: Int, typeId: Int, keyBuffer: DirectBuffer, _: String? ->
            if (typeId >= PublisherLimit.PUBLISHER_LIMIT_TYPE_ID && typeId <= ReceiverPos.RECEIVER_POS_TYPE_ID || typeId == SenderLimit.SENDER_LIMIT_TYPE_ID || typeId == PerImageIndicator.PER_IMAGE_TYPE_ID || typeId == PublisherPos.PUBLISHER_POS_TYPE_ID) {
                val key = StreamCompositeKey(
                    keyBuffer.getInt(StreamCounter.SESSION_ID_OFFSET),
                    keyBuffer.getInt(StreamCounter.STREAM_ID_OFFSET),
                    keyBuffer.getStringAscii(StreamCounter.CHANNEL_OFFSET)
                )
                val streamBacklog = streams.computeIfAbsent(key) { _: StreamCompositeKey? -> StreamBacklog() }
                val registrationId = keyBuffer.getLong(StreamCounter.REGISTRATION_ID_OFFSET)
                val value = counters.getCounterValue(counterId)
                when (typeId) {
                    PublisherLimit.PUBLISHER_LIMIT_TYPE_ID -> {
                        streamBacklog.createPublisherIfAbsent().registrationId(registrationId)
                        streamBacklog.createPublisherIfAbsent().limit(value)
                    }
                    PublisherPos.PUBLISHER_POS_TYPE_ID -> {
                        streamBacklog.createPublisherIfAbsent().registrationId(registrationId)
                        streamBacklog.createPublisherIfAbsent().position(value)
                    }
                    SenderPos.SENDER_POSITION_TYPE_ID -> {
                        streamBacklog.createSenderIfAbsent().registrationId(registrationId)
                        streamBacklog.createSenderIfAbsent().position(value)
                    }
                    SenderLimit.SENDER_LIMIT_TYPE_ID -> {
                        streamBacklog.createSenderIfAbsent().registrationId(registrationId)
                        streamBacklog.createSenderIfAbsent().limit(value)
                    }
                    ReceiverHwm.RECEIVER_HWM_TYPE_ID -> {
                        streamBacklog.createReceiverIfAbsent().registrationId(registrationId)
                        streamBacklog.createReceiverIfAbsent().highWaterMark(value)
                    }
                    ReceiverPos.RECEIVER_POS_TYPE_ID -> {
                        streamBacklog.createReceiverIfAbsent().registrationId(registrationId)
                        streamBacklog.createReceiverIfAbsent().position(value)
                    }
                    SubscriberPos.SUBSCRIBER_POSITION_TYPE_ID -> streamBacklog.subscriberBacklogs()[registrationId] = Subscriber(value)
                }
            }
        }
        return streams
    }

    /**
     * Print a snapshot of the stream backlog with some explanation to a [StringBuilder].
     *
     *
     * Each stream will be printed in its own section.
     *
     * @return the string builder to which the stream backlog is written to.
     */
    fun output(snapshot: Map<StreamCompositeKey, StreamBacklog> = snapshot()): StringBuilder {
        val builder = StringBuilder()

        for ((key, streamBacklog) in snapshot) {
            builder.setLength(0)

            builder
                .append("sessionId=").append(key.sessionId())
                .append(" streamId=").append(key.streamId())
                .append(" channel=").append(key.channel())
                .append(" : ")

            if (streamBacklog.publisher() != null) {
                val publisher = streamBacklog.publisher()!!

                builder
                    .append("\n┌─for publisher ")
                    .append(publisher.registrationId())
                    .append(" the last sampled position is ")
                    .append(publisher.position())
                    .append(" (~").append(publisher.remainingWindow())
                    .append(" bytes before back-pressure)")

                val sender = streamBacklog.sender()
                if (sender != null) {
                    val senderBacklog = sender.backlog(publisher.position())

                    builder.append("\n└─sender ").append(sender.registrationId())
                    if (senderBacklog >= 0) {
                        builder.append(" has to send ").append(senderBacklog).append(" bytes")
                    } else {
                        builder.append(" is at position ").append(sender.position())
                    }
                    builder
                        .append(" (").append(sender.window())
                        .append(" bytes remaining in the sender window)")
                } else {
                    builder.append("\n└─no sender")
                }
            }
            if (streamBacklog.receiver() != null) {
                val receiver = streamBacklog.receiver()!!

                builder
                    .append("\n┌─receiver ").append(receiver.registrationId())
                    .append(" is at position ").append(receiver.position())

                val subscriberIterator: Iterator<Map.Entry<Long, Subscriber>> = streamBacklog.subscriberBacklogs().entries.iterator()
                while (subscriberIterator.hasNext()) {
                    val (key1, value) = subscriberIterator.next()
                    builder
                        .append(if (subscriberIterator.hasNext()) "\n├" else "\n└")
                        .append("─subscriber ")
                        .append(key1)
                        .append(" has ")
                        .append(value.backlog(receiver.highWaterMark()))
                        .append(" backlog bytes")
                }
            }
            builder.append('\n')
        }
        return builder
    }

    /**
     * Composite key which identifies an Aeron stream of messages.
     */
    class StreamCompositeKey(sessionId: Int, streamId: Int, channel: String) {
        private val sessionId: Int
        private val streamId: Int
        private val channel: String

        /**
         * Construct a key to represent a unique stream.
         *
         * @param sessionId for the stream.
         * @param streamId for the stream within a channel.
         * @param channel as a URI.
         */
        init {
            Objects.requireNonNull(channel, "channel cannot be null")
            this.sessionId = sessionId
            this.streamId = streamId
            this.channel = channel
        }

        /**
         * The session id of the stream.
         *
         * @return session id of the stream.
         */
        fun sessionId(): Int {
            return sessionId
        }

        /**
         * The stream id within a channel.
         *
         * @return stream id within a channel.
         */
        fun streamId(): Int {
            return streamId
        }

        /**
         * The channel as a URI.
         *
         * @return channel as a URI.
         */
        fun channel(): String {
            return channel
        }

        /**
         * {@inheritDoc}
         */
        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other !is StreamCompositeKey) {
                return false
            }
            val that = other
            return sessionId == that.sessionId && streamId == that.streamId && channel == that.channel
        }

        /**
         * {@inheritDoc}
         */
        override fun hashCode(): Int {
            var result = sessionId
            result = 31 * result + streamId
            result = 31 * result + channel.hashCode()
            return result
        }

        /**
         * {@inheritDoc}
         */
        override fun toString(): String {
            return "StreamCompositeKey{sessionId=$sessionId, streamId=$streamId, channel='$channel'}"
        }
    }

    /**
     * Represents the backlog information for a particular stream of messages.
     */
    class StreamBacklog {
        private var publisher: Publisher? = null
        private var sender: Sender? = null
        private var receiver: Receiver? = null
        private val subscriberBacklogs: SortedMap<Long, Subscriber> = TreeMap()
        fun publisher(): Publisher? {
            return publisher
        }

        fun sender(): Sender? {
            return sender
        }

        fun receiver(): Receiver? {
            return receiver
        }

        fun subscriberBacklogs(): MutableMap<Long, Subscriber> {
            return subscriberBacklogs
        }

        fun createPublisherIfAbsent(): Publisher {
            return if (publisher == null) Publisher().also { publisher = it } else publisher!!
        }

        fun createSenderIfAbsent(): Sender {
            return if (sender == null) Sender().also { sender = it } else sender!!
        }

        fun createReceiverIfAbsent(): Receiver {
            return if (receiver == null) Receiver().also { receiver = it } else receiver!!
        }
    }

    open class AeronEntity {
        private var registrationId: Long = 0
        fun registrationId(): Long {
            return registrationId
        }

        fun registrationId(registrationId: Long) {
            this.registrationId = registrationId
        }
    }

    class Publisher : AeronEntity() {
        private var limit: Long = 0
        private var position: Long = 0
        fun limit(limit: Long) {
            this.limit = limit
        }

        fun position(position: Long) {
            this.position = position
        }

        fun position(): Long {
            return position
        }

        fun remainingWindow(): Long {
            return limit - position
        }
    }

    class Sender : AeronEntity() {
        private var position: Long = 0
        private var limit: Long = 0
        fun position(publisherPosition: Long) {
            position = publisherPosition
        }

        fun position(): Long {
            return position
        }

        fun limit(limit: Long) {
            this.limit = limit
        }

        fun backlog(publisherPosition: Long): Long {
            return publisherPosition - position
        }

        fun window(): Long {
            return limit - position
        }
    }

    class Receiver : AeronEntity() {
        private var highWaterMark: Long = 0
        private var position: Long = 0
        fun highWaterMark(highWaterMark: Long) {
            this.highWaterMark = highWaterMark
        }

        fun highWaterMark(): Long {
            return highWaterMark
        }

        fun position(position: Long) {
            this.position = position
        }

        fun position(): Long {
            return position
        }
    }

    class Subscriber(private val position: Long) {
        fun backlog(receiverHwm: Long): Long {
            return receiverHwm - position
        }
    }
}
