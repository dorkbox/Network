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
package dorkbox.network;

import java.io.File;
import java.util.concurrent.TimeUnit;

import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;

import dorkbox.network.serialization.NetworkSerializationManager;
import dorkbox.network.store.SettingsStore;
import io.aeron.driver.ThreadingMode;

public
class Configuration {
    /**
     * Specify the UDP port to use. This port is used to establish client-server connections.
     * <p>
     * Must be greater than 0
     */
    public int port = 0;

    /**
     * Specify the UDP MDC control port to use. This port is used to establish client-server connections.
     * <p>
     * Must be greater than 0
     */
    public int controlPort = 0;

    /**
     * Allows the end user to change how server settings are stored. For example, a custom database instead of the default.
     */
    public SettingsStore settingsStore = null;

    /**
     * Specify the serialization manager to use. If null, it uses the default.
     */
    public NetworkSerializationManager serialization = null;


    /**
     * The idle strategy used when polling the Media Driver for new messages. BackOffIdleStrategy is the DEFAULT.
     *
     * There are a couple strategies of importance to understand.
     * <p>
     * BusySpinIdleStrategy uses a busy spin as an idle and will eat up CPU by default.
     * <p>
     * BackOffIdleStrategy uses a backoff strategy of spinning, yielding, and parking to be kinder to the CPU, but to be less
     * responsive to activity when idle for a little while.
     * <p>
     * <p>
     * The main difference in strategies is how responsive to changes should the idler be when idle for a little bit of time and
     * how much CPU should be consumed when no work is being done. There is an inherent tradeoff to consider.
     */
    public IdleStrategy messagePollIdleStrategy = new BackoffIdleStrategy(100, 10,
                                                                          TimeUnit.MICROSECONDS.toNanos(10),
                                                                          TimeUnit.MILLISECONDS.toNanos(100));


    /**
     * A Media Driver, whether being run embedded or not, needs 1-3 threads to perform its operation.
     * <p>
     * There are three main Agents in the driver:
     * <p>
     *    Conductor: Responsible for reacting to client requests and house keeping duties as well as detecting loss, sending NAKs,
     *                  rotating buffers, etc.
     *       Sender: Responsible for shovelling messages from publishers to the network.
     *     Receiver: Responsible for shovelling messages from the network to subscribers.
     * <p>
     * This value can be one of:
     * <p>
     *     INVOKER: No threads. The client is responsible for using the MediaDriver.Context.driverAgentInvoker() to invoke the duty
     *                  cycle directly.
     *     SHARED: All Agents share a single thread. 1 thread in total.
     *     SHARED_NETWORK: Sender and Receiver shares a thread, conductor has its own thread. 2 threads in total.
     *     DEDICATED: The default and dedicates one thread per Agent. 3 threads in total.
     * <p>
     * For performance, it is recommended to use DEDICATED as long as the number of busy threads is less than or equal to the number of
     *      spare cores on the machine. If there are not enough cores to dedicate, then it is recommended to consider sharing some with
     *      SHARED_NETWORK or SHARED. INVOKER can be used for low resource environments while the application using Aeron can invoke the
     *      media driver to carry out its duty cycle on a regular interval.
     */
    public ThreadingMode threadingMode = ThreadingMode.SHARED;

    /**
     * Log Buffer Locations for the Media Driver. The default location is a TEMP dir. This must be unique PER application and instance!
     */
    public File aeronLogDirectory = null;

    /**
     * The Aeron MTU value impacts a lot of things.
     * <p>
     * The default MTU is set to a value that is a good trade-off. However, it is suboptimal for some use cases involving very large
     * (> 4KB) messages and for maximizing throughput above everything else. Various checks during publication and subscription/connection
     * setup are done to verify a decent relationship with MTU.
     * <p>
     * However, it is good to understand these relationships.
     * <p>
     * The MTU on the Media Driver controls the length of the MTU of data frames. This value is communicated to the Aeron clients during
     * registration. So, applications do not have to concern themselves with the MTU value used by the Media Driver and use the same value.
     * <p>
     * An MTU value over the interface MTU will cause IP to fragment the datagram. This may increase the likelihood of loss under several
     * circumstances. If increasing the MTU over the interface MTU, consider various ways to increase the interface MTU first in preparation.
     * <p>
     * The MTU value indicates the largest message that Aeron will send as a single data frame.
     * <p>
     * MTU length also has implications for socket buffer sizing.
     * <p>
     * <p>
     * Default value is 1408 for internet; for a LAN, 9k is possible with jumbo frames (if the routers/interfaces support it)
     */
    public int networkMtuSize = io.aeron.driver.Configuration.MTU_LENGTH_DEFAULT;

     /**
      * This option (ultimately SO_SNDBUF for the network socket) can impact loss rate. Loss can occur on the sender side due
      * to this buffer being too small.
      * <p>
      * This buffer must be large enough to accommodate the MTU as a minimum. In addition, some systems, most notably Windows,
      * need plenty of buffering on the send side to reach adequate throughput rates. If too large, this buffer can increase latency
      * or cause loss.
      * <p>
      * This usually should be less than 2MB.
     */
    public int sendBufferSize = 0;

    /**
     * This option (ultimately SO_RCVBUF for the network socket) can impact loss rates when too small for the given processing.
     * If too large, this buffer can increase latency.
     * <p>
     * Values that tend to work well with Aeron are 2MB to 4MB. This setting must be large enough for the MTU of the sender. If not,
     * persistent loss can result. In addition, the receiver window length should be less than or equal to this value to allow plenty
     * of space for burst traffic from a sender.
     */
    public int receiveBufferSize = 0;







    public
    Configuration() {

    }
}
