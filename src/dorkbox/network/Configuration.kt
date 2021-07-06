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
package dorkbox.network

import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.CoroutineBackoffIdleStrategy
import dorkbox.network.aeron.CoroutineIdleStrategy
import dorkbox.network.aeron.CoroutineSleepingMillisIdleStrategy
import dorkbox.network.connection.Connection
import dorkbox.network.serialization.Serialization
import dorkbox.network.storage.StorageType
import dorkbox.network.storage.types.PropertyStore
import dorkbox.os.OS
import io.aeron.driver.Configuration
import io.aeron.driver.ThreadingMode
import mu.KLogger
import org.agrona.SystemUtil
import java.io.File
import java.util.concurrent.TimeUnit

class ServerConfiguration : dorkbox.network.Configuration() {
    companion object {
        /**
         * Gets the version number.
         */
        const val version = "5.2"
    }

    /**
     * The address for the server to listen on. "*" will accept connections from all interfaces, otherwise specify
     * the hostname (or IP) to bind to.
     */
    var listenIpAddress = "*"
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * The maximum number of clients allowed for a server. IPC is unlimited
     */
    var maxClientCount = 0
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * The maximum number of client connection allowed per IP address. IPC is unlimited
     */
    var maxConnectionsPerIpAddress = 0
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * The IPC Publication ID is used to define what ID the server will send data on. The client IPC subscription ID must match this value.
     */
    var ipcPublicationId = AeronDriver.IPC_HANDSHAKE_STREAM_ID_PUB
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * The IPC Subscription ID is used to define what ID the server will receive data on. The client IPC publication ID must match this value.
     */
    var ipcSubscriptionId = AeronDriver.IPC_HANDSHAKE_STREAM_ID_SUB
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * Allows the user to change how endpoint settings and public key information are saved.
     *
     * For example, a custom database instead of the default, in-memory storage.
     *
     * Included types are:
     *  * ChronicleMapStore.type(file)  -- high performance, but non-transactional and not recommended to be shared
     *  * LmdbStore.type(file)          -- high performance, ACID, and can be shared
     *  * MemoryStore.type()            -- v. high performance, but not persistent
     *  * PropertyStore.type(file)      -- slow performance on write, but can easily be edited by user (similar to how openSSH server key info is)
     */
    override var settingsStore: StorageType = PropertyStore.type("settings-server.db")
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }


    /**
     * Validates the current configuration
     */
    @Suppress("DuplicatedCode")
    override fun validate() {
        // have to do some basic validation of our configuration
        if (listenIpAddress != listenIpAddress.lowercase()) {
            // only do this once!
            listenIpAddress = listenIpAddress.lowercase()
        }
        if (maxConnectionsPerIpAddress == 0) { maxConnectionsPerIpAddress = maxClientCount }


        require(listenIpAddress.isNotBlank()) { "Blank listen IP address, cannot continue." }

        // can't disable everything!
        require(enableIpc || enableIPv4 || enableIPv6) { "At least one of IPC/IPv4/IPv6 must be enabled!" }

        if (enableIpc) {
            require(!aeronDirectoryForceUnique) { "IPC enabled and forcing a unique Aeron directory are incompatible (IPC requires shared Aeron directories)!" }
        } else {
            if (enableIPv4 && !enableIPv6) {
                require(IPv4.isAvailable) { "IPC/IPv6 are disabled and IPv4 is enabled, but there is no IPv4 interface available!" }
            }

            if (!enableIPv4 && enableIPv6) {
                require(IPv6.isAvailable) { "IPC/IPv4 are disabled and IPv6 is enabled, but there is no IPv6 interface available!" }
            }
        }


        require(publicationPort > 0) { "configuration port must be > 0" }
        require(publicationPort < 65535) { "configuration port must be < 65535" }

        require(subscriptionPort > 0) { "configuration controlPort must be > 0" }
        require(subscriptionPort < 65535) { "configuration controlPort must be < 65535" }

        require(networkMtuSize > 0) { "configuration networkMtuSize must be > 0" }
        require(networkMtuSize < 9 * 1024) { "configuration networkMtuSize must be < ${9 * 1024}" }

    }
}

open class Configuration {
    companion object {
        internal const val errorMessage = "Cannot set a property after the configuration context has been created!"
    }

    /**
     * Enables the ability to use the IPv4 network stack.
     */
    var enableIPv4 = true
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * Enables the ability to use the IPv6 network stack.
     */
    var enableIPv6 = true
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * Enables the ability use IPC (Inter Process Communication). If a "loopback" is specified, and this is 'true', then
     * IPC will be used instead - if possible.  IPC is about 4x faster than UDP in loopback situations.
     *
     * Aeron must be running in the same location for the client/server in order for this to work
     */
    var enableIpc = true
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * When connecting to a remote client/server, should connections be allowed if the remote machine signature has changed?
     *
     * Setting this to false is not recommended as it is a security risk
     */
    var enableRemoteSignatureValidation: Boolean = true
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * Specify the UDP port to use. This port is used to establish client-server connections, and is from the
     * perspective of the server
     *
     * This means that server-pub -> {{network}} -> client-sub
     *
     * Must be greater than 0
     */
    var publicationPort: Int = 0
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * Specify the UDP MDC subscription port to use. This port is used to establish client-server connections, and is from the
     * perspective of the server.
     *
     * This means that client-pub -> {{network}} -> server-sub
     *
     * Must be greater than 0
     */
    var subscriptionPort: Int = 0
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * How long a connection must be disconnected before we cleanup the memory associated with it
     */
    var connectionCloseTimeoutInSeconds: Int = 10
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * How often to check if the underlying aeron publication/subscription is connected or not.
     *
     * Aeron Publications and Subscriptions are, and can be, constantly in flux (because of UDP!).
     *
     * Too low and it's wasting CPU cycles, too high and there will be some lag when detecting if a connection has been disconnected.
     */
    var connectionCheckIntervalInMS = TimeUnit.MILLISECONDS.toNanos(200)
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }


    /**
     * How long a connection must be disconnected (via Aeron) before we actually consider it disconnected.
     *
     * Aeron Publications and Subscriptions are, and can be, constantly in flux (because of UDP!).
     *
     * Too low and it's likely to get false-positives, too high and there will be some lag when detecting if a connection has been disconnected.
     */
    var connectionExpirationTimoutInMS = TimeUnit.SECONDS.toNanos(2)
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }


    /**
     * Allows the user to change how endpoint settings and public key information are saved.
     *
     * For example, a custom database instead of the default, in-memory storage.
     *
     * Included types are:
     *  * ChronicleMapStore.type(file)  -- high performance, but non-transactional and not recommended to be shared
     *  * LmdbStore.type(file)          -- high performance, ACID, and can be shared
     *  * MemoryStore.type()            -- v. high performance, but not persistent
     *  * PropertyStore.type(file)      -- slow performance on write, but can easily be edited by user (similar to how openSSH server key info is)
     *
     *  Note: This field is overridden for server configurations, so that the file used is different for client/server
     */
    open var settingsStore: StorageType = PropertyStore.type("settings-client.db")
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * Specify the serialization manager to use.
     */
    var serialization: Serialization<Connection> = Serialization()
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * The idle strategy used when polling the Media Driver for new messages. BackOffIdleStrategy is the DEFAULT.
     *
     * There are a couple strategies of importance to understand.
     *  - BusySpinIdleStrategy uses a busy spin as an idle and will eat up CPU by default.
     *  - BackOffIdleStrategy uses a backoff strategy of spinning, yielding, and parking to be kinder to the CPU, but to be less
     *          responsive to activity when idle for a little while.
     *
     * The main difference in strategies is how responsive to changes should the idler be when idle for a little bit of time and
     * how much CPU should be consumed when no work is being done. There is an inherent tradeoff to consider.
     */
    var pollIdleStrategy: CoroutineIdleStrategy = CoroutineBackoffIdleStrategy(maxSpins = 100, maxYields = 10, minParkPeriodMs = 1, maxParkPeriodMs = 100)
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * The idle strategy used when polling the Media Driver for new messages. BackOffIdleStrategy is the DEFAULT.
     *
     * There are a couple strategies of importance to understand.
     *  - BusySpinIdleStrategy uses a busy spin as an idle and will eat up CPU by default.
     *  - BackOffIdleStrategy uses a backoff strategy of spinning, yielding, and parking to be kinder to the CPU, but to be less
     *          responsive to activity when idle for a little while.
     *
     * The main difference in strategies is how responsive to changes should the idler be when idle for a little bit of time and
     * how much CPU should be consumed when no work is being done. There is an inherent tradeoff to consider.
     */
    var sendIdleStrategy: CoroutineIdleStrategy = CoroutineSleepingMillisIdleStrategy(sleepPeriodMs = 100)
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * ## A Media Driver, whether being run embedded or not, needs 1-3 threads to perform its operation.
     *
     *
     * There are three main Agents in the driver:
     * - Conductor: Responsible for reacting to client requests and house keeping duties as well as detecting loss, sending NAKs,
     * rotating buffers, etc.
     * - Sender: Responsible for shovelling messages from publishers to the network.
     * - Receiver: Responsible for shovelling messages from the network to subscribers.
     *
     *
     * This value can be one of:
     * - INVOKER: No threads. The client is responsible for using the MediaDriver.Context.driverAgentInvoker() to invoke the duty
     * cycle directly.
     * - SHARED: All Agents share a single thread. 1 thread in total.
     * - SHARED_NETWORK: Sender and Receiver shares a thread, conductor has its own thread. 2 threads in total.
     * - DEDICATED: The default and dedicates one thread per Agent. 3 threads in total.
     *
     *
     * For performance, it is recommended to use DEDICATED as long as the number of busy threads is less than or equal to the number of
     * spare cores on the machine. If there are not enough cores to dedicate, then it is recommended to consider sharing some with
     * SHARED_NETWORK or SHARED. INVOKER can be used for low resource environments while the application using Aeron can invoke the
     * media driver to carry out its duty cycle on a regular interval.
     */
    var threadingMode = ThreadingMode.SHARED
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * Aeron location for the Media Driver. The default location is a TEMP dir.
     */
    var aeronDirectory: File? = null
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * Should we force the Aeron location to be unique for every instance? This is mutually exclusive with IPC.
     */
    var aeronDirectoryForceUnique = false
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * The Aeron MTU value impacts a lot of things.
     *
     *
     * The default MTU is set to a value that is a good trade-off. However, it is suboptimal for some use cases involving very large
     * (> 4KB) messages and for maximizing throughput above everything else. Various checks during publication and subscription/connection
     * setup are done to verify a decent relationship with MTU.
     *
     *
     * However, it is good to understand these relationships.
     *
     *
     * The MTU on the Media Driver controls the length of the MTU of data frames. This value is communicated to the Aeron clients during
     * registration. So, applications do not have to concern themselves with the MTU value used by the Media Driver and use the same value.
     *
     *
     * An MTU value over the interface MTU will cause IP to fragment the datagram. This may increase the likelihood of loss under several
     * circumstances. If increasing the MTU over the interface MTU, consider various ways to increase the interface MTU first in preparation.
     *
     *
     * The MTU value indicates the largest message that Aeron will send as a single data frame.
     *
     *
     * MTU length also has implications for socket buffer sizing.
     *
     *
     * Default value is 1408 for internet; for a LAN, 9k is possible with jumbo frames (if the routers/interfaces support it)
     */
    var networkMtuSize = Configuration.MTU_LENGTH_DEFAULT
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * Default initial window length for flow control sender to receiver purposes. This assumes a system free of pauses.
     *
     * Length of Initial Window:
     *
     * RTT (LAN) = 100 usec -- Throughput = 10 Gbps)
     * RTT (LAN) = 100 usec -- Throughput = 1 Gbps
     *
     * Buffer = Throughput * RTT
     *
     * Buffer (10 Gps) = (10 * 1000 * 1000 * 1000 / 8) * 0.0001 = 125000  (Round to 128KB)
     * Buffer (1 Gps) = (1 * 1000 * 1000 * 1000 / 8) * 0.0001 = 12500     (Round to 16KB)
     */
    var initialWindowLength = SystemUtil.getSizeAsInt(Configuration.INITIAL_WINDOW_LENGTH_PROP_NAME, 16 * 1024)
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * This option (ultimately SO_SNDBUF for the network socket) can impact loss rate. Loss can occur on the sender side due
     * to this buffer being too small.
     *
     *
     * This buffer must be large enough to accommodate the MTU as a minimum. In addition, some systems, most notably Windows,
     * need plenty of buffering on the send side to reach adequate throughput rates. If too large, this buffer can increase latency
     * or cause loss.
     *
     * This should be less than 2MB for most use-cases.
     *
     * A value of 0 will 'auto-configure' this setting
     */
    var sendBufferSize = 0
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * This option (ultimately SO_RCVBUF for the network socket) can impact loss rates when too small for the given processing.
     * If too large, this buffer can increase latency.
     *
     *
     * Values that tend to work well with Aeron are 2MB to 4MB. This setting must be large enough for the MTU of the sender. If not,
     * persistent loss can result. In addition, the receiver window length should be less than or equal to this value to allow plenty
     * of space for burst traffic from a sender.
     *
     * A value of 0 will 'auto-configure' this setting.
     */
    var receiveBufferSize = 0
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * Internal property that tells us if this configuration has already been configured and used to create and start the Media Driver
     */
    @Volatile
    internal var contextDefined: Boolean = false

    /**
     * Internal property that tells us if this configuration has already been used in an endpoint
     */
    @Volatile
    internal var previouslyUsed = false

    /**
     * Depending on the OS, different base locations for the Aeron log directory are preferred.
     */
    fun suggestAeronLogLocation(logger: KLogger): File {
        return when {
            OS.isMacOsX() -> {
                // does the recommended location exist??
                val suggestedLocation = File("/Volumes/DevShm")
                if (suggestedLocation.exists()) {
                    suggestedLocation
                }
                else {
                    logger.info("It is recommended to create a RAM drive for best performance. For example\n" + "\$ diskutil erasevolume HFS+ \"DevShm\" `hdiutil attach -nomount ram://\$((2048 * 2048))`\n" + "\t After this, set config.aeronLogDirectory = \"/Volumes/DevShm\"")

                    File(System.getProperty("java.io.tmpdir"))
                }
            }
            OS.isLinux() -> {
                // this is significantly faster for linux than using the temp dir
                File("/dev/shm/")
            }
            else -> {
                File(System.getProperty("java.io.tmpdir"))
            }
        }
    }

    /**
     * Validates the current configuration
     */
    @Suppress("DuplicatedCode")
    open fun validate() {
        // have to do some basic validation of our configuration

        require(!(enableIpc && aeronDirectoryForceUnique)) { "IPC enabled and forcing a unique Aeron directory are incompatible (IPC requires shared Aeron directories)!" }

        require(publicationPort > 0) { "configuration port must be > 0" }
        require(publicationPort < 65535)  { "configuration port must be < 65535" }

        require(subscriptionPort > 0) { "configuration controlPort must be > 0" }
        require(subscriptionPort < 65535) { "configuration controlPort must be < 65535" }

        require(networkMtuSize > 0) { "configuration networkMtuSize must be > 0" }
        require(networkMtuSize < 9 * 1024)  { "configuration networkMtuSize must be < ${9 * 1024}" }
    }
}
