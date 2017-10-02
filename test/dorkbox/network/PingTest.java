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

import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.Test;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Ping;
import dorkbox.network.connection.PingListener;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;

public
class PingTest extends BaseTest {

    private volatile int response = -1;

    // ping prefers the following order:  UDP, TCP
    @Test
    public
    void pingTCP() throws InitializationException, SecurityException, IOException, InterruptedException {
        this.response = -1;

        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.host = host;

        Server server = new Server(configuration);
        addEndPoint(server);
        server.bind(false);

        // ----

        Client client = new Client(configuration);
        addEndPoint(client);

        client.connect(5000);

        System.err.println("Testing TCP ping");
        for (int i = 0; i < 10; i++) {
            this.response = client.send()
                                  .ping()
                                  .getResponse();
            System.err.println("Ping: " + this.response);
        }

        stopEndPoints();
        if (this.response == -1) {
            fail();
        }
    }

    @Test
    public
    void pingTCP_testListeners1() throws InitializationException, SecurityException, IOException, InterruptedException {
        this.response = -1;

        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.host = host;

        Server server = new Server(configuration);
        addEndPoint(server);
        server.bind(false);

        // ----

        Client client = new Client(configuration);
        addEndPoint(client);

        client.connect(5000);

        System.err.println("Testing TCP ping with multi callback");

        final PingListener<Connection> pingListener = new PingListener<Connection>() {
            volatile int count = 0;

            @Override
            public
            void response(Connection connection, int pingResponseTime) {
                System.err.println("Ping: " + pingResponseTime);

                if (this.count++ < 10) {
                    connection.send()
                              .ping()
                              .addListener(this);
                }
                else {
                    PingTest.this.response = pingResponseTime;
                    stopEndPoints();
                }
            }
        };

        //  alternate way to register for the receipt of a one-off ping response
        // doesn't matter how many times this is called. If there is a PING waiting, then it's overwritten
        Ping ping = client.send()
                          .ping();
        ping.addListener(pingListener);

        waitForThreads();

        if (this.response == -1) {
            fail();
        }
    }

    @Test
    public
    void pingTCP_testListeners2() throws InitializationException, SecurityException, IOException, InterruptedException {
        this.response = -1;

        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.host = host;

        Server server = new Server(configuration);
        addEndPoint(server);
        server.bind(false);

        // ----

        Client client = new Client(configuration);
        addEndPoint(client);

        client.connect(5000);

        System.err.println("Testing TCP ping with single callback");

        final PingListener<Connection> pingListener = new PingListener<Connection>() {
            @Override
            public
            void response(Connection connection, int pingResponseTime) {
                System.err.println("Ping: " + pingResponseTime);
                PingTest.this.response = pingResponseTime;
                stopEndPoints();
            }
        };


        //  alternate way to register for the receipt of a one-off ping response
        // doesn't matter how many times this is called. If there is a PING waiting, then it's overwritten
        Ping ping = client.send()
                          .ping();
        ping.addListener(pingListener);

        waitForThreads();
        if (this.response == -1) {
            fail();
        }
    }

    // ping prefers the following order:  UDP, TCP
    @Test
    public
    void pingUDP() throws InitializationException, SecurityException, IOException, InterruptedException {
        this.response = -1;

        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.udpPort = udpPort;
        configuration.host = host;


        Server server = new Server(configuration);
        addEndPoint(server);
        server.bind(false);

        // ----

        Client client = new Client(configuration);
        addEndPoint(client);

        client.connect(5000);

        System.err.println("Testing UDP ping");
        for (int i = 0; i < 10; i++) {
            this.response = client.send()
                                  .ping()
                                  .getResponse();
            System.err.println("Ping: " + this.response);
        }

        stopEndPoints();

        if (this.response == -1) {
            fail();
        }
    }
}
