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
@file:OptIn(ExperimentalCoroutinesApi::class)

package dorkbox.network

import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.network.aeron.CoroutineBackoffIdleStrategy
import dorkbox.network.aeron.CoroutineIdleStrategy
import dorkbox.network.connection.Connection
import dorkbox.network.connection.CryptoManagement
import dorkbox.network.serialization.Serialization
import dorkbox.os.OS
import dorkbox.storage.Storage
import dorkbox.util.NamedThreadFactory
import io.aeron.driver.Configuration
import io.aeron.driver.ThreadingMode
import io.aeron.driver.exceptions.InvalidChannelException
import io.aeron.exceptions.DriverTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import mu.KLogger
import mu.KotlinLogging
import org.agrona.concurrent.AgentTerminationException
import org.agrona.concurrent.IdleStrategy
import org.slf4j.helpers.NOPLogger
import java.io.File
import java.net.BindException
import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.*

class ServerConfiguration : dorkbox.network.Configuration() {
    companion object {
        /**
         * Gets the version number.
         */
        const val version = "6.6"
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
     * Allows the user to change how endpoint settings and public key information are saved.
     */
    override var settingsStore: Storage.Builder = Storage.Property().file("settings-server.db")
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }


    /**
     * Validates the current configuration
     */
    @Suppress("DuplicatedCode")
    override fun validate() {
        super.validate()

        // have to do some basic validation of our configuration
        if (listenIpAddress != listenIpAddress.lowercase()) {
            // only do this once!
            listenIpAddress = listenIpAddress.lowercase()
        }
        if (maxConnectionsPerIpAddress == 0) { maxConnectionsPerIpAddress = maxClientCount }


        require(listenIpAddress.isNotBlank()) { "Blank listen IP address, cannot continue." }
    }

    override fun initialize(logger: KLogger): dorkbox.network.ServerConfiguration {
        return super.initialize(logger) as dorkbox.network.ServerConfiguration
    }

    override fun copy(): dorkbox.network.ServerConfiguration {
        val config = ServerConfiguration()

        config.listenIpAddress = listenIpAddress
        config.maxClientCount = maxClientCount
        config.maxConnectionsPerIpAddress = maxConnectionsPerIpAddress
        config.settingsStore = settingsStore

        super.copy(config)

        return config
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ServerConfiguration) return false
        if (!super.equals(other)) return false

        if (listenIpAddress != other.listenIpAddress) return false
        if (maxClientCount != other.maxClientCount) return false
        if (maxConnectionsPerIpAddress != other.maxConnectionsPerIpAddress) return false
        if (settingsStore != other.settingsStore) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + listenIpAddress.hashCode()
        result = 31 * result + maxClientCount
        result = 31 * result + maxConnectionsPerIpAddress
        result = 31 * result + settingsStore.hashCode()
        return result
    }
}

class ClientConfiguration : dorkbox.network.Configuration() {

    /**
     * Specify the UDP port to use. This port is used by the client to listen for return traffic from the server.
     *
     * This will normally be autoconfigured randomly to the first available port, however it can be hardcoded in the event that
     * there is a reason autoconfiguration is not wanted - for example, specific firewall rules.
     *
     * Must be the value of an unsigned short and greater than 0
     */
    var port: Int = -1
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * Validates the current configuration. Throws an exception if there are problems.
     */
    @Suppress("DuplicatedCode")
    override fun validate() {
        super.validate()

        // have to do some basic validation of our configuration

        if (port != -1) {
            // this means it was configured!
            require(port > 0) { "Client listen port must be > 0" }
            require(port < 65535) { "Client listen port must be < 65535" }
        }
    }

    override fun initialize(logger: KLogger): dorkbox.network.ClientConfiguration {
        return super.initialize(logger) as dorkbox.network.ClientConfiguration
    }

    override fun copy(): dorkbox.network.ClientConfiguration {
        val config = ClientConfiguration()
        super.copy(config)

        config.port = port

        return config
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClientConfiguration) return false
        if (!super.equals(other)) return false

        if (port != other.port) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + port.hashCode()
        return result
    }
}

abstract class Configuration protected constructor() {
    @OptIn(ExperimentalCoroutinesApi::class)
    companion object {
        internal val NOP_LOGGER = KotlinLogging.logger(NOPLogger.NOP_LOGGER)

        internal const val errorMessage = "Cannot set a property after the configuration context has been created!"

        private val appIdRegexString = Regex("a-zA-Z0-9_.-")
        private val appIdRegex = Regex("^[$appIdRegexString]+$")

        internal val networkThreadGroup = ThreadGroup("Network")
        internal val aeronThreadFactory = NamedThreadFactory( "Aeron", networkThreadGroup,  true)

        const val UDP_HANDSHAKE_STREAM_ID: Int = 0x1337cafe  // 322423550
        const val IPC_HANDSHAKE_STREAM_ID: Int = 0x1337c0de  // 322420958

        private val defaultMessageCoroutineScope = Dispatchers.Default

        private val defaultAeronFilter: (error: Throwable) -> Boolean = { error ->
            // we suppress these because they are already handled
            when {
                error is InvalidChannelException || error.cause is InvalidChannelException -> { false }
                error is ClosedByInterruptException || error.cause is ClosedByInterruptException -> { false }
                error is DriverTimeoutException || error.cause is DriverTimeoutException -> { false }
                error is AgentTerminationException || error.cause is AgentTerminationException-> { false }
                error is BindException || error.cause is BindException -> { false }
                else -> { true }
            }
        }

        /**
         * Determines if the app ID is valid.
         */
        fun isAppIdValid(input: String): Boolean {
            return appIdRegex.matches(input)
        }

        /**
         * Depending on the OS, different base locations for the Aeron log directory are preferred.
         */
        fun defaultAeronLogLocation(logger: KLogger = NOP_LOGGER): File {
            return when {
                OS.isMacOsX -> {
                    // does the recommended location exist??

                    // Default is to try the RAM drive
                    val suggestedLocation = File("/Volumes/DevShm")
                    if (suggestedLocation.exists()) {
                        suggestedLocation
                    }
                    else if (logger !== NOP_LOGGER) {
                        // don't ALWAYS create it!
                        val sizeInGB = 2

                        // on macos, we cannot rely on users to actually create this -- so we automatically do it for them.
                        logger.info("Creating a $sizeInGB GB RAM drive for best performance.")

                        // hdiutil attach -nobrowse -nomount ram://4194304
                        val newDevice = dorkbox.executor.Executor()
                            .command("hdiutil", "attach", "-nomount", "ram://${sizeInGB * 1024 * 2048}")
                            .destroyOnExit()
                            .enableRead()
                            .startBlocking(60, TimeUnit.SECONDS)
                            .output
                            .string().trim().also { logger.trace { "Created new disk: $it" } }

                        // diskutil apfs createContainer /dev/disk4
                        val lines = dorkbox.executor.Executor()
                            .command("diskutil", "apfs", "createContainer", newDevice)
                            .destroyOnExit()
                            .enableRead()
                            .startBlocking(60, TimeUnit.SECONDS)
                            .output
                            .lines().onEach { line -> logger.trace { line } }

                        val newDiskLine = lines[lines.lastIndex-1]
                        val disk = newDiskLine.substring(newDiskLine.lastIndexOf(':')+1).trim()

                        // diskutil apfs addVolume disk5 APFS DevShm -nomount
                        dorkbox.executor.Executor()
                            .command("diskutil", "apfs", "addVolume", disk, "APFS", "DevShm", "-nomount")
                            .destroyOnExit()
                            .enableRead()
                            .startBlocking(60, TimeUnit.SECONDS)
                            .output
                            .string().also { logger.trace { it } }

                        // diskutil mount nobrowse "DevShm"
                        dorkbox.executor.Executor()
                            .command("diskutil", "mount", "nobrowse", "DevShm")
                            .destroyOnExit()
                            .enableRead()
                            .startBlocking(60, TimeUnit.SECONDS)
                            .output
                            .string().also { logger.trace { it } }

                        // touch /Volumes/RAMDisk/.metadata_never_index
                        File("${suggestedLocation}/.metadata_never_index").createNewFile()

                        suggestedLocation
                    }
                    else {
                        // we don't always want to create a ram drive!
                        OS.TEMP_DIR
                    }
                }
                OS.isLinux -> {
                    // this is significantly faster for linux than using the temp dir
                    File("/dev/shm/")
                }
                else -> {
                    OS.TEMP_DIR
                }
            }
        }
    }


    /**
     * Specify the application ID. This is necessary, as it prevents multiple instances of aeron from responding to applications that
     * is not theirs. Because of the shared nature of aeron drivers, this is necessary.
     *
     * This is a human-readable string, and it MUST be configured the same for both the clint/server
     */
    var appId = ""
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * In **very** unique circumstances, you might want to force the aeron driver to be shared between processes.
     *
     * This is only really applicable if the C driver is running/used.
     */
    var forceAllowSharedAeronDriver = false
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
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
     * The IPC ID is used to define what ID the server will receive data on for the handshake. The client IPC ID must match this value.
     */
    var ipcId = IPC_HANDSHAKE_STREAM_ID
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * The UDP ID is used to define what ID the server will receive data on for the handshake. The client UDP ID must match this value.
     */
    var udpId = UDP_HANDSHAKE_STREAM_ID
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
    var connectionCheckIntervalNanos = TimeUnit.MILLISECONDS.toNanos(200)
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
    var connectionExpirationTimoutNanos = TimeUnit.SECONDS.toNanos(2)
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * Set the subscription semantics for if data loss is acceptable or not, for a reliable message delivery.
     */
    var isReliable = true
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * Changes the default ping timeout, used to test the liveliness of a connection, specifically it's round-trip performance
     */
    var pingTimeoutSeconds = 30
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * Responsible for publishing messages that arrive via the network.
     *
     * Normally, events should be dispatched asynchronously across a thread pool, but in certain circumstances you may want to constrain this to a single thread dispatcher or other, custom dispatcher.
     */
    var messageDispatch = defaultMessageCoroutineScope
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }


    /**
     * Allows the user to change how endpoint settings and public key information are saved.
     *
     *  Note: This field is overridden for server configurations, so that the file used is different for client/server
     */
    open var settingsStore: Storage.Builder = Storage.Property().file("settings-client.db")
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * Specify the serialization manager to use. The type must extend `Connection`, since this will be cast
     */
    var serialization: Serialization<*> = Serialization<Connection>()
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * What is the max stream size that can exist in memory when deciding if data chunks are in memory or on temo-file on disk.
     * Data is streamed when it is too large to send in a single aeron message
     *
     * Must be >= 16 and <= 256
     */
    var maxStreamSizeInMemoryMB: Int = 16
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
    var pollIdleStrategy: CoroutineIdleStrategy = CoroutineBackoffIdleStrategy()
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
    var sendIdleStrategy: CoroutineIdleStrategy = CoroutineBackoffIdleStrategy()
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * The idle strategy used by the Aeron Media Driver to write to the network when in DEDICATED mode. Null will use the aeron defaults
     */
    var senderIdleStrategy: IdleStrategy? = null
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * The idle strategy used by the Aeron Media Driver read from the network when in DEDICATED mode. Null will use the aeron defaults
     */
    var receiverIdleStrategy: IdleStrategy? = null
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * The idle strategy used by the Aeron Media Driver to read/write to the network when in NETWORK_SHARED mode. Null will use the aeron defaults
     */
    var sharedIdleStrategy: IdleStrategy? = null
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    /**
     * The idle strategy used by the Aeron Media Driver conductor when in DEDICATED mode. Null will use the aeron defaults
     */
    var conductorIdleStrategy: IdleStrategy? = null
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
    var threadingMode = ThreadingMode.SHARED_NETWORK
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
            field = value?.absoluteFile ?: value
        }


    internal var uniqueAeronDirectoryID = 0

    /**
     * Should we force the Aeron location to be unique for every instance? This is mutually exclusive with IPC.
     */
    var uniqueAeronDirectory = false
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
     * However, it is good to understand these relationships.
     *
     * The MTU on the Media Driver controls the length of the MTU of data frames. This value is communicated to the Aeron clients during
     * registration. So, applications do not have to concern themselves with the MTU value used by the Media Driver and use the same value.
     *
     *
     * An MTU value over the interface MTU will cause IP to fragment the datagram. This may increase the likelihood of loss under several
     * circumstances. If increasing the MTU over the interface MTU, consider various ways to increase the interface MTU first in preparation.
     *
     * The MTU value indicates the largest message that Aeron will send as a single data frame.
     * MTU length also has implications for socket buffer sizing.
     *
     * Default value is 1408 for internet; for a LAN, 9k is possible with jumbo frames (if the routers/interfaces support it)
     */
    var networkMtuSize = Configuration.MTU_LENGTH_DEFAULT
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    var ipcMtuSize = Configuration.MAX_UDP_PAYLOAD_LENGTH
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
    var initialWindowLength = 16 * 1024
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
     * The "term" buffer is the size of EACH of the 3 (Active, Dirty, Clean) log files that are used to send/receive messages.
     *  - the smallest term buffer length is 65536 bytes;
     *  - the largest term buffer length is 1,073,741,824 bytes;
     *  - the size must be a power of 2;
     *  - maximum message length is the smallest value of 16 megabytes or (term buffer length) / 8;
     *
     * A value of 0 will 'auto-configure' this setting to use the default of 64 megs. Be wary, it is easy to run out of space w/ lots of clients
     *
     * @see [io.aeron.driver.Configuration.TERM_BUFFER_IPC_LENGTH_DEFAULT]
     * @see [io.aeron.logbuffer.LogBufferDescriptor.TERM_MIN_LENGTH]
     * @see [io.aeron.logbuffer.LogBufferDescriptor.TERM_MAX_LENGTH]
     */
    var ipcTermBufferLength = 8 * 1024 * 1024
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }


    /**
     * The "term" buffer is the size of EACH of the 3 (Active, Dirty, Clean) log files that are used to send/receive messages.
     *  - the smallest term buffer length is 65536 bytes;
     *  - the largest term buffer length is 1,073,741,824 bytes;
     *  - the size must be a power of 2;
     *  - maximum message length is the smallest value of 16 megabytes or (term buffer length) / 8;
     *
     * A value of 0 will 'auto-configure' this setting to use the default of 16 megs. Be wary, it is easy to run out of space w/ lots of clients
     *
     *  @see [io.aeron.driver.Configuration.TERM_BUFFER_LENGTH_DEFAULT]
     *  @see [io.aeron.logbuffer.LogBufferDescriptor.TERM_MIN_LENGTH]
     *  @see [io.aeron.logbuffer.LogBufferDescriptor.TERM_MAX_LENGTH]
     */
    var publicationTermBufferLength = 2 * 1024 * 1024
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }


    /**
     * This allows the user to setup the error filter for Aeron *SPECIFIC* error messages.
     *
     * Aeron WILL report more errors than normal (which is where there are suppression statements), because we cannot manage
     * the emitted errors from Aeron when we attempt/retry connections. This filters out those errors so we can log (or perform an action)
     * when those errors are encountered
     *
     * This is for advanced usage, and REALLY should never be over-ridden.
     *
     * @return true if the error message should be logged, false to suppress the error
     */
    var aeronErrorFilter: (error: Throwable) -> Boolean = defaultAeronFilter
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
     * Validates the current configuration. Throws an exception if there are problems.
     */
    @Suppress("DuplicatedCode")
    open fun validate() {
        // have to do some basic validation of our configuration

        require(appId.isNotEmpty()) { "The application ID must be set, as it prevents an listener from responding to differently configured applications. This is a human-readable string, and it MUST be configured the same for both the clint/server!"}

        // The applicationID is used to create the prefix for the aeron directory -- EVEN IF the directory name is specified.
        require(appId.length < 32) { "The application ID is too long, it must be < 32 characters" }

        require(isAppIdValid(appId)) { "The application ID is not valid. It may only be the following characters: $appIdRegexString" }

        // can't disable everything!
        require(enableIpc || enableIPv4 || enableIPv6) { "At least one of IPC/IPv4/IPv6 must be enabled!" }

        if (enableIpc) {
            require(!uniqueAeronDirectory) { "IPC enabled and forcing a unique Aeron directory are incompatible (IPC requires shared Aeron directories)!" }
        } else {
            if (enableIPv4 && !enableIPv6) {
                require(IPv4.isAvailable) { "IPC/IPv6 are disabled and IPv4 is enabled, but there is no IPv4 interface available!" }
            }

            if (!enableIPv4 && enableIPv6) {
                require(IPv6.isAvailable) { "IPC/IPv4 are disabled and IPv6 is enabled, but there is no IPv6 interface available!" }
            }
        }

        require(maxStreamSizeInMemoryMB >= 16) { "configuration maxStreamSizeInMemoryMB must be >= 16" }
        require(maxStreamSizeInMemoryMB <= 256) { "configuration maxStreamSizeInMemoryMB must be <= 256" } // 256 is arbitrary

        require(networkMtuSize > 0) { "configuration networkMtuSize must be > 0" }
        require(networkMtuSize < Configuration.MAX_UDP_PAYLOAD_LENGTH)  { "configuration networkMtuSize must be < ${Configuration.MAX_UDP_PAYLOAD_LENGTH}" }
        require(ipcMtuSize > 0) { "configuration ipcMtuSize must be > 0" }
        require(ipcMtuSize <= Configuration.MAX_UDP_PAYLOAD_LENGTH)  { "configuration ipcMtuSize must be <= ${Configuration.MAX_UDP_PAYLOAD_LENGTH}" }

        require(sendBufferSize >= 0) { "configuration socket send buffer must be >= 0"}
        require(receiveBufferSize >= 0) { "configuration socket receive buffer must be >= 0"}
        require(ipcTermBufferLength > 65535) { "configuration IPC term buffer must be > 65535"}
        require(ipcTermBufferLength < 1_073_741_824) { "configuration IPC term buffer must be < 1,073,741,824"}
        require(publicationTermBufferLength > 65535) { "configuration publication term buffer must be > 65535"}
        require(publicationTermBufferLength < 1_073_741_824) { "configuration publication term buffer must be < 1,073,741,824"}
    }

    internal open fun initialize(logger: KLogger): dorkbox.network.Configuration  {
        // explicitly don't set defaults if we already have the context defined!
        if (contextDefined) {
            return this
        }

        // we are starting a new context, make sure the aeron directory is unique (if specified)
        if (uniqueAeronDirectory) {
            uniqueAeronDirectoryID = CryptoManagement.secureRandom.let {
                // make sure it's not 0, because 0 is special
                var id = 0
                while (id == 0) id = it.nextInt()
                id
            }
        }

//        /*
//         * Linux
//         * Linux normally requires some settings of sysctl values. One is net.core.rmem_max to allow larger SO_RCVBUF and
//         * net.core.wmem_max to allow larger SO_SNDBUF values to be set.
//         *
//         * Windows
//         * Windows tends to use SO_SNDBUF values that are too small. It is recommended to use values more like 1MB or so.
//         *
//         * Mac/Darwin
//         * Mac tends to use SO_SNDBUF values that are too small. It is recommended to use larger values, like 16KB.
//         */
//        if (receiveBufferSize == 0) {
//            receiveBufferSize = io.aeron.driver.Configuration.SOCKET_RCVBUF_LENGTH_DEFAULT * 4
//            //            when {
//            //                OS.isLinux() ->
//            //                OS.isWindows() ->
//            //                OS.isMacOsX() ->
//            //            }
//
//            //            val rmem_max = dorkbox.network.other.NetUtil.sysctlGetInt("net.core.rmem_max")
//        }
//
//
//        if (sendBufferSize == 0) {
//            sendBufferSize = io.aeron.driver.Configuration.SOCKET_SNDBUF_LENGTH_DEFAULT * 4
//            //            when {
//            //                OS.isLinux() ->
//            //                OS.isWindows() ->
//            //                OS.isMacOsX() ->
//            //            }
//
//                        val wmem_max = dorkbox.netUtil.SocketUtils.sysctlGetInt("net.core.wmem_max")
//        }


        /*
         * Note: Since Mac OS does not have a built-in support for /dev/shm, we automatically create a RAM disk for the Aeron directory (aeron.dir).
         *
         * You can create a RAM disk with the following command:
         *
         * $ diskutil erasevolume APFS "DISK_NAME" `hdiutil attach -nomount ram://$((SIZE_IN_MB * 2048))`
         *
         * where:
         *
         * DISK_NAME should be replaced with a name of your choice.
         * SIZE_IN_MB is the size in megabytes for the disk (e.g. 4096 for a 4GB disk).
         *
         * For example, the following command creates a RAM disk named DevShm which is 8GB in size:
         *
         * $ diskutil erasevolume APFS "DevShm" `hdiutil attach -nomount ram://$((8 * 1024 * 2048))`
         *
         * After this command is executed the new disk will be mounted under /Volumes/DevShm.
         */
        var dir = aeronDirectory

        if (dir != null) {
            if (forceAllowSharedAeronDriver) {
                logger.warn { "Forcing the Aeron driver to be shared between processes. THIS IS DANGEROUS!" }
            } else if (!dir.absolutePath.endsWith(appId)) {
                // we have defined an aeron directory
                dir = File(dir.absolutePath + "_$appId")
            }
        }
        else {
            val baseFileLocation = defaultAeronLogLocation(logger)
            val prefix = if (appId.startsWith("aeron_")) {
                ""
            } else {
                "aeron_"
            }


            val aeronLogDirectory = if (uniqueAeronDirectory) {
                // this is incompatible with IPC, and will not be set if IPC is enabled (error will be thrown on validate)
                File(baseFileLocation, "$prefix${appId}_${mediaDriverIdNoDir()}")
            } else {
                File(baseFileLocation, "$prefix$appId")
            }
            dir = aeronLogDirectory.absoluteFile
        }

        aeronDirectory = dir!!.absoluteFile

        // cannot make any more changes to the configuration!
        contextDefined = true

        return this
    }


    // internal class for making sure that the AeronDriver is not duplicated for the same configuration (as that is entirely unnecessary)
    internal class MediaDriverConfig(val config: dorkbox.network.Configuration) {
        val connectionCloseTimeoutInSeconds get() = config.connectionCloseTimeoutInSeconds
        val threadingMode get() = config.threadingMode

        val networkMtuSize get() = config.networkMtuSize
        val ipcMtuSize get() = config.ipcMtuSize
        val initialWindowLength get() = config.initialWindowLength
        val sendBufferSize get() = config.sendBufferSize
        val receiveBufferSize get() = config.receiveBufferSize

        val aeronDirectory get() = config.aeronDirectory
        val uniqueAeronDirectory get() = config.uniqueAeronDirectory
        val uniqueAeronDirectoryID get() = config.uniqueAeronDirectoryID

        val forceAllowSharedAeronDriver get() = config.forceAllowSharedAeronDriver

        val ipcTermBufferLength get() = config.ipcTermBufferLength
        val publicationTermBufferLength get() = config.publicationTermBufferLength

        val conductorIdleStrategy get() = config.conductorIdleStrategy
        val sharedIdleStrategy get() = config.sharedIdleStrategy
        val receiverIdleStrategy get() = config.receiverIdleStrategy
        val senderIdleStrategy get() = config.senderIdleStrategy

        val aeronErrorFilter get() = config.aeronErrorFilter
        var contextDefined
            get() = config.contextDefined
            set(value) {
                config.contextDefined = value
            }

        /**
         * Validates the current configuration. Throws an exception if there are problems.
         */
        @Suppress("DuplicatedCode")
        fun validate() {
            // already validated! do nothing.
        }

        /**
         * Normally, the hashCode MAY be duplicate for entities that are similar, but not the same identity (via .equals). In the case
         * of the MediaDriver config, the ID is used to uniquely identify a config that has the same VALUES, but is not the same REFERENCE.
         *
         * This is because configs that are DIFFERENT, but have the same values MUST use the same aeron driver.
         */
        fun mediaDriverId(): Int {
            return config.mediaDriverId()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MediaDriverConfig) return false

            return mediaDriverEquals(config)
        }

        override fun hashCode(): Int {
            return config.mediaDriverId()
        }

        @Suppress("DuplicatedCode", "RedundantIf")
        private fun mediaDriverEquals(other: dorkbox.network.Configuration): Boolean {
            if (forceAllowSharedAeronDriver != other.forceAllowSharedAeronDriver) return false
            if (connectionCloseTimeoutInSeconds != other.connectionCloseTimeoutInSeconds) return false
            if (threadingMode != other.threadingMode) return false
            if (networkMtuSize != other.networkMtuSize) return false
            if (ipcMtuSize != other.ipcMtuSize) return false
            if (initialWindowLength != other.initialWindowLength) return false
            if (sendBufferSize != other.sendBufferSize) return false
            if (receiveBufferSize != other.receiveBufferSize) return false

            if (aeronDirectory != other.aeronDirectory) return false
            if (uniqueAeronDirectory != other.uniqueAeronDirectory) return false
            if (uniqueAeronDirectoryID != other.uniqueAeronDirectoryID) return false

            if (ipcTermBufferLength != other.ipcTermBufferLength) return false
            if (publicationTermBufferLength != other.publicationTermBufferLength) return false
            if (aeronErrorFilter != other.aeronErrorFilter) return false

            if (conductorIdleStrategy != other.conductorIdleStrategy) return false
            if (sharedIdleStrategy != other.sharedIdleStrategy) return false
            if (receiverIdleStrategy != other.receiverIdleStrategy) return false
            if (senderIdleStrategy != other.senderIdleStrategy) return false

            return true
        }
    }

    @Suppress("DuplicatedCode", "RedundantIf")
    private fun mediaDriverEquals(other: dorkbox.network.Configuration): Boolean {
        if (forceAllowSharedAeronDriver != other.forceAllowSharedAeronDriver) return false
        if (threadingMode != other.threadingMode) return false
        if (networkMtuSize != other.networkMtuSize) return false
        if (ipcMtuSize != other.ipcMtuSize) return false
        if (initialWindowLength != other.initialWindowLength) return false
        if (sendBufferSize != other.sendBufferSize) return false
        if (receiveBufferSize != other.receiveBufferSize) return false

        if (conductorIdleStrategy != other.conductorIdleStrategy) return false
        if (sharedIdleStrategy != other.sharedIdleStrategy) return false
        if (receiverIdleStrategy != other.receiverIdleStrategy) return false
        if (senderIdleStrategy != other.senderIdleStrategy) return false

        if (aeronDirectory != other.aeronDirectory) return false
        if (uniqueAeronDirectory != other.uniqueAeronDirectory) return false
        if (uniqueAeronDirectoryID != other.uniqueAeronDirectoryID) return false

        if (ipcTermBufferLength != other.ipcTermBufferLength) return false
        if (publicationTermBufferLength != other.publicationTermBufferLength) return false
        if (aeronErrorFilter != other.aeronErrorFilter) return false

        return true
    }

    abstract fun copy(): dorkbox.network.Configuration
    protected fun copy(config: dorkbox.network.Configuration) {
        config.appId = appId
        config.forceAllowSharedAeronDriver = forceAllowSharedAeronDriver
        config.enableIPv4 = enableIPv4
        config.enableIPv6 = enableIPv6
        config.enableIpc = enableIpc
        config.ipcId = ipcId
        config.udpId = udpId
        config.enableRemoteSignatureValidation = enableRemoteSignatureValidation
        config.connectionCloseTimeoutInSeconds = connectionCloseTimeoutInSeconds
        config.connectionCheckIntervalNanos = connectionCheckIntervalNanos
        config.connectionExpirationTimoutNanos = connectionExpirationTimoutNanos
        config.isReliable = isReliable
        config.pingTimeoutSeconds = pingTimeoutSeconds
        config.messageDispatch = messageDispatch
        config.settingsStore = settingsStore
        config.serialization = serialization
        config.maxStreamSizeInMemoryMB = maxStreamSizeInMemoryMB
        config.pollIdleStrategy = pollIdleStrategy.clone()
        config.sendIdleStrategy = sendIdleStrategy.clone()
        config.threadingMode = threadingMode
        config.aeronDirectory = aeronDirectory
        config.uniqueAeronDirectoryID = uniqueAeronDirectoryID
        config.uniqueAeronDirectory = uniqueAeronDirectory
        config.networkMtuSize = networkMtuSize
        config.initialWindowLength = initialWindowLength
        config.sendBufferSize = sendBufferSize
        config.receiveBufferSize = receiveBufferSize
        config.ipcTermBufferLength = ipcTermBufferLength
        config.publicationTermBufferLength = publicationTermBufferLength
        config.aeronErrorFilter = aeronErrorFilter
        // config.contextDefined = contextDefined // we want to be able to reuse this config if it's a copy of an already defined config
    }

    fun mediaDriverId(): Int {
        var result = mediaDriverIdNoDir()
        result = 31 * result + aeronDirectory.hashCode()

        return result
    }

    private fun mediaDriverIdNoDir(): Int {
        var result = threadingMode.hashCode()
        result = 31 * result + networkMtuSize
        result = 31 * result + ipcMtuSize
        result = 31 * result + initialWindowLength
        result = 31 * result + sendBufferSize
        result = 31 * result + receiveBufferSize

        result = 31 * result + uniqueAeronDirectoryID
        result = 31 * result + uniqueAeronDirectory.hashCode()

        result = 31 * result + ipcTermBufferLength
        result = 31 * result + publicationTermBufferLength

        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is dorkbox.network.Configuration) return false

        // some values are defined here. Not necessary to list them twice
        if (!mediaDriverEquals(other)) return false

        if (appId != other.appId) return false
        if (enableIPv4 != other.enableIPv4) return false
        if (enableIPv6 != other.enableIPv6) return false
        if (enableIpc != other.enableIpc) return false
        if (ipcId != other.ipcId) return false
        if (udpId != other.udpId) return false

        if (enableRemoteSignatureValidation != other.enableRemoteSignatureValidation) return false
        if (connectionCloseTimeoutInSeconds != other.connectionCloseTimeoutInSeconds) return false
        if (connectionCheckIntervalNanos != other.connectionCheckIntervalNanos) return false
        if (connectionExpirationTimoutNanos != other.connectionExpirationTimoutNanos) return false

        if (isReliable != other.isReliable) return false
        if (pingTimeoutSeconds != other.pingTimeoutSeconds) return false
        if (settingsStore != other.settingsStore) return false
        if (serialization != other.serialization) return false
        if (maxStreamSizeInMemoryMB != other.maxStreamSizeInMemoryMB) return false

        if (pollIdleStrategy != other.pollIdleStrategy) return false
        if (sendIdleStrategy != other.sendIdleStrategy) return false

        if (uniqueAeronDirectory != other.uniqueAeronDirectory) return false
        if (uniqueAeronDirectoryID != other.uniqueAeronDirectoryID) return false
        if (ipcTermBufferLength != other.ipcTermBufferLength) return false
        if (contextDefined != other.contextDefined) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mediaDriverId()

        result = 31 * result + appId.hashCode()
        result = 31 * result + forceAllowSharedAeronDriver.hashCode()
        result = 31 * result + enableIPv4.hashCode()
        result = 31 * result + enableIPv6.hashCode()
        result = 31 * result + enableIpc.hashCode()
        result = 31 * result + enableRemoteSignatureValidation.hashCode()
        result = 31 * result + ipcId
        result = 31 * result + udpId
        result = 31 * result + pingTimeoutSeconds
        result = 31 * result + connectionCloseTimeoutInSeconds
        result = 31 * result + connectionCheckIntervalNanos.hashCode()
        result = 31 * result + connectionExpirationTimoutNanos.hashCode()
        result = 31 * result + isReliable.hashCode()
        result = 31 * result + messageDispatch.hashCode()
        result = 31 * result + settingsStore.hashCode()
        result = 31 * result + serialization.hashCode()
        result = 31 * result + maxStreamSizeInMemoryMB
        result = 31 * result + pollIdleStrategy.hashCode()
        result = 31 * result + sendIdleStrategy.hashCode()
        // aeronErrorFilter // cannot get the predictable hash code of a lambda
        result = 31 * result + contextDefined.hashCode()
        return result
    }
}
