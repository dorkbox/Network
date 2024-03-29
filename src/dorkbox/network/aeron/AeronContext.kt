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

package dorkbox.network.aeron

import dorkbox.network.Configuration
import dorkbox.network.exceptions.AeronDriverException
import dorkbox.util.Sys
import io.aeron.driver.MediaDriver
import io.aeron.exceptions.DriverTimeoutException
import org.slf4j.Logger
import java.io.Closeable
import java.io.File
import java.util.concurrent.*

/**
 * Creates the Aeron Media Driver context
 *
 * @throws IllegalStateException if the configuration has already been used to create a context
 * @throws IllegalArgumentException if the aeron media driver directory cannot be setup
 */
internal class AeronContext(config: Configuration.MediaDriverConfig, logger: Logger, aeronErrorHandler: (Throwable) -> Unit) : Closeable {
    companion object {
        private fun create(config: Configuration.MediaDriverConfig, aeronErrorHandler: (Throwable) -> Unit): MediaDriver.Context {
            // LOW-LATENCY SETTINGS
            // MediaDriver.Context()
            //             .termBufferSparseFile(false)
            //             .useWindowsHighResTimer(true)
            //             .threadingMode(ThreadingMode.DEDICATED)
            //             .conductorIdleStrategy(BusySpinIdleStrategy.INSTANCE)
            //             .receiverIdleStrategy(NoOpIdleStrategy.INSTANCE)
            //             .senderIdleStrategy(NoOpIdleStrategy.INSTANCE);
            //     setProperty(DISABLE_BOUNDS_CHECKS_PROP_NAME, "true");
            //    setProperty("aeron.mtu.length", "16384");
            //    setProperty("aeron.socket.so_sndbuf", "2097152");
            //    setProperty("aeron.socket.so_rcvbuf", "2097152");
            //    setProperty("aeron.rcv.initial.window.length", "2097152");

            val threadFactory = Configuration.aeronThreadFactory

            // driver context must happen in the initializer, because we have a Server.isRunning() method that uses the mediaDriverContext (without bind)
            val mediaDriverContext = MediaDriver.Context()
                .termBufferSparseFile(false) // files occupy the same space virtually AND physically!
                .useWindowsHighResTimer(true)

                // we assign our OWN ID! so we reserve everything.
                .publicationReservedSessionIdLow(AeronDriver.RESERVED_SESSION_ID_LOW)
                .publicationReservedSessionIdHigh(AeronDriver.RESERVED_SESSION_ID_HIGH)

                .threadingMode(config.threadingMode)
                .mtuLength(config.networkMtuSize)
                .ipcMtuLength(config.ipcMtuSize)

                .initialWindowLength(config.initialWindowLength)

                .conductorThreadFactory(threadFactory)
                .receiverThreadFactory(threadFactory)
                .senderThreadFactory(threadFactory)
                .sharedNetworkThreadFactory(threadFactory)
                .sharedThreadFactory(threadFactory)


            if (config.sendBufferSize > 0) {
                mediaDriverContext.socketSndbufLength(config.sendBufferSize)
            }

            if (config.receiveBufferSize > 0) {
                mediaDriverContext.socketRcvbufLength(config.receiveBufferSize)
            }

            if (config.conductorIdleStrategy != null) {
                mediaDriverContext.conductorIdleStrategy(config.conductorIdleStrategy)
            }
            if (config.sharedIdleStrategy != null) {
                mediaDriverContext.sharedIdleStrategy(config.sharedIdleStrategy)
            }
            if (config.receiverIdleStrategy != null) {
                mediaDriverContext.receiverIdleStrategy(config.receiverIdleStrategy)
            }
            if (config.senderIdleStrategy != null) {
                mediaDriverContext.senderIdleStrategy(config.senderIdleStrategy)
            }

            mediaDriverContext.aeronDirectoryName(config.aeronDirectory!!.path)

            if (config.ipcTermBufferLength > 0) {
                mediaDriverContext.ipcTermBufferLength(config.ipcTermBufferLength)
            }

            if (config.publicationTermBufferLength > 0) {
                mediaDriverContext.publicationTermBufferLength(config.publicationTermBufferLength)
            }

            // we DO NOT want to abort the JVM if there are errors.
            // this replaces the default handler with one that doesn't abort the JVM
            mediaDriverContext.errorHandler(aeronErrorHandler)

            return mediaDriverContext
        }
    }

    // the context is validated before the AeronDriver object is created
    val context: MediaDriver.Context

    /**
     * @return the configured driver timeout
     */
    val driverTimeout: Long
        get() {
            return context.driverTimeoutMs()
        }

    /**
     * This is only valid **AFTER** [context.concludeAeronDirectory()] has been called.
     *
     * @return the aeron context directory
     */
    val directory: File
        get() {
            return context.aeronDirectory()
        }

    fun deleteAeronDir(): Boolean {
        // NOTE: We must be *super* careful trying to delete directories, because if we have multiple AERON/MEDIA DRIVERS connected to the
        //   same directory, deleting the directory will cause any other aeron connection to fail! (which makes sense).
        return directory.deleteRecursively()
    }

    /**
     * Checks to see if an endpoint (using the specified configuration) is running.
     *
     * @return true if the media driver is active and running
     */
    fun isRunning(): Boolean {
        // if the media driver is running, it will be a quick connection. Usually 100ms or so
        return context.isDriverActive(context.driverTimeoutMs()) { }
    }

    private fun isRunning(context: MediaDriver.Context): Boolean {
        // if the media driver is running, it will be a quick connection. Usually 100ms or so
        return try {
            context.isDriverActive(context.driverTimeoutMs()) { }
        } catch (e: Exception) {
            false
        }
    }

    init {
        // NOTE: if a DIFFERENT PROCESS is using the SAME driver location, THERE WILL BE POTENTIAL PROBLEMS!
        //  ADDITIONALLY, the ONLY TIME we create a new aeron context is when it is the FIRST aeron context for a driver. Within the same
        //  JVM, the aeron driver/context is SHARED.
        val context = create(config, aeronErrorHandler)

        // this happens EXACTLY once. Must be BEFORE the "isRunning" check!
        context.concludeAeronDirectory()

        // will setup the aeron directory or throw IllegalArgumentException if it cannot be configured
        val aeronDir = context.aeronDirectory()

        val driverTimeout = context.driverTimeoutMs()

        // sometimes when starting up, if a PREVIOUS run was corrupted (during startup, for example)
        // we ONLY do this during the initial startup check because it will delete the directory, and we don't always want to do this.

        val isRunning = try {
            context.isDriverActive(driverTimeout) { }
        } catch (e: DriverTimeoutException) {
            // we have to delete the directory, since it was corrupted, and we try again.
            if (!config.forceAllowSharedAeronDriver && aeronDir.deleteRecursively()) {
                context.isDriverActive(driverTimeout) { }
            } else if (config.forceAllowSharedAeronDriver) {
                // we are expecting a shared directory. SOMETHING is screwed up!
                throw AeronDriverException("Aeron was expected to be running, and the current location is corrupted. Not doing anything!", e)
            } else {
                // unable to delete the directory
                throw e
            }
        }

        // only do this if we KNOW we are not running!
        if (!isRunning) {
            // NOTE: We must be *super* careful trying to delete directories, because if we have multiple AERON/MEDIA DRIVERS connected to the
            //   same directory, deleting the directory will cause any other aeron connection to fail! (which makes sense).
            // make sure it's clean!
            aeronDir.deleteRecursively()

            // if we are not CURRENTLY running, then we should ALSO delete it when we are done!
            context.dirDeleteOnShutdown()
        } else if (!config.forceAllowSharedAeronDriver) {
            // maybe it's a mistake because we restarted too quickly! A brief pause to fix this!

            val timeoutInNs = TimeUnit.SECONDS.toNanos(config.connectionCloseTimeoutInSeconds.toLong()) + context.publicationLingerTimeoutNs()
            val timeoutInMs = TimeUnit.NANOSECONDS.toMillis(timeoutInNs)
            logger.warn("Aeron is currently running, waiting ${Sys.getTimePrettyFull(timeoutInNs)} for it to close.")

            // wait for it to close! wait longer.
            val startTime = System.nanoTime()
            while (isRunning(context) && System.nanoTime() - startTime < timeoutInNs) {
                Thread.sleep(timeoutInMs)
            }

            require(!isRunning(context)) { "Aeron is currently running, and this is the first instance created by this JVM. " +
                    "You must use `config.forceAllowSharedAeronDriver` to be able to re-use a shared aeron process at: $aeronDir" }
        }

        this.context = context
    }

    override fun toString(): String {
        return context.toString()
    }

    override fun close() {
        context.close()
    }
}
