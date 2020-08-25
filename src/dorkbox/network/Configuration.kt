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

import dorkbox.network.aeron.CoroutineBackoffIdleStrategy
import dorkbox.network.aeron.CoroutineIdleStrategy
import dorkbox.network.aeron.CoroutineSleepingMillisIdleStrategy
import dorkbox.network.serialization.NetworkSerializationManager
import dorkbox.network.serialization.Serialization
import dorkbox.network.storage.PropertyStore
import dorkbox.network.storage.SettingsStore
import dorkbox.os.OS
import dorkbox.util.storage.StorageBuilder
import dorkbox.util.storage.StorageSystem
import io.aeron.driver.Configuration
import io.aeron.driver.ThreadingMode
import mu.KLogger
import java.io.File

class ServerConfiguration : dorkbox.network.Configuration() {
    /**
     * The address for the server to listen on. "*" will accept connections from all interfaces, otherwise specify
     * the hostname (or IP) to bind to.
     */
    var listenIpAddress = "*"

    /**
     * The maximum number of clients allowed for a server
     */
    var maxClientCount = 0

    /**
     * The maximum number of client connection allowed per IP address
     */
    var maxConnectionsPerIpAddress = 0
}

open class Configuration {

    /**
     * When connecting to a remote client/server, should connections be allowed if the remote machine signature has changed?
     *
     * Setting this to false is not recommended as it is a security risk
     */
    var enableRemoteSignatureValidation: Boolean = true

    /**
     * Specify the UDP port to use. This port is used to establish client-server connections, and is from the
     * perspective of the server
     *
     * This means that server-pub -> {{network}} -> client-sub
     *
     * Must be greater than 0
     */
    var publicationPort: Int = 0

    /**
     * Specify the UDP MDC subscription port to use. This port is used to establish client-server connections, and is from the
     * perspective of the server.
     *
     * This means that client-pub -> {{network}} -> server-sub
     *
     * Must be greater than 0
     */
    var subscriptionPort: Int = 0


    /**
     * How long a connection must be disconnected before we cleanup the memory associated with it
     */
    var connectionCloseTimeoutInSeconds: Int = 10

    /**
     * Allows the end user to change how endpoint settings are stored. For example, a custom database instead of the default.
     */
    var settingsStore: SettingsStore = PropertyStore()

    /**
     * Specify the type of storage used for the endpoint settings , the options are Disk and Memory
     */
    var settingsStorageSystem: StorageBuilder = StorageSystem.Memory()

    /**
     * Specify the serialization manager to use.
     */
    var serialization: NetworkSerializationManager = Serialization.DEFAULT()

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

    /**
     * Log Buffer Locations for the Media Driver. The default location is a TEMP dir. This must be unique PER application and instance!
     */
    var aeronLogDirectory: File? = null

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
}
