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
package dorkbox.network

import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.CoroutineBackoffIdleStrategy
import dorkbox.network.aeron.CoroutineIdleStrategy
import dorkbox.network.aeron.CoroutineSleepingMillisIdleStrategy
import dorkbox.network.connection.Connection
import dorkbox.network.serialization.Serialization
import dorkbox.os.OS
import dorkbox.storage.Storage
import dorkbox.util.NamedThreadFactory
import io.aeron.driver.Configuration
import io.aeron.driver.ThreadingMode
import io.aeron.driver.exceptions.InvalidChannelException
import io.aeron.exceptions.DriverTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import mu.KLogger
import org.agrona.SystemUtil
import org.agrona.concurrent.AgentTerminationException
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
        const val version = "6.4"
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
     * The IPC ID is used to define what ID the server will receive data on. The client IPC ID must match this value.
     */
    var ipcId = AeronDriver.IPC_HANDSHAKE_STREAM_ID
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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ServerConfiguration) return false
        if (!super.equals(other)) return false

        if (listenIpAddress != other.listenIpAddress) return false
        if (maxClientCount != other.maxClientCount) return false
        if (maxConnectionsPerIpAddress != other.maxConnectionsPerIpAddress) return false
        if (ipcId != other.ipcId) return false
        if (settingsStore != other.settingsStore) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + listenIpAddress.hashCode()
        result = 31 * result + maxClientCount
        result = 31 * result + maxConnectionsPerIpAddress
        result = 31 * result + ipcId
        result = 31 * result + settingsStore.hashCode()
        return result
    }
}

class ClientConfiguration : dorkbox.network.Configuration() {
    /**
     * Validates the current configuration. Throws an exception if there are problems.
     */
    @Suppress("DuplicatedCode")
    override fun validate() {
        super.validate()

        // have to do some basic validation of our configuration
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClientConfiguration) return false
        if (!super.equals(other)) return false
        return true
    }

    override fun hashCode(): Int {
        return super.hashCode()
    }
}

abstract class Configuration {
    companion object {
        internal const val errorMessage = "Cannot set a property after the configuration context has been created!"

        @Volatile
        private var alreadyShownTempFsTips = false

        internal val networkThreadGroup = ThreadGroup("Network")
        internal val aeronThreadFactory = NamedThreadFactory( "Aeron", networkThreadGroup, Thread.NORM_PRIORITY, true)

        private val defaultNetworkEventPoll = Executors.newSingleThreadExecutor(
            NamedThreadFactory( "Poll Dispatcher", networkThreadGroup, Thread.NORM_PRIORITY, true)
        )

        private val defaultActionEventDispatcher = Executors.newSingleThreadExecutor(
            NamedThreadFactory( "Event Dispatcher", networkThreadGroup, Thread.NORM_PRIORITY, true)
        ).asCoroutineDispatcher()

        private val defaultEventCoroutineScope = CoroutineScope(defaultActionEventDispatcher)
        private val defaultMessageCoroutineScope = CoroutineScope(Dispatchers.Default)

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
     * Specify the UDP port to use. This port is used to establish client-server connections.
     *
     * When used for the server, this is the subscription port, which will be listening for incoming connections
     * When used for the client, this is the publication port, which is what port to connect to when establishing a connection
     *
     * This means that client-pub -> {{network}} -> server-sub
     *
     * Must be the value of an unsigned short and greater than 0
     *
     * In order to bypass issues with NAT, one EXTRA port is used - by default it is this port + 1
     */
    var port: Int = 0
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }

    var controlPort: Int = 0
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
     * Specifies the Java thread that will poll the underlying network for incoming messages
     */
    var networkEventPoll: ExecutorService = defaultNetworkEventPoll
        set(value) {
            require(!contextDefined) { errorMessage }
            field = value
        }


    /**
     * Responsible for executing connection/misc events
     *
     * NOTE: This is very specifically NOT 'CoroutineScope(Dispatchers.Default)', because it is very easy (and tricky) to make sure
     *      that there is no thread starvation going on, which can, and WILL happen.
     */
    var eventDispatch = defaultEventCoroutineScope
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
    var sendBufferSize = 1048576
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
    var receiveBufferSize = 2097152
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
     * Depending on the OS, different base locations for the Aeron log directory are preferred.
     */
    private fun suggestAeronLogLocation(logger: KLogger): File {
        return when {
            OS.isMacOsX -> {
                // does the recommended location exist??

                // Default is to try the RAM drive
                val suggestedLocation = File("/Volumes/DevShm")
                if (suggestedLocation.exists()) {
                    suggestedLocation
                }
                else {
                    if (logger !== NOPLogger.NOP_LOGGER) {
                        if (!alreadyShownTempFsTips) {
                            alreadyShownTempFsTips = true
                            logger.info(
                                "It is recommended to create a RAM drive for best performance. For example\n" + "\$ diskutil erasevolume HFS+ \"DevShm\" `hdiutil attach -nomount ram://\$((2048 * 2048))`"
                            )
                        }
                    }

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

    /**
     * Validates the current configuration. Throws an exception if there are problems.
     */
    @Suppress("DuplicatedCode")
    open fun validate() {
        // have to do some basic validation of our configuration

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

        require(port > 0) { "configuration controlPort must be > 0" }
        require(port < 65535) { "configuration controlPort must be < 65535" }

        require(networkMtuSize > 0) { "configuration networkMtuSize must be > 0" }
        require(networkMtuSize < 9 * 1024)  { "configuration networkMtuSize must be < ${9 * 1024}" }

        require(sendBufferSize > 0) { "configuration socket send buffer must be > 0"}
        require(receiveBufferSize > 0) { "configuration socket receive buffer must be > 0"}
        require(ipcTermBufferLength > 65535) { "configuration IPC term buffer must be > 65535"}
        require(ipcTermBufferLength < 1_073_741_824) { "configuration IPC term buffer must be < 1,073,741,824"}
        require(publicationTermBufferLength > 65535) { "configuration publication term buffer must be > 65535"}
        require(publicationTermBufferLength < 1_073_741_824) { "configuration publication term buffer must be < 1,073,741,824"}

        require(eventDispatch.coroutineContext != Dispatchers.Default) { "configuration of the eventDispatch.context must be it's own ThreadExecutor. It CANNOT be the default dispatch because there will be thread starvation"}
    }

    internal fun setDefaults(logger: KLogger) {
        // explicitly don't set defaults if we already have the context defined!
        if (contextDefined) {
            return
        }

        /*
         * Linux
         * Linux normally requires some settings of sysctl values. One is net.core.rmem_max to allow larger SO_RCVBUF and
         * net.core.wmem_max to allow larger SO_SNDBUF values to be set.
         *
         * Windows
         * Windows tends to use SO_SNDBUF values that are too small. It is recommended to use values more like 1MB or so.
         *
         * Mac/Darwin
         *
         * Mac tends to use SO_SNDBUF values that are too small. It is recommended to use larger values, like 16KB.
         */
        if (receiveBufferSize == 0) {
            receiveBufferSize = io.aeron.driver.Configuration.SOCKET_RCVBUF_LENGTH_DEFAULT
            //            when {
            //                OS.isLinux() ->
            //                OS.isWindows() ->
            //                OS.isMacOsX() ->
            //            }

            //            val rmem_max = dorkbox.network.other.NetUtil.sysctlGetInt("net.core.rmem_max")
            //            val wmem_max = dorkbox.network.other.NetUtil.sysctlGetInt("net.core.wmem_max")
        }


        if (sendBufferSize == 0) {
            sendBufferSize = io.aeron.driver.Configuration.SOCKET_SNDBUF_LENGTH_DEFAULT
            //            when {
            //                OS.isLinux() ->
            //                OS.isWindows() ->
            //                OS.isMacOsX() ->
            //            }

            //            val rmem_max = dorkbox.network.other.NetUtil.sysctlGetInt("net.core.rmem_max")
            //            val wmem_max = dorkbox.network.other.NetUtil.sysctlGetInt("net.core.wmem_max")
        }


        /*
         * Note: Since Mac OS does not have a built-in support for /dev/shm it is advised to create a RAM disk for the Aeron directory (aeron.dir).
         *
         * You can create a RAM disk with the following command:
         *
         * $ diskutil erasevolume HFS+ "DISK_NAME" `hdiutil attach -nomount ram://$((2048 * SIZE_IN_MB))`
         *
         * where:
         *
         * DISK_NAME should be replaced with a name of your choice.
         * SIZE_IN_MB is the size in megabytes for the disk (e.g. 4096 for a 4GB disk).
         *
         * For example, the following command creates a RAM disk named DevShm which is 2GB in size:
         *
         * $ diskutil erasevolume HFS+ "DevShm" `hdiutil attach -nomount ram://$((2048 * 2048))`
         *
         * After this command is executed the new disk will be mounted under /Volumes/DevShm.
         */
        if (aeronDirectory == null) {
            val baseFileLocation = suggestAeronLogLocation(logger)
            val aeronLogDirectory = File(baseFileLocation, "aeron")
            aeronDirectory = aeronLogDirectory
        }

        aeronDirectory = aeronDirectory!!.absoluteFile
    }


    // internal class for making sure that the AeronDriver is not duplicated for the same configuration (as that is entirely unnecessary)
    internal class MediaDriverConfig : dorkbox.network.Configuration() {
        /**
         * Validates the current configuration. Throws an exception if there are problems.
         */
        @Suppress("DuplicatedCode")
        override fun validate() {
            // have to do some basic validation of our configuration
            require(sendBufferSize > 0) { "configuration socket send buffer must be > 0"}
            require(receiveBufferSize > 0) { "configuration socket receive buffer must be > 0"}
            require(ipcTermBufferLength > 65535) { "configuration IPC term buffer must be > 65535"}
            require(ipcTermBufferLength < 1_073_741_824) { "configuration IPC term buffer must be < 1,073,741,824"}
            require(publicationTermBufferLength > 65535) { "configuration publication term buffer must be > 65535"}
            require(publicationTermBufferLength < 1_073_741_824) { "configuration publication term buffer must be < 1,073,741,824"}

            require(networkMtuSize > 0) { "configuration networkMtuSize must be > 0" }
            require(networkMtuSize < 9 * 1024)  { "configuration networkMtuSize must be < ${9 * 1024}" }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MediaDriverConfig) return false

            return mediaDriverEquals(this)
        }

        override fun hashCode(): Int {
            return mediaDriverHash()
        }
    }
    internal fun asMediaDriverConfig(): MediaDriverConfig {
        val newConfig = MediaDriverConfig()

        threadingMode = newConfig.threadingMode
        networkMtuSize = newConfig.networkMtuSize
        initialWindowLength = newConfig.initialWindowLength
        sendBufferSize = newConfig.sendBufferSize
        receiveBufferSize = newConfig.receiveBufferSize
        aeronDirectory = newConfig.aeronDirectory
        ipcTermBufferLength = newConfig.ipcTermBufferLength
        publicationTermBufferLength = newConfig.publicationTermBufferLength
        aeronErrorFilter = newConfig.aeronErrorFilter

        return newConfig
    }
    fun mediaDriverEquals(other: dorkbox.network.Configuration): Boolean {
        if (threadingMode != other.threadingMode) return false
        if (networkMtuSize != other.networkMtuSize) return false
        if (initialWindowLength != other.initialWindowLength) return false
        if (sendBufferSize != other.sendBufferSize) return false
        if (receiveBufferSize != other.receiveBufferSize) return false
        if (aeronDirectory != other.aeronDirectory) return false
        if (ipcTermBufferLength != other.ipcTermBufferLength) return false
        if (publicationTermBufferLength != other.publicationTermBufferLength) return false
        if (aeronErrorFilter != other.aeronErrorFilter) return false

        return true
    }

    fun mediaDriverHash(): Int {
        var result = threadingMode.hashCode()
        result = 31 * result + networkMtuSize
        result = 31 * result + initialWindowLength
        result = 31 * result + sendBufferSize
        result = 31 * result + receiveBufferSize
        result = 31 * result + ipcTermBufferLength
        result = 31 * result + publicationTermBufferLength
        result = 31 * result + aeronErrorFilter.hashCode() // lambda
        result = 31 * result + (aeronDirectory?.hashCode() ?: 0)

        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is dorkbox.network.Configuration) return false

        if (!mediaDriverEquals(other)) return false

        if (networkEventPoll != other.networkEventPoll) return false
        if (enableIPv4 != other.enableIPv4) return false
        if (enableIPv6 != other.enableIPv6) return false
        if (enableIpc != other.enableIpc) return false
        if (enableRemoteSignatureValidation != other.enableRemoteSignatureValidation) return false
        if (port != other.port) return false
        if (controlPort != other.controlPort) return false
        if (connectionCloseTimeoutInSeconds != other.connectionCloseTimeoutInSeconds) return false
        if (connectionCheckIntervalNanos != other.connectionCheckIntervalNanos) return false
        if (connectionExpirationTimoutNanos != other.connectionExpirationTimoutNanos) return false
        if (isReliable != other.isReliable) return false
        if (eventDispatch != other.eventDispatch) return false
        if (settingsStore != other.settingsStore) return false
        if (serialization != other.serialization) return false
        if (pollIdleStrategy != other.pollIdleStrategy) return false
        if (sendIdleStrategy != other.sendIdleStrategy) return false

        if (uniqueAeronDirectory != other.uniqueAeronDirectory) return false
        if (ipcTermBufferLength != other.ipcTermBufferLength) return false
        if (contextDefined != other.contextDefined) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mediaDriverHash()
        result = 31 * result + networkEventPoll.hashCode()
        result = 31 * result + enableIPv4.hashCode()
        result = 31 * result + enableIPv6.hashCode()
        result = 31 * result + enableIpc.hashCode()
        result = 31 * result + enableRemoteSignatureValidation.hashCode()
        result = 31 * result + port
        result = 31 * result + controlPort
        result = 31 * result + connectionCloseTimeoutInSeconds
        result = 31 * result + connectionCheckIntervalNanos.hashCode()
        result = 31 * result + connectionExpirationTimoutNanos.hashCode()
        result = 31 * result + isReliable.hashCode()
        result = 31 * result + eventDispatch.hashCode()
        result = 31 * result + settingsStore.hashCode()
        result = 31 * result + serialization.hashCode()
        result = 31 * result + pollIdleStrategy.hashCode()
        result = 31 * result + sendIdleStrategy.hashCode()
        result = 31 * result + uniqueAeronDirectory.hashCode()
        result = 31 * result + contextDefined.hashCode()
        return result
    }
}
