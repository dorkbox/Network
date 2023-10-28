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

package dorkbox.network.connection.buffer

import dorkbox.bytes.ByteArrayWrapper
import dorkbox.collections.LockFreeHashMap
import dorkbox.hex.toHexString
import dorkbox.network.Configuration
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.connection.Connection
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.ListenerManager
import dorkbox.util.Sys
import net.jodah.expiringmap.ExpirationPolicy
import net.jodah.expiringmap.ExpiringMap
import org.slf4j.LoggerFactory
import java.util.concurrent.*

internal open class BufferManager<CONNECTION: Connection>(
    config: Configuration,
    listenerManager: ListenerManager<CONNECTION>,
    aeronDriver: AeronDriver,
    sessionTimeout: Long
) {

    companion object {
        private val logger = LoggerFactory.getLogger(BufferManager::class.java.simpleName)
    }

    private val sessions = LockFreeHashMap<ByteArrayWrapper, BufferedSession>()
    private val expiringSessions: ExpiringMap<ByteArrayWrapper, BufferedSession>

    init {
        require(sessionTimeout >= 60) { "The buffered connection timeout 'bufferedConnectionTimeoutSeconds' must be greater than 60 seconds!" }

        // ignore 0
        val check = TimeUnit.SECONDS.toNanos(sessionTimeout)
        val lingerNs = aeronDriver.lingerNs()
        val required = TimeUnit.SECONDS.toNanos(config.connectionCloseTimeoutInSeconds.toLong())
        require(check == 0L || check > required + lingerNs) {
            "The session timeout (${Sys.getTimePretty(check)}) must be longer than the connection close timeout (${Sys.getTimePretty(required)}) + the aeron driver linger timeout (${Sys.getTimePretty(lingerNs)})!"
        }

        // connections are extremely difficult to diagnose when the connection timeout is short
        val timeUnit = if (EndPoint.DEBUG_CONNECTIONS) { TimeUnit.HOURS } else { TimeUnit.SECONDS }

        expiringSessions = ExpiringMap.builder()
            .expiration(sessionTimeout, timeUnit)
            .expirationPolicy(ExpirationPolicy.CREATED)
            .expirationListener<ByteArrayWrapper, BufferedSession> { publicKeyWrapped, sessionConnection ->
                // this blocks until it fully runs (which is ok. this is fast)
                logger.debug("Connection session expired for: ${publicKeyWrapped.bytes.toHexString()}")

                // this SESSION has expired, so we should call the onDisconnect for the underlying connection, in order to clean it up.
                listenerManager.notifyDisconnect(sessionConnection.connection)
            }
            .build()
    }

    /**
     * this must be called when a new connection is created
     *
     * @return true if this is a new session, false if it is an existing session
     */
    fun onConnect(connection: Connection): BufferedSession {
        val publicKeyWrapped = ByteArrayWrapper.wrap(connection.uuid)

        return synchronized(sessions) {
            // always check if we are expiring first...
            val expiring = expiringSessions.remove(publicKeyWrapped)
            if (expiring != null) {
                expiring.connection = connection
                expiring
            } else {
                val existing = sessions[publicKeyWrapped]
                if (existing != null) {
                    // we must always set this session value!!
                    existing.connection = connection
                    existing
                } else {
                    val newSession = BufferedSession(connection)
                    sessions[publicKeyWrapped] = newSession

                    // we must always set this when the connection is created, and it must be inside the sync block!
                    newSession
                }
            }
        }
    }

    /**
     * Always called when a connection is disconnected from the network
     */
    fun onDisconnect(connection: Connection) {
        try {
            val publicKeyWrapped = ByteArrayWrapper.wrap(connection.uuid)

            synchronized(sessions) {
                val sess = sessions.remove(publicKeyWrapped)
                // we want to expire this session after XYZ time
                expiringSessions[publicKeyWrapped] = sess
            }
        }
        catch (e: Exception) {
            logger.error("Unable to run session expire logic!", e)
        }
    }


    fun close() {
        synchronized(sessions) {
            sessions.clear()
            expiringSessions.clear()
        }
    }
}
