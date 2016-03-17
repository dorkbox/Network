/* Copyright (c) 2008, Nathan Sweet
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
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
import io.netty.util.ResourceLeakDetector;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

import static dorkbox.network.connection.EndPoint.THREADGROUP_NAME;
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
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);

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
        final String name = Thread.currentThread().getThreadGroup()
                                  .getName();

        // no need to run inside another thread if we are not inside the client/server thread
        if (!name.contains(THREADGROUP_NAME)) {
            stopEndPoints_outsideThread();
            return;
        }


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

                        stopEndPoints_outsideThread();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }, "UnitTest shutdown");

            thread.setDaemon(true);
            thread.start();
        }
    }

    private
    void stopEndPoints_outsideThread() {
        synchronized (BaseTest.this.endPoints) {
            for (EndPoint endPoint : BaseTest.this.endPoints) {
                endPoint.stop();
                endPoint.waitForShutdown();
            }
            BaseTest.this.endPoints.clear();
        }
    }


    private
    ThreadGroup getThreadGroup() {
        ThreadGroup threadGroup = Thread.currentThread()
                                        .getThreadGroup();
        final String name = threadGroup.getName();
        if (name.contains(THREADGROUP_NAME)) {
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
