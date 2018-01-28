/*
 * Copyright 2018 dorkbox, llc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dorkbox.network.rmi;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import dorkbox.network.BaseTest;
import dorkbox.network.Client;
import dorkbox.network.Configuration;
import dorkbox.network.Server;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.Listener;
import dorkbox.network.serialization.Serialization;
import dorkbox.util.exceptions.SecurityException;

public
class RmiObjectIdExhaustionTest extends BaseTest {

    // start at 1 because we are testing connection local RMI objects, which start at 1
    private AtomicInteger objectCounter = new AtomicInteger(1);

    public static
    void register(dorkbox.network.serialization.CryptoSerializationManager manager) {
        manager.register(Object.class); // Needed for Object#toString, hashCode, etc.
        manager.register(MessageWithTestCow.class);
        manager.register(UnsupportedOperationException.class);
    }

    // @Test // NOTE: change final to test!
    public
    void rmiNetwork() throws SecurityException, IOException, InterruptedException {
        rmi(new Config() {
            @Override
            public
            void apply(final Configuration configuration) {
                configuration.tcpPort = tcpPort;
                configuration.udpPort = udpPort;
                configuration.host = host;
            }
        });

        // have to reset the object ID counter
        TestCowImpl.ID_COUNTER.set(1);

        Thread.sleep(2000L);
    }

    // @Test // NOTE: change final to test!
    public
    void rmiLocal() throws SecurityException, IOException, InterruptedException {
        rmi(new Config() {
            @Override
            public
            void apply(final Configuration configuration) {
                configuration.localChannelName = EndPoint.LOCAL_CHANNEL;
            }
        });

        // have to reset the object ID counter
        TestCowImpl.ID_COUNTER.set(1);

        Thread.sleep(2000L);
    }


    public
    void rmi(final Config config) throws SecurityException, IOException {
        // NOTE: change final to test!
        // RmiBridge.INVALID_RMI = 4;

        Configuration configuration = new Configuration();
        config.apply(configuration);

        configuration.serialization = Serialization.DEFAULT();
        register(configuration.serialization);

        // for Client -> Server RMI (ID 1)
        configuration.serialization.registerRmiImplementation(TestCow.class, TestCowImpl.class);

        // for Server -> Client RMI (ID 2)
        configuration.serialization.registerRmiInterface(TestCow.class);


        final Server server = new Server(configuration);
        server.setIdleTimeout(0);

        addEndPoint(server);
        server.bind(false);

        // ----
        configuration = new Configuration();
        config.apply(configuration);

        configuration.serialization = Serialization.DEFAULT();
        register(configuration.serialization);

        // for Client -> Server RMI (ID 1)
        configuration.serialization.registerRmiInterface(TestCow.class);

        // for Server -> Client RMI (ID 2)
        configuration.serialization.registerRmiImplementation(TestCow.class, TestCowImpl.class);


        final Client client = new Client(configuration);
        client.setIdleTimeout(0);

        addEndPoint(client);


        client.listeners().add(new Listener.OnConnected<Connection>() {
            @Override
            public
            void connected(final Connection connection) {
                iterate(objectCounter.getAndAdd(2), connection);
            }
        });

        client.listeners()
              .add(new Listener.OnMessageReceived<Connection, MessageWithTestCow>() {
                  @Override
                  public
                  void received(Connection connection, MessageWithTestCow m) {

                  }
              });

        client.connect(5000);

        waitForThreads();
    }

    private void iterate(final int currentCount, final Connection connection) {
        // if this is called in the dispatch thread, it will block network comms while waiting for a response and it won't work...
        connection.createRemoteObject(TestCow.class, new RemoteObjectCallback<TestCow>() {
            @Override
            public
            void created(final TestCow remoteObject) {
                if (remoteObject != null) {
                    System.out.println("valid ID " + currentCount);

                    iterate(objectCounter.getAndAdd(2), connection);
                }
                else {
                    System.out.println("invalid ID " + currentCount);
                    stopEndPoints();
                }
            }
        });
    }
}
