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
import dorkbox.network.connection.EndPoint
import dorkbox.util.Sys
import net.jodah.expiringmap.ExpirationPolicy
import net.jodah.expiringmap.ExpiringMap
import org.slf4j.LoggerFactory
import java.util.concurrent.*

internal open class SessionManager<CONNECTION: SessionConnection>(config: Configuration, val aeronDriver: AeronDriver, sessionTimeout: Long) {

    companion object {
        private val logger = LoggerFactory.getLogger(SessionManager::class.java.simpleName)

        class NoOp<CONNECTION: SessionConnection>(config: Configuration, aeronDriver: AeronDriver):
            SessionManager<CONNECTION>(config, aeronDriver, 0L) {

            override fun enabled(): Boolean {
                return false
            }

            override fun onInit(connection: CONNECTION): Boolean {
                // do nothing
                return true
            }

            override fun onDisconnect(connection: CONNECTION) {
                // do nothing
            }
        }
    }


    private val sessions = LockFreeHashMap<ByteArrayWrapper, Session<CONNECTION>>()


    // note: the expire time here is a 4x longer than the expire time in the client, this way we can adjust for network lag or quick reconnects
    private val expiringSessions = ExpiringMap.builder()
        .apply {
            // connections are extremely difficult to diagnose when the connection timeout is short
            val timeUnit = if (EndPoint.DEBUG_CONNECTIONS) { TimeUnit.HOURS } else { TimeUnit.NANOSECONDS }

            // we MUST include the publication linger timeout, otherwise we might encounter problems that are NOT REALLY problems
            this.expiration(TimeUnit.SECONDS.toNanos(config.connectionCloseTimeoutInSeconds.toLong() * 2) + aeronDriver.lingerNs(), timeUnit)
        }
        .expirationPolicy(ExpirationPolicy.CREATED)
        .expirationListener<ByteArrayWrapper, Session<CONNECTION>> { publicKeyWrapped, _ ->
            // this blocks until it fully runs (which is ok. this is fast)
            logger.debug("Connection session for ${publicKeyWrapped.bytes.toHexString()} expired.")

        }
        .build<ByteArrayWrapper, Session<CONNECTION>>()


    init {
        // ignore 0
        val check = TimeUnit.SECONDS.toNanos(sessionTimeout)
        val lingerNs = aeronDriver.lingerNs()
        val required = TimeUnit.SECONDS.toNanos(config.connectionCloseTimeoutInSeconds.toLong())
        require(check == 0L || check > required + lingerNs) {
            "The session timeout (${Sys.getTimePretty(check)}) must be longer than the connection close timeout (${Sys.getTimePretty(required)}) + the aeron driver linger timeout (${Sys.getTimePretty(lingerNs)})!"
        }
    }

    open fun enabled(): Boolean {
        return true
    }


    /**
     * this must be called when a new connection is created AND when the internal `reconnect` occurs (as a result of a network error)
     *
     * @return true if this is a new session, false if it is an existing session
     */
    open fun onInit(connection: CONNECTION): Boolean {
        val publicKeyWrapped = ByteArrayWrapper.wrap(connection.uuid)

        var isNewSession = false
        val session = synchronized(sessions) {
            // always check if we are expiring first...
            val expiring = expiringSessions.remove(publicKeyWrapped)
            if (expiring != null) {
                expiring
            } else {
                val existing = sessions[publicKeyWrapped]
                if (existing != null) {
                    existing
                } else {
                    isNewSession = true

                    @Suppress("UNCHECKED_CAST")
                    val newSession: Session<CONNECTION> = if (connection.endPoint.isServer()) {
                        (connection.endPoint as SessionServer).newSession() as Session<CONNECTION>
                    } else {
                        (connection.endPoint as SessionClient).newSession() as Session<CONNECTION>
                    }

                    sessions[publicKeyWrapped] = newSession
                    newSession
                }
            }
        }

        connection.session = session
        session.restore(connection)

        return isNewSession
    }


    /**
     * Always called when a connection is disconnected from the network
     */
    open fun onDisconnect(connection: CONNECTION) {
        val publicKeyWrapped = ByteArrayWrapper.wrap(connection.uuid)

        val session = synchronized(sessions) {
            val session = sessions.remove(publicKeyWrapped)
            // we want to expire this session after XYZ time
            expiringSessions[publicKeyWrapped] = session
            session
        }


        session!!.save(connection)
    }
}
