package dorkbox.network;


import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import dorkbox.network.connection.EndPoint;
import dorkbox.util.entropy.Entropy;
import dorkbox.util.entropy.SimpleEntropy;
import dorkbox.util.exceptions.InitializationException;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

import static org.junit.Assert.fail;

public abstract
class BaseTest {

    public static final String host = "localhost";
    public static final int tcpPort = 54558;
    public static final int udpPort = 54779;
    public static final int udtPort = 54580;

    static {
        // we want our entropy generation to be simple (ie, no user interaction to generate)
        try {
            Entropy.init(SimpleEntropy.class);
        } catch (InitializationException e) {
            e.printStackTrace();
        }
    }

    volatile boolean fail_check;
    private final ArrayList<EndPoint> endPoints = new ArrayList<EndPoint>();

    public
    BaseTest() {
        System.out.println("---- " + getClass().getSimpleName());

        // assume SLF4J is bound to logback in the current environment
        Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        LoggerContext context = rootLogger.getLoggerContext();

        JoranConfigurator jc = new JoranConfigurator();
        jc.setContext(context);
        context.reset(); // override default configuration

//        rootLogger.setLevel(Level.OFF);

        rootLogger.setLevel(Level.DEBUG);
//        rootLogger.setLevel(Level.TRACE);
//        rootLogger.setLevel(Level.ALL);


        // we only want error messages
        Logger nettyLogger = (Logger) LoggerFactory.getLogger("io.netty");
        nettyLogger.setLevel(Level.ERROR);

        // we only want error messages
        Logger kryoLogger = (Logger) LoggerFactory.getLogger("com.esotericsoftware");
        kryoLogger.setLevel(Level.ERROR);

        // we only want error messages
        Logger barchartLogger = (Logger) LoggerFactory.getLogger("com.barchart");
        barchartLogger.setLevel(Level.ERROR);

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%date{HH:mm:ss.SSS}  %-5level [%logger{35}] %msg%n");
        encoder.start();

        ConsoleAppender<ILoggingEvent> consoleAppender = new ch.qos.logback.core.ConsoleAppender<ILoggingEvent>();

        consoleAppender.setContext(context);
        consoleAppender.setEncoder(encoder);
        consoleAppender.start();

        rootLogger.addAppender(consoleAppender);
    }

    public
    void addEndPoint(final EndPoint endPoint) {
        this.endPoints.add(endPoint);
    }

    /**
     * Immediately stop the endpoints
     */
    public
    void stopEndPoints() {
        stopEndPoints(1);
    }

    public
    void stopEndPoints(final int stopAfterMillis) {
        if (stopAfterMillis > 0) {
            // We have to ALWAYS run this in a new thread, BECAUSE if stopEndPoints() is called from a client/server thread, it will
            // DEADLOCK
            final Thread thread = new Thread(getThreadGroup(), new Runnable() {
                @Override
                public
                void run() {
                    try {
                        // not the best, but this works for our purposes. This is a TAD hacky, because we ALSO have to make sure that we
                        // ARE NOT in the same thread group as netty!
                        Thread.sleep(stopAfterMillis);

                        synchronized (BaseTest.this.endPoints) {
                            for (EndPoint endPoint : BaseTest.this.endPoints) {
                                endPoint.stop();
                                endPoint.waitForShutdown();
                            }
                            BaseTest.this.endPoints.clear();
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }, "UnitTest timeout");

            thread.setDaemon(true);
            thread.start();
        }
    }

    private
    ThreadGroup getThreadGroup() {
        ThreadGroup threadGroup = Thread.currentThread()
                                        .getThreadGroup();
        final String name = threadGroup.getName();
        if (name.contains(EndPoint.THREADGROUP_NAME)) {
            threadGroup = threadGroup.getParent();
        }
        return threadGroup;
    }

    public
    void waitForThreads(int stopAfterSecondsOrMillis) {
        if (stopAfterSecondsOrMillis < 1000) {
            stopAfterSecondsOrMillis *= 1000;
        }
        stopEndPoints(stopAfterSecondsOrMillis);
        waitForThreads0(stopAfterSecondsOrMillis);
    }

    /**
     * Wait for threads until they are done (no timeout)
     */
    public
    void waitForThreads() {
        waitForThreads0(0);
    }

    private
    void waitForThreads0(final int stopAfterMillis) {
        this.fail_check = false;

        Thread thread = null;

        if (stopAfterMillis > 0L) {
            stopEndPoints(stopAfterMillis);

            // We have to ALWAYS run this in a new thread, BECAUSE if stopEndPoints() is called from a client/server thread, it will
            // DEADLOCK
            thread = new Thread(getThreadGroup(), new Runnable() {
                @Override
                public
                void run() {
                    try {
                        // not the best, but this works for our purposes. This is a TAD hacky, because we ALSO have to make sure that we
                        // ARE NOT in the same thread group as netty!
                        Thread.sleep(stopAfterMillis + 120000L); // test must run in 2 minutes or it fails

                        BaseTest.this.fail_check = true;
                    } catch (InterruptedException ignored) {
                    }
                }
            }, "UnitTest timeout");

            thread.setDaemon(true);
            thread.start();
        }

        while (true) {
            synchronized (this.endPoints) {
                if (this.endPoints.isEmpty()) {
                    break;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
        }

        if (this.fail_check) {
            fail("Test did not complete in a timely manner.");
        }

        if (thread != null) {
            thread.interrupt();
        }

        // Give sockets a chance to close before starting the next test.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
        }
    }
}
