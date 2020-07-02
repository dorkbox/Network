/*
 * Copyright 2010 dorkbox, llc
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
package dorkbox.network

import dorkbox.network.aeron.server.ServerException
import dorkbox.network.connection.Connection
import dorkbox.network.connection.ConnectionManagerServer
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.UdpMediaDriverConnection
import dorkbox.network.connection.connectionType.ConnectionProperties
import dorkbox.network.connection.connectionType.ConnectionRule
import dorkbox.network.ipFilter.IpFilterRule
import dorkbox.network.ipFilter.IpFilterRuleType
import io.aeron.FragmentAssembler
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
import kotlinx.coroutines.launch
import org.agrona.DirectBuffer
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * NOTE: when using "server.publish(A)", this will go to ALL CLIENTS! add this to aeron via "publication.addDestination" so aeron manages it
 *
 *
 * The server can only be accessed in an ASYNC manner. This means that the server can only be used in RESPONSE to events. If you access the
 * server OUTSIDE of events, you will get inaccurate information from the server (such as getConnections())
 *
 *
 * To put it bluntly, ONLY have the server do work inside of a listener!
 */
open class Server<C : Connection>(config: ServerConfiguration = ServerConfiguration()) : EndPoint<C>(config) {
    companion object {
        /**
         * Gets the version number.
         */
        const val version = "4.1"

        /**
         * Checks to see if a server (using the specified configuration) is running.
         *
         * @return true if the configuration matches and can connect (but not verify) to the TCP control socket.
         */
        fun isRunning(configuration: ServerConfiguration): Boolean {
            val server = Server<Connection>(configuration)

            val running = server.isRunning()
            server.close()

            return running
        }
    }


    /**
     * @return true if this server has successfully bound to an IP address and is running
     */
    @Volatile
    private var bindAlreadyCalled = false


    override val connectionManager = ConnectionManagerServer<C>(logger, config)

    /**
     * Maintains a thread-safe collection of rules to allow/deny connectivity to this server.
     */
    protected val ipFilterRules = CopyOnWriteArrayList<IpFilterRule>()

    /**
     * Maintains a thread-safe collection of rules used to define the connection type with this server.
     */
    protected val connectionRules = CopyOnWriteArrayList<ConnectionRule>()

    init {
        // have to do some basic validation of our configuration
        config.listenIpAddress = config.listenIpAddress.toLowerCase()

        // localhost/loopback IP might not always be 127.0.0.1 or ::1
        when (config.listenIpAddress) {
            "loopback", "localhost", "lo" -> config.listenIpAddress = NetUtil.LOCALHOST.hostAddress
            else -> when {
                config.listenIpAddress.startsWith("127.") -> config.listenIpAddress = NetUtil.LOCALHOST.hostAddress
                config.listenIpAddress.startsWith("::1") -> config.listenIpAddress = NetUtil.LOCALHOST6.hostAddress
                else -> config.listenIpAddress = "0.0.0.0" // we set this to "0.0.0.0" so that it is clear that we are trying to bind to that address.
            }
        }

        // if we are IPv6, the IP must be in '[]'
        if (config.listenIpAddress.count { it == ':' } > 1 &&
            config.listenIpAddress.count { it == '[' } < 1 &&
            config.listenIpAddress.count { it == ']' } < 1) {

            config.listenIpAddress = """[${config.listenIpAddress}]"""
        }

        if (config.listenIpAddress == "0.0.0.0") {
            // fixup windows!
            config.listenIpAddress = NetworkUtil.WILDCARD_IPV4
        }


        if (config.publicationPort <= 0) { throw ServerException("configuration port must be > 0") }
        if (config.publicationPort >= 65535) { throw ServerException("configuration port must be < 65535") }

        if (config.subscriptionPort <= 0) { throw ServerException("configuration controlPort must be > 0") }
        if (config.subscriptionPort >= 65535) { throw ServerException("configuration controlPort must be < 65535") }

        if (config.networkMtuSize <= 0) { throw ServerException("configuration networkMtuSize must be > 0") }
        if (config.networkMtuSize >= 9 * 1024) { throw ServerException("configuration networkMtuSize must be < ${9 * 1024}") }

        closables.add(connectionManager)
    }

    /**
     * Binds the server to AERON configuration
     *
     * @param blockUntilTerminate if true, will BLOCK until the server [close] method is called, and if you want to continue running code
     * after this pass in false
     */
    @JvmOverloads
    fun bind(blockUntilTerminate: Boolean = true) {
        if (bindAlreadyCalled) {
            logger.error("Unable to bind when the server is already running!")
            return
        }

        bindAlreadyCalled = true

        config as ServerConfiguration

        // setup the "HANDSHAKE" ports, for initial clients to connect.
        // The is how clients then get the new ports to connect to + other configuration options

        val handshakeDriver = UdpMediaDriverConnection(
                config.listenIpAddress, config.subscriptionPort, config.publicationPort,
                UDP_HANDSHAKE_STREAM_ID, RESERVED_SESSION_ID_INVALID)

        handshakeDriver.buildServer(aeron)

        val handshakePublication = handshakeDriver.publication
        val handshakeSubscription = handshakeDriver.subscription

        logger.debug(handshakeDriver.serverInfo())
        logger.debug("Server listening for incomming clients on ${handshakePublication.localSocketAddresses()}")

        /**
         * Note:
         * Reassembly has been shown to be minimal impact to latency. But not totally negligible. If the lowest latency is
         * desired, then limiting message sizes to MTU size is a good practice.
         *
         * There is a maximum length allowed for messages which is the min of 1/8th a term length or 16MB.
         * Messages larger than this should chunked using an application level chunking protocol. Chunking has better recovery
         * properties from failure and streams with mechanical sympathy.
         */
        val initialConnectionHandler = FragmentAssembler(FragmentHandler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
            actionDispatch.launch {
                connectionManager.receiveHandshakeMessageServer(handshakePublication, buffer, offset, length, header, this@Server)
            }
        })

        actionDispatch.launch {
            val pollIdleStrategy = config.pollIdleStrategy

            try {
                while (!isShutdown()) {
                    // this checks to see if there are NEW clients
                    var pollCount = handshakeSubscription.poll(initialConnectionHandler, 100)

                    // this manages existing clients (for cleanup + connection polling)
                    pollCount += connectionManager.poll()

                    // 0 means we idle. >0 means reset and don't idle (because there are likely more poll events)
                    pollIdleStrategy.idle(pollCount)
                }
            } finally {
                handshakePublication.close()
                handshakeSubscription.close()
            }
        }


        // we now BLOCK until the stop method is called.
        if (blockUntilTerminate) {
            waitForShutdown();
        }
    }


    /**
     * Adds an IP+subnet rule that defines if that IP+subnet is allowed/denied connectivity to this server.
     *
     *
     * If there are any IP+subnet added to this list - then ONLY those are permitted (all else are denied)
     *
     *
     * If there is nothing added to this list - then ALL are permitted
     */
    fun addIpFilterRule(vararg rules: IpFilterRule?) {
        ipFilterRules.addAll(Arrays.asList(*rules))
    }

    /**
     * Adds an IP+subnet rule that defines what type of connection this IP+subnet should have.
     * - NOTHING : Nothing happens to the in/out bytes
     * - COMPRESS: The in/out bytes are compressed with LZ4-fast
     * - COMPRESS_AND_ENCRYPT: The in/out bytes are compressed (LZ4-fast) THEN encrypted (AES-256-GCM)
     *
     * If no rules are defined, then for LOOPBACK, it will always be `COMPRESS` and for everything else it will always be `COMPRESS_AND_ENCRYPT`.
     *
     * If rules are defined, then everything by default is `COMPRESS_AND_ENCRYPT`.
     *
     * The compression algorithm is LZ4-fast, so there is a small performance impact for a very large gain
     * Compress   :       6.210 micros/op;  629.0 MB/s (output: 55.4%)
     * Uncompress :       0.641 micros/op; 6097.9 MB/s
     */
    fun addConnectionRules(vararg rules: ConnectionRule?) {
        connectionRules.addAll(Arrays.asList(*rules))
    }






    // verify the class ID registration details.
    // the client will send their class registration data. VERIFY IT IS CORRECT!

    // verify the class ID registration details.
    // the client will send their class registration data. VERIFY IT IS CORRECT!
//    var state: dorkbox.network.connection.RegistrationWrapper.STATE = registrationWrapper.verifyClassRegistration(metaChannel, registration)
//    if (state == RegistrationWrapper.STATE.ERROR)
//    {
//        // abort! There was an error
//        shutdown(channel, 0)
//        return
//    } else if (state == RegistrationWrapper.STATE.WAIT)
//    {
//        return
//    }




    /**
     * Checks to seeOnce a server has connected to ANY client, it will always return true until server.close() is called
     *
     * @return true if we are connected, false otherwise.
     */
    override fun isConnected(): Boolean {
        return connectionManager.connectionCount() > 0
    }


    /**
     * Safely sends objects to a destination
     */
    suspend fun send(message: Any) {
        connectionManager.send(message)
    }

    /**
     * When called by a server, NORMALLY listeners are added at the GLOBAL level (meaning, I add one listener,
     * and ALL connections are notified of that listener.
     * <br></br>
     * It is POSSIBLE to add a server-connection 'local' listener (via connection.addListener), meaning that ONLY
     * that listener attached to the connection is notified on that event (ie, admin type listeners)
     *
     * @return a newly created listener manager for the connection
     */
//    fun addListenerManager(connection: C): ConnectionManager<C> {
//        return connectionManager.addListenerManager(connection)
//    }

    /**
     * When called by a server, NORMALLY listeners are added at the GLOBAL level (meaning, I add one listener,
     * and ALL connections are notified of that listener.
     * <br></br>
     * It is POSSIBLE to remove a server-connection 'local' listener (via connection.removeListener), meaning that ONLY
     * that listener attached to the connection is removed
     *
     *
     * This removes the listener manager for that specific connection
     */
//    fun removeListenerManager(connection: C) {
//        connectionManager.removeListenerManager(connection)
//    }

    /**
     * Adds a custom connection to the server.
     *
     * This should only be used in situations where there can be DIFFERENT types of connections (such as a 'web-based' connection) and
     * you want *this* server instance to manage listeners + message dispatch
     *
     * @param connection the connection to add
     */
    fun add(connection: C) {
        connectionManager.addConnection(connection)
    }

    /**
     * Removes a custom connection to the server.
     *
     *
     * This should only be used in situations where there can be DIFFERENT types of connections (such as a 'web-based' connection) and
     * you want *this* server instance to manage listeners + message dispatch
     *
     * @param connection the connection to remove
     */
    fun remove(connection: C) {
        connectionManager.removeConnection(connection)
    }

    // if no rules, then always yes
    // if rules, then default no unless a rule says yes. ACCEPT rules take precedence over REJECT (so if you have both rules, ACCEPT will happen)
    fun acceptRemoteConnection(remoteAddress: InetSocketAddress): Boolean {
        val size = ipFilterRules.size
        if (size == 0) {
            return true
        }
        val address = remoteAddress.address

        // it's possible for a remote address to match MORE than 1 rule.
        var isAllowed = false
        for (i in 0 until size) {
            val rule = ipFilterRules[i] ?: continue
            if (isAllowed) {
                break
            }
            if (rule.matches(remoteAddress)) {
                isAllowed = rule.ruleType() == IpFilterRuleType.ACCEPT
            }
        }
        logger.debug("Validating {}  Connection allowed: {}", address, isAllowed)
        return isAllowed
    }

    /**
     * Checks to see if a server (using the specified configuration) is running.
     *
     * @return true if the server is active and running
     */
    fun isRunning(): Boolean {
        return mediaDriver.context().isDriverActive(10_000, logger::debug)
    }

    override fun close() {
        super.close()
        bindAlreadyCalled = false
    }



    /**
     * Only called by the server!
     *
     * If we are loopback or the client is a specific IP/CIDR address, then we do things differently. The LOOPBACK address will never encrypt or compress the traffic.
     */
    // after the handshake, what sort of connection do we want (NONE, COMPRESS, ENCRYPT+COMPRESS)
    fun getConnectionUpgradeType(remoteAddress: InetSocketAddress): Byte {
        val address = remoteAddress.address
        val size = connectionRules.size

        // if it's unknown, then by default we encrypt the traffic
        var connectionType = ConnectionProperties.COMPRESS_AND_ENCRYPT
        if (size == 0 && address == NetUtil.LOCALHOST) {
            // if nothing is specified, then by default localhost is compression and everything else is encrypted
            connectionType = ConnectionProperties.COMPRESS
        }
        for (i in 0 until size) {
            val rule = connectionRules[i] ?: continue
            if (rule.matches(remoteAddress)) {
                connectionType = rule.ruleType()
                break
            }
        }
        logger.debug("Validating {}  Permitted type is: {}", remoteAddress, connectionType)
        return connectionType.type
    }

    enum class STATE {
        ERROR, WAIT, CONTINUE
    }

//    fun verifyClassRegistration(metaChannel: MetaChannel, registration: Registration): STATE {
//        if (registration.upgradeType == UpgradeType.FRAGMENTED) {
//            val fragment = registration.payload!!
//
//            // this means that the registrations are FRAGMENTED!
//            // max size of ALL fragments is xxx * 127
//            if (metaChannel.fragmentedRegistrationDetails == null) {
//                metaChannel.remainingFragments = fragment[1]
//                metaChannel.fragmentedRegistrationDetails = ByteArray(Serialization.CLASS_REGISTRATION_VALIDATION_FRAGMENT_SIZE * fragment[1])
//            }
//            System.arraycopy(fragment, 2, metaChannel.fragmentedRegistrationDetails, fragment[0] * Serialization.CLASS_REGISTRATION_VALIDATION_FRAGMENT_SIZE, fragment.size - 2)
//
//            metaChannel.remainingFragments--
//
//            if (fragment[0] + 1 == fragment[1].toInt()) {
//                // this is the last fragment in the in byte array (but NOT necessarily the last fragment to arrive)
//                val correctSize = Serialization.CLASS_REGISTRATION_VALIDATION_FRAGMENT_SIZE * (fragment[1] - 1) + (fragment.size - 2)
//                val correctlySized = ByteArray(correctSize)
//                System.arraycopy(metaChannel.fragmentedRegistrationDetails, 0, correctlySized, 0, correctSize)
//                metaChannel.fragmentedRegistrationDetails = correctlySized
//            }
//            if (metaChannel.remainingFragments.toInt() == 0) {
//                // there are no more fragments available
//                val details = metaChannel.fragmentedRegistrationDetails
//                metaChannel.fragmentedRegistrationDetails = null
//                if (!serialization.verifyKryoRegistration(details)) {
//                    // error
//                    return STATE.ERROR
//                }
//            } else {
//                // wait for more fragments
//                return STATE.WAIT
//            }
//        } else {
//            if (!serialization.verifyKryoRegistration(registration.payload!!)) {
//                return STATE.ERROR
//            }
//        }
//        return STATE.CONTINUE
//    }
}
