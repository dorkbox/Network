package dorkbox.network.connection;

import static dorkbox.network.pipeline.ConnectionType.EPOLL;
import static dorkbox.network.pipeline.ConnectionType.KQUEUE;
import static dorkbox.network.pipeline.ConnectionType.NIO;
import static dorkbox.network.pipeline.ConnectionType.OIO;

import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;

import dorkbox.network.NativeLibrary;
import dorkbox.network.pipeline.ConnectionType;
import dorkbox.util.NamedThreadFactory;
import dorkbox.util.OS;
import dorkbox.util.Property;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.internal.PlatformDependent;

/**
 * This is the highest level endpoint, for lifecycle support/management.
 */
public
class Shutdownable {
    static {
        //noinspection Duplicates
        try {
            // doesn't work when running from inside eclipse.
            // Needed for NIO selectors on Android 2.2, and to force IPv4.
            System.setProperty("java.net.preferIPv4Stack", Boolean.TRUE.toString());
            System.setProperty("java.net.preferIPv6Addresses", Boolean.FALSE.toString());

            // java6 has stack overflow problems when loading certain classes in it's classloader. The result is a StackOverflow when
            // loading them normally. This calls AND FIXES this issue.
            if (OS.javaVersion == 6) {
                if (PlatformDependent.hasUnsafe()) {
                    //noinspection ResultOfMethodCallIgnored
                    PlatformDependent.newFixedMpscQueue(8);
                }
            }
        } catch (AccessControlException ignored) {
        }
    }


    protected static final String shutdownHookName = "::SHUTDOWN_HOOK::";
    protected static final String stopTreadName = "::STOP_THREAD::";

    public static final String THREADGROUP_NAME = "(Netty)";

    /**
     * The HIGH and LOW watermark points for connections
     */
    @Property
    public static final int WRITE_BUFF_HIGH = 32 * 1024;
    @Property
    public static final int WRITE_BUFF_LOW = 8 * 1024;

    /**
     * The number of threads used for the worker threads.
     */
    @Property
    public static int WORKER_THREAD_POOL_SIZE = Math.min(Runtime.getRuntime().availableProcessors() / 2, 1);

    /**
     * The amount of time in milli-seconds to wait for this endpoint to close all {@link Channel}s and shutdown gracefully.
     */
    @Property
    public static long maxShutdownWaitTimeInMilliSeconds = 2000L; // in milliseconds

    /**
     * Checks to see if we are running in the netty thread. This is (usually) to prevent potential deadlocks in code that CANNOT be run from
     * inside a netty worker.
     */
    public static
    boolean isNettyThread() {
        return Thread.currentThread()
                     .getThreadGroup()
                     .getName()
                     .contains(THREADGROUP_NAME);
    }

    /**
     * Runs a runnable inside a NEW thread that is NOT in the same thread group as Netty
     */
    public static
    void runNewThread(final String threadName, final Runnable runnable) {
        Thread thread = new Thread(Thread.currentThread()
                                         .getThreadGroup()
                                         .getParent(),
                                   runnable);
        thread.setDaemon(true);
        thread.setName(threadName);
        thread.start();
    }

    protected final org.slf4j.Logger logger;

    protected final ThreadGroup threadGroup;

    protected final Class<? extends Shutdownable> type;

    protected final Object shutdownInProgress = new Object();
    private volatile boolean isShutdown = false;

    // the eventLoop groups are used to track and manage the event loops for startup/shutdown
    private final List<EventLoopGroup> eventLoopGroups = new ArrayList<EventLoopGroup>(8);
    private final List<ChannelFuture> shutdownChannelList = new ArrayList<ChannelFuture>();

    // make sure that the endpoint is closed on JVM shutdown (if it's still open at that point in time)
    private Thread shutdownHook;

    private final CountDownLatch blockUntilDone = new CountDownLatch(1);

    private AtomicBoolean stopCalled = new AtomicBoolean(false);

    public
    Shutdownable(final Class<? extends Shutdownable> type) {
        this.type = type;

        // setup the thread group to easily ID what the following threads belong to (and their spawned threads...)
        SecurityManager s = System.getSecurityManager();
        threadGroup = new ThreadGroup(s != null
                                      ? s.getThreadGroup()
                                      : Thread.currentThread()
                                              .getThreadGroup(), type.getSimpleName() + " " + THREADGROUP_NAME);
        threadGroup.setDaemon(true);

        logger = org.slf4j.LoggerFactory.getLogger(type.getSimpleName());


        shutdownHook = new Thread() {
            @Override
            public
            void run() {
                if (Shutdownable.this.shouldShutdownHookRun()) {
                    Shutdownable.this.stop();
                }
            }
        };
        shutdownHook.setName(shutdownHookName);
        try {
            Runtime.getRuntime()
                   .addShutdownHook(shutdownHook);
        } catch (Throwable ignored) {
            // if we are in the middle of shutdown, we cannot do this.
        }
    }

    /**
     * Add a channel future to be tracked and managed for shutdown.
     */
    protected final
    void manageForShutdown(ChannelFuture future) {
        synchronized (shutdownChannelList) {
            shutdownChannelList.add(future);
        }
    }

    /**
     * Add an eventloop group to be tracked & managed for shutdown
     */
    protected final
    void manageForShutdown(EventLoopGroup loopGroup) {
        synchronized (eventLoopGroups) {
            eventLoopGroups.add(loopGroup);
        }
    }

    /**
     * Remove an eventloop group to be tracked & managed for shutdown
     */
    protected final
    void removeFromShutdown(EventLoopGroup loopGroup) {
        synchronized (eventLoopGroups) {
            eventLoopGroups.remove(loopGroup);
        }
    }

    // server only does this on stop. Client does this on closeConnections
    void shutdownAllChannels() {
        synchronized (shutdownChannelList) {
            // now we stop all of our channels. For the server, this will close the server manager for UDP sessions
            for (ChannelFuture f : shutdownChannelList) {
                Channel channel = f.channel();
                if (channel.isOpen()) {
                    channel.close()
                           .awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
                    Thread.yield();
                }
            }

            // we have to clear the shutdown list. (
            shutdownChannelList.clear();
        }
    }

    // shutdown all event loops associated
    void shutdownEventLoops() {
        // we want to WAIT until after the event executors have completed shutting down.
        List<Future<?>> shutdownThreadList = new LinkedList<Future<?>>();

        List<EventLoopGroup> loopGroups;
        synchronized (eventLoopGroups) {
            loopGroups = new ArrayList<EventLoopGroup>(eventLoopGroups.size());
            loopGroups.addAll(eventLoopGroups);
        }

        for (EventLoopGroup loopGroup : loopGroups) {
            shutdownThreadList.add(loopGroup.shutdownGracefully(maxShutdownWaitTimeInMilliSeconds,
                                                                maxShutdownWaitTimeInMilliSeconds * 10,
                                                                TimeUnit.MILLISECONDS));
            Thread.yield();
        }

        // now wait for them to finish!
        // It can take a few seconds to shut down the executor. This will affect unit testing, where connections are quickly created/stopped
        for (Future<?> f : shutdownThreadList) {
            f.syncUninterruptibly();
            Thread.yield();
        }
    }

    protected final
    String stopWithErrorMessage(Logger logger, String errorMessage, Throwable throwable) {
        if (logger.isDebugEnabled() && throwable != null) {
            // extra info if debug is enabled
            logger.error(errorMessage, throwable.getCause());
        }
        else {
            logger.error(errorMessage);
        }

        stop();
        return errorMessage;
    }

    /**
     * Starts the shutdown process during JVM shutdown, if necessary.
     * </p>
     * By default, we always can shutdown via the JVM shutdown hook.
     */
    protected
    boolean shouldShutdownHookRun() {
        return true;
    }


    /**
     * Creates a new event loop based on the OS type and specified configuration
     *
     * @param threadCount number of threads for the event loop
     *
     * @return a new event loop group based on the specified parameters
     */
    protected
    EventLoopGroup newEventLoop(final int threadCount, final String threadName) {
        if (OS.isAndroid()) {
            // android ONLY supports OIO
            return newEventLoop(OIO, threadCount, threadName);
        }
        else if (OS.isLinux() && NativeLibrary.isAvailable()) {
            // epoll network stack is MUCH faster (but only on linux)
            return newEventLoop(EPOLL, threadCount, threadName);
        }
        else if (OS.isMacOsX() && NativeLibrary.isAvailable()) {
            // KQueue network stack is MUCH faster (but only on macosx)
            return newEventLoop(KQUEUE, threadCount, threadName);
        }
        else {
            return newEventLoop(NIO, threadCount, threadName);
        }
    }

    /**
     * Creates a new event loop based on the specified configuration
     *
     * @param connectionType LOCAL, NIO, EPOLL, etc...
     * @param threadCount number of threads for the event loop
     *
     * @return a new event loop group based on the specified parameters
     */
    protected
    EventLoopGroup newEventLoop(final ConnectionType connectionType, final int threadCount, final String threadName) {
        NamedThreadFactory threadFactory = new NamedThreadFactory(threadName, threadGroup);

        EventLoopGroup group;

        switch (connectionType) {
            case LOCAL:
                group = new DefaultEventLoopGroup(threadCount, threadFactory);
                break;
            case OIO:
                group = new OioEventLoopGroup(threadCount, threadFactory);
                break;
            case NIO:
                group = new NioEventLoopGroup(threadCount, threadFactory);
                break;
            case EPOLL:
                group = new EpollEventLoopGroup(threadCount, threadFactory);
                break;
            case KQUEUE:
                group = new KQueueEventLoopGroup(threadCount, threadFactory);
                break;

            default:
                group = new DefaultEventLoopGroup(threadCount, threadFactory);
                break;
        }

        manageForShutdown(group);
        return group;
    }

    /**
     * Check to see if the current thread is running from it's OWN thread, or from Netty... This is used to prevent deadlocks.
     *
     * @return true if the specified thread is as Netty thread, false if it's own thread.
     */
    protected
    boolean isInEventLoop(Thread thread) {
        for (EventLoopGroup loopGroup : eventLoopGroups) {
            for (EventExecutor next : loopGroup) {
                if (next.inEventLoop(thread)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Safely closes all associated resources/threads/connections.
     * <p/>
     * If we want to WAIT for this endpoint to shutdown, we must explicitly call waitForShutdown()
     * <p/>
     * Override stopExtraActions() if you want to provide extra behavior while stopping the endpoint
     */
    public final
    void stop() {
        // only permit us to "stop" once!
        if (!stopCalled.compareAndSet(false, true)) {
            return;
        }

        // check to make sure we are in our OWN thread, otherwise, this thread will never exit -- because it will wait indefinitely
        // for itself to finish (since it blocks itself).
        // This occurs when calling stop from within a listener callback.
        Thread currentThread = Thread.currentThread();
        String threadName = currentThread.getName();
        boolean isShutdownThread = !threadName.equals(shutdownHookName) && !threadName.equals(stopTreadName);

        // used to check the event groups to see if we are running from one of them. NOW we force to
        // ALWAYS shutdown inside a NEW thread
        if (!isShutdownThread || !isInEventLoop(currentThread)) {
            stopInThread();
        }
        else {
            Thread thread = new Thread(new Runnable() {
                @Override
                public
                void run() {
                    Shutdownable.this.stopInThread();
                }
            });
            thread.setDaemon(false);
            thread.setName(stopTreadName);
            thread.start();
        }
    }

    /**
     * Extra EXTERNAL actions to perform when stopping this endpoint.
     */
    protected
    void stopExtraActions() {
    }

    /**
     * Actions that happen by the endpoint before the channels are shutdown
     */
    protected
    void shutdownChannelsPre() {
    }


    /**
     * Actions that happen by the endpoint before any extra actions are run.
     */
    protected
    void stopExtraActionsInternal() {
    }

    // This actually does the "stopping", since there is some logic to making sure we don't deadlock, this is important
    private
    void stopInThread() {
        // make sure we are not trying to stop during a startup procedure.
        // This will wait until we have finished starting up/shutting down.
        synchronized (shutdownInProgress) {
            shutdownChannelsPre();
            shutdownAllChannels();

            shutdownEventLoops();

            logger.info("Stopping endpoint.");

            // there is no need to call "stop" again if we close the connection.
            // however, if this is called WHILE from the shutdown hook, blammo! problems!

            // Also, you can call client/server.stop from another thread, which is run when the JVM is shutting down
            // (as there is nothing left to do), and also have problems.
            if (!Thread.currentThread()
                       .getName()
                       .equals(shutdownHookName)) {
                try {
                    Runtime.getRuntime()
                           .removeShutdownHook(shutdownHook);
                } catch (Exception e) {
                    // ignore
                }
            }

            stopExtraActionsInternal();

            // when the eventloop closes, the associated selectors are ALSO closed!
            stopExtraActions();

            // we also want to stop the thread group
            threadGroup.interrupt();

            isShutdown = true;
        }

        // tell the blocked "bind" method that it may continue (and exit)
        blockUntilDone.countDown();
    }

    /**
     * Blocks the current thread until the endpoint has been stopped. If the endpoint is already stopped, this do nothing.
     */
    public final
    void waitForShutdown() {
        // we now BLOCK until the stop method is called.
        try {
            blockUntilDone.await();
        } catch (InterruptedException e) {
            logger.error("Thread interrupted while waiting for stop!");
        }
    }

    /**
     * @return true if we have already shutdown, false otherwise
     */
    public final
    boolean isShutdown() {
        synchronized (shutdownInProgress) {
            return isShutdown;
        }
    }

    @Override
    public
    String toString() {
        return "EndPoint [" + getName() + "]";
    }

    public
    String getName() {
        return type.getSimpleName();
    }
}
