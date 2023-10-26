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

package dorkbox.network.connection.session

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

internal open class SessionManagerFull<CONNECTION: SessionConnection>(
    config: Configuration,
    listenerManager: ListenerManager<CONNECTION>,
    val aeronDriver: AeronDriver,
    sessionTimeout: Long
): SessionManager<CONNECTION> {

    companion object {
        private val logger = LoggerFactory.getLogger(SessionManagerFull::class.java.simpleName)
    }


    private val sessions = LockFreeHashMap<ByteArrayWrapper, Session<CONNECTION>>()


    private val expiringSessions: ExpiringMap<ByteArrayWrapper, Session<CONNECTION>>

    init {
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
            .expirationListener<ByteArrayWrapper, Session<CONNECTION>> { publicKeyWrapped, sessionConnection ->
                // this blocks until it fully runs (which is ok. this is fast)
                logger.debug("Connection session expired for: ${publicKeyWrapped.bytes.toHexString()}")

                // this SESSION has expired, so we should call the onDisconnect for the underlying connection, in order to clean it up.
                listenerManager.notifyDisconnect(sessionConnection.connection)
            }
            .build()
    }

    override fun enabled(): Boolean {
        return true
    }

    /**
     * this must be called when a new connection is created
     *
     * @return true if this is a new session, false if it is an existing session
     */
    override fun onNewConnection(connection: Connection) {
        require(connection is SessionConnection) { "The new connection does not inherit a SessionConnection, unable to continue. " }

        val publicKeyWrapped = ByteArrayWrapper.wrap(connection.uuid)

        synchronized(sessions) {
            // always check if we are expiring first...
            val expiring = expiringSessions.remove(publicKeyWrapped)
            if (expiring != null) {
                // we must always set this session value!!
                connection.session = expiring
            } else {
                val existing = sessions[publicKeyWrapped]
                if (existing != null) {
                    // we must always set this session value!!
                    connection.session = existing
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val newSession = (connection.endPoint as SessionEndpoint<CONNECTION>).newSession(connection as CONNECTION)

                    // we must always set this when the connection is created, and it must be inside the sync block!
                    connection.session = newSession

                    sessions[publicKeyWrapped] = newSession
                }
            }
        }
    }


    /**
     * this must be called when a new connection is created AND when the internal `reconnect` occurs (as a result of a network error)
     *
     * @return true if this is a new session, false if it is an existing session
     */
    @Suppress("UNCHECKED_CAST")
    override fun onInit(connection: Connection): Boolean {
        // we know this will always be the case, because if this specific method can be called, then it will be a sessionConnection
        connection as SessionConnection

        val session: Session<CONNECTION> = connection.session as Session<CONNECTION>
        session.restore(connection as CONNECTION)

        // the FIRST time this method is called, it will be true. EVERY SUBSEQUENT TIME, it will be false
        return session.isNewSession
    }


    /**
     * Always called when a connection is disconnected from the network
     */
    override fun onDisconnect(connection: CONNECTION) {
        try {
            val publicKeyWrapped = ByteArrayWrapper.wrap(connection.uuid)

            val session = synchronized(sessions) {
                val sess = sessions.remove(publicKeyWrapped)
                // we want to expire this session after XYZ time
                expiringSessions[publicKeyWrapped] = sess
                sess
            }


            session!!.save(connection)
        }
        catch (e: Exception) {
            logger.error("Unable to run save data for the session!", e)
        }
    }
}
