package dorkbox.network;


import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import com.esotericsoftware.minlog.Log;
import dorkbox.network.connection.EndPoint;
import dorkbox.util.entropy.Entropy;
import dorkbox.util.entropy.SimpleEntropy;
import dorkbox.util.exceptions.InitializationException;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

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

    boolean fail_check;
    private final ArrayList<EndPoint> endPoints = new ArrayList<EndPoint>();
    private volatile Timer timer;

    public
    BaseTest() {
        System.out.println("---- " + getClass().getSimpleName());

        // set the minlog logging level
        Log.DEBUG();

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

        // let the netty wrapper have debug messages.
        Logger nettyWrapperLogger = (Logger) LoggerFactory.getLogger("io.netty.wrapper");
        nettyWrapperLogger.setLevel(Level.DEBUG);


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
    void stopEndPoints(int stopAfterMillis) {
        if (stopAfterMillis > 0) {
            if (this.timer == null) {
                this.timer = new Timer("UnitTest timeout timer");
            }

            // We have to ALWAYS run this in a new timer, BECAUSE if stopEndPoints() is called from a client/server thread, it will DEADLOCK
            this.timer.schedule(new TimerTask() {
                @Override
                public
                void run() {
                    synchronized (BaseTest.this.endPoints) {
                        for (EndPoint endPoint : BaseTest.this.endPoints) {
                            endPoint.stop();
                            endPoint.waitForShutdown();
                        }
                        BaseTest.this.endPoints.clear();
                    }
                    if (BaseTest.this.timer != null) {
                        BaseTest.this.timer.cancel();
                        BaseTest.this.timer.purge();
                        BaseTest.this.timer = null;
                    }
                }
            }, stopAfterMillis);
        }
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
    void waitForThreads0(int stopAfterMillis) {
        this.fail_check = false;

        TimerTask failTask = null;

        if (stopAfterMillis > 0L) {
            stopEndPoints(stopAfterMillis);

            failTask = new TimerTask() {
                @Override
                public
                void run() {
                    BaseTest.this.fail_check = true;
                }
            };
            this.timer.schedule(failTask, stopAfterMillis + 10000L);
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

        if (failTask != null) {
            failTask.cancel();
        }

        if (this.fail_check) {
            fail("Test did not complete in a timely manner.");
        }

        // Give sockets a chance to close before starting the next test.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
        }
    }
}
