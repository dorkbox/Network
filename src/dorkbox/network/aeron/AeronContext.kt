package dorkbox.network.aeron

import dorkbox.network.Configuration
import dorkbox.network.exceptions.AeronDriverException
import dorkbox.util.NamedThreadFactory
import io.aeron.driver.MediaDriver
import io.aeron.exceptions.DriverTimeoutException
import mu.KLogger
import java.io.File
import java.util.concurrent.locks.*

class AeronContext(
    val config: Configuration,
    val type: Class<*> = AeronDriver::class.java,
    val logger: KLogger,
    aeronErrorHandler: (error: Throwable) -> Unit
) {
    fun close() {
        context.close()

        // Destroys this thread group and all of its subgroups.
        // This thread group must be empty, indicating that all threads that had been in this thread group have since stopped.
        threadFactory.group.destroy()
    }

    companion object {
        private fun create(
            config: Configuration,
            threadFactory: NamedThreadFactory,
            aeronErrorHandler: (error: Throwable) -> Unit

        ): MediaDriver.Context {
            // LOW-LATENCY SETTINGS
            // .termBufferSparseFile(false)
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

            // driver context must happen in the initializer, because we have a Server.isRunning() method that uses the mediaDriverContext (without bind)
            val mediaDriverContext = MediaDriver.Context()
                .publicationReservedSessionIdLow(AeronDriver.RESERVED_SESSION_ID_LOW)
                .publicationReservedSessionIdHigh(AeronDriver.RESERVED_SESSION_ID_HIGH)
                .threadingMode(config.threadingMode)
                .mtuLength(config.networkMtuSize)

                .initialWindowLength(config.initialWindowLength)
                .socketSndbufLength(config.sendBufferSize)
                .socketRcvbufLength(config.receiveBufferSize)

            mediaDriverContext
                .conductorThreadFactory(threadFactory)
                .receiverThreadFactory(threadFactory)
                .senderThreadFactory(threadFactory)
                .sharedNetworkThreadFactory(threadFactory)
                .sharedThreadFactory(threadFactory)

            mediaDriverContext.aeronDirectoryName(config.aeronDirectory!!.absolutePath)

            if (mediaDriverContext.ipcTermBufferLength() != io.aeron.driver.Configuration.ipcTermBufferLength()) {
                // default 64 megs each is HUGE
                mediaDriverContext.ipcTermBufferLength(8 * 1024 * 1024)
            }

            if (mediaDriverContext.publicationTermBufferLength() != io.aeron.driver.Configuration.termBufferLength()) {
                // default 16 megs each is HUGE (we run out of space in production w/ lots of clients)
                mediaDriverContext.publicationTermBufferLength(2 * 1024 * 1024)
            }

            // we DO NOT want to abort the JVM if there are errors.
            // this replaces the default handler with one that doesn't abort the JVM
            mediaDriverContext.errorHandler { error ->
                aeronErrorHandler(AeronDriverException(error))
            }

            return mediaDriverContext
        }
    }

    // this is the aeron conductor/network processor thread factory which manages the incoming messages from the network.
    internal val threadFactory = NamedThreadFactory(
        "Aeron",
        ThreadGroup("${type.simpleName}-AeronDriver"), Thread.MAX_PRIORITY,
        true)


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
    val driverDirectory: File
        get() {
            return context.aeronDirectory()
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


    /**
     * Creates the Aeron Media Driver context
     *
     * @throws IllegalStateException if the configuration has already been used to create a context
     * @throws IllegalArgumentException if the aeron media driver directory cannot be setup
     */
     init {
        var context = create(config, threadFactory, aeronErrorHandler)

        // this happens EXACTLY once. Must be BEFORE the "isRunning" check!
        context.concludeAeronDirectory()

        // will setup the aeron directory or throw IllegalArgumentException if it cannot be configured
        val aeronDir = context.aeronDirectory()

        val driverTimeout = context.driverTimeoutMs()

        // sometimes when starting up, if a PREVIOUS run was corrupted (during startup, for example)
        // we ONLY do this during the initial startup check because it will delete the directory, and we don't
        // always want to do this.
        var isRunning = try {
            context.isDriverActive(driverTimeout) { }
        } catch (e: DriverTimeoutException) {
            // we have to delete the directory, since it was corrupted, and we try again.
            if (aeronDir.deleteRecursively()) {
                context.isDriverActive(driverTimeout) { }
            } else {
                // unable to delete the directory
                throw e
            }
        }

        // this is incompatible with IPC, and will not be set if IPC is enabled
        if (config.uniqueAeronDirectory && isRunning) {
            val savedParent = aeronDir.parentFile
            var retry = 0
            val retryMax = 100

            while (config.uniqueAeronDirectory && isRunning) {
                if (retry++ > retryMax) {
                    throw IllegalArgumentException("Unable to force unique aeron Directory. Tried $retryMax times and all tries were in use.")
                }

                val randomNum = (1..retryMax).shuffled().first()
                val newDir = savedParent.resolve("${aeronDir.name}_$randomNum")

                context = create(config, threadFactory, aeronErrorHandler)
                context.aeronDirectoryName(newDir.path)

                // this happens EXACTLY once. Must be BEFORE the "isRunning" check!
                context.concludeAeronDirectory()

                isRunning = context.isDriverActive(driverTimeout) { }
            }

            if (!isRunning) {
                // NOTE: We must be *super* careful trying to delete directories, because if we have multiple AERON/MEDIA DRIVERS connected to the
                //   same directory, deleting the directory will cause any other aeron connection to fail! (which makes sense).
                // since we are forcing a unique directory, we should ALSO delete it when we are done!
                context.dirDeleteOnShutdown()
            }
        }

        logger.info { "Aeron directory: '$aeronDir'" }

        this.context = context
    }

    override fun toString(): String {
        return context.toString()
    }
}
