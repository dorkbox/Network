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
@file:Suppress("DuplicatedCode")

package dorkbox.network.aeron

import dorkbox.network.exceptions.ClientTimedOutException
import io.aeron.Aeron
import io.aeron.Publication
import io.aeron.Subscription
import kotlinx.coroutines.delay
import mu.KLogger

abstract class MediaDriverConnection(val publicationPort: Int, val subscriptionPort: Int,
                                     val streamId: Int, val sessionId: Int,
                                     val connectionTimeoutMS: Long, val isReliable: Boolean) : AutoCloseable {

    lateinit var subscription: Subscription
    lateinit var publication: Publication


    suspend fun addSubscriptionWithRetry(aeron: Aeron, uri: String, streamId: Int, logger: KLogger): Subscription {
        // If we start/stop too quickly, we might have the address already in use! Retry a few times.
        var count = 10
        var exception: Exception? = null
        while (count-- > 0) {
            try {
                return aeron.addSubscription(uri, streamId)
            } catch (e: Exception) {
                exception = e
                logger.warn { "Unable to add a publication to Aeron. Retrying $count more times..." }
                delay(5000)
            }
        }

        throw exception!!
    }

    suspend fun addPublicationWithRetry(aeron: Aeron, uri: String, streamId: Int, logger: KLogger): Publication {
        // If we start/stop too quickly, we might have the address already in use! Retry a few times.
        var count = 10
        var exception: Exception? = null
        while (count-- > 0) {
            try {
                return aeron.addPublication(uri, streamId)
            } catch (e: Exception) {
                exception = e
                logger.warn { "Unable to add a publication to Aeron. Retrying $count more times..." }
                delay(5_000)
            }
        }

        throw exception!!
    }

    @Throws(ClientTimedOutException::class)
    abstract suspend fun buildClient(aeron: Aeron, logger: KLogger)
    abstract suspend fun buildServer(aeron: Aeron, logger: KLogger)

    abstract fun clientInfo() : String
    abstract fun serverInfo() : String
}
