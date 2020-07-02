/*
 * Copyright 2019 dorkbox, llc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import dorkbox.network.Client;
import dorkbox.network.Configuration;
import dorkbox.network.connection.Connection;
import dorkbox.network.rmi.RemoteObjectCallback;
import dorkbox.network.rmi.RmiTest;
import dorkbox.network.rmi.TestCow;
import dorkbox.network.serialization.Serialization;
import io.netty.util.ResourceLeakDetector;

/**
 *
 */
public
class TestClient
{
    public static void setup() {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);

        // assume SLF4J is bound to logback in the current environment
        Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        LoggerContext context = rootLogger.getLoggerContext();

        JoranConfigurator jc = new JoranConfigurator();
        jc.setContext(context);
        context.reset(); // override default configuration

//        rootLogger.setLevel(Level.OFF);

        // rootLogger.setLevel(Level.DEBUG);
       rootLogger.setLevel(Level.TRACE);
//        rootLogger.setLevel(Level.ALL);


        // we only want error messages
        Logger nettyLogger = (Logger) LoggerFactory.getLogger("io.netty");
        nettyLogger.setLevel(Level.ERROR);

        // we only want error messages
        Logger kryoLogger = (Logger) LoggerFactory.getLogger("com.esotericsoftware");
        kryoLogger.setLevel(Level.ERROR);

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


    public static
    void main(String[] args) {
        setup();

        Configuration configuration = new Configuration();
        configuration.tcpPort = 2000;
        configuration.udpPort = 2001;
        configuration.host = "localhost";

        configuration.serialization = Serialization.DEFAULT();
        RmiTest.register(configuration.serialization);

        try {
            final Client client = new Client(configuration);
            client.disableRemoteKeyValidation();
            client.setIdleTimeout(0);

            client.listeners()
                  .add(new dorkbox.network.connection.Listener.OnConnected<Connection>() {
                      @Override
                      public
                      void connected(final Connection connection) {
                          System.err.println("Starting test for: Client -> Server");

                          // if this is called in the dispatch thread, it will block network comms while waiting for a response and it won't work...
                          connection.createRemoteObject(TestCow.class, new RemoteObjectCallback<TestCow>() {
                              @Override
                              public
                              void created(final TestCow remoteObject) {
                                  // MUST run on a separate thread because remote object method invocations are blocking
                                  new Thread() {
                                      @Override
                                      public
                                      void run() {
                                          RmiTest.runTests(connection, remoteObject, 1);

                                          try {
                                              Thread.sleep(1000L);
                                          } catch (InterruptedException e) {
                                              e.printStackTrace();
                                          }

                                          System.err.println("DONE");
                                          client.stop();
                                      }
                                  }.start();
                              }
                          });
                      }
                  });

            client.connect(3000);
            client.waitForShutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
