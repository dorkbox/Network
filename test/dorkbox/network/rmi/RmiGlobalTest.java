/*
 * Copyright 2016 dorkbox, llc
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
 *
 * Copyright (c) 2008, Nathan Sweet
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
package dorkbox.network.rmi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

import dorkbox.network.BaseTest;
import dorkbox.network.Client;
import dorkbox.network.Configuration;
import dorkbox.network.Server;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.Listener;
import dorkbox.network.serialization.Serialization;
import dorkbox.util.exceptions.SecurityException;

public
class RmiGlobalTest extends BaseTest {

    private int CLIENT_GLOBAL_OBJECT_ID = 0;
    private int SERVER_GLOBAL_OBJECT_ID = 0;

    private final TestCow globalRemoteServerObject = new TestCowImpl();
    private final TestCow globalRemoteClientObject = new TestCowImpl();

    private static
    void runTest(final Connection connection, final TestCow globalObject, final TestCow test, final int remoteObjectID) {
        System.err.println("Starting test for: " + remoteObjectID);

        assertEquals(globalObject.hashCode(), test.hashCode());
        RemoteObject remoteObject = (RemoteObject) test;

        // Default behavior. RMI is transparent, method calls behave like normal
        // (return values and exceptions are returned, call is synchronous)
        System.err.println("hashCode: " + test.hashCode());
        System.err.println("toString: " + test);

        // see what the "remote" toString() method is
        final String s = remoteObject.toString();
        remoteObject.enableToString(true);
        assertFalse(s.equals(remoteObject.toString()));


        test.moo();
        test.moo("Cow");
        assertEquals(remoteObjectID, test.id());


        // UDP calls that ignore the return value
        remoteObject.setUDP();
        remoteObject.setAsync(true);
        remoteObject.setTransmitReturnValue(false);
        remoteObject.setTransmitExceptions(false);
        test.moo("Meow");
        assertEquals(0, test.id());
        remoteObject.setAsync(false);
        remoteObject.setTransmitReturnValue(true);
        remoteObject.setTransmitExceptions(true);
        remoteObject.setTCP();


        // Test that RMI correctly waits for the remotely invoked method to exit
        remoteObject.setResponseTimeout(5000);
        test.moo("You should see this two seconds before...", 2000);
        System.out.println("...This");
        remoteObject.setResponseTimeout(3000);

        // Try exception handling
        boolean caught = false;
        try {
            test.throwException();
        } catch (UnsupportedOperationException ex) {
            System.err.println("\tExpected exception! " + ex.getMessage());
            caught = true;
        }
        assertTrue(caught);


        // Return values are ignored, but exceptions are still dealt with properly
        remoteObject.setTransmitReturnValue(false);
        test.moo("Baa");
        test.id();
        caught = false;
        try {
            test.throwException();
        } catch (UnsupportedOperationException ex) {
            caught = true;
        }
        assertTrue(caught);

        // Non-blocking call that ignores the return value
        remoteObject.setAsync(true);
        remoteObject.setTransmitReturnValue(false);
        test.moo("Meow");
        assertEquals(0, test.id());

        // Non-blocking call that returns the return value
        remoteObject.setTransmitReturnValue(true);
        test.moo("Foo");

        assertEquals(0, test.id());

        // wait for the response to id()
        assertEquals(remoteObjectID, remoteObject.waitForLastResponse());

        assertEquals(0, test.id());
        byte responseID = remoteObject.getLastResponseID();
        // wait for the response to id()
        assertEquals(remoteObjectID, remoteObject.waitForResponse(responseID));

        // Non-blocking call that errors out
        remoteObject.setTransmitReturnValue(false);
        test.throwException();
        assertEquals(remoteObject.waitForLastResponse()
                                 .getClass(), UnsupportedOperationException.class);

        // Call will time out if non-blocking isn't working properly
        remoteObject.setTransmitExceptions(false);
        test.moo("Mooooooooo", 3000);


        // should wait for a small time
        remoteObject.setTransmitReturnValue(true);
        remoteObject.setAsync(false);
        remoteObject.setResponseTimeout(6000);
        System.out.println("You should see this 2 seconds before");
        float slow = test.slow();
        System.out.println("...This");
        assertEquals(123.0F, slow, 0.0001D);


        // Test sending a reference to a remote object (the receiving end should receive the IMPL object, not the proxy object)
        System.out.println("Sending proxied object to remote...");
        MessageWithTestCow m = new MessageWithTestCow(test);
        m.number = 678;
        m.text = "sometext";

        connection.send()
                  .TCP(m)
                  .flush();
    }



    public static
    void register(dorkbox.network.serialization.CryptoSerializationManager manager) {
        manager.register(Object.class); // Needed for Object#toString, hashCode, etc.
        manager.register(MessageWithTestCow.class);
        manager.register(UnsupportedOperationException.class);
    }

    @Test
    public
    void rmiNetwork() throws SecurityException, IOException {
        rmi(new Config() {
            @Override
            public
            void apply(final Configuration configuration) {
                configuration.tcpPort = tcpPort;
                configuration.udpPort = udpPort;
                configuration.host = host;
            }
        });
    }

    @Test
    public
    void rmiLocal() throws SecurityException, IOException {
        rmi(new Config() {
            @Override
            public
            void apply(final Configuration configuration) {
                configuration.localChannelName = EndPoint.LOCAL_CHANNEL;
            }
        });
    }

    public
    void rmi(final Config config) throws SecurityException, IOException {
        Configuration configuration = new Configuration();
        config.apply(configuration);

        configuration.serialization = Serialization.DEFAULT();
        register(configuration.serialization);

        // NOTICE: none of the super classes/interfaces are registered!
        configuration.serialization.registerRmi(TestCow.class, TestCowImpl.class);

        final Server server = new Server(configuration);
        server.setIdleTimeout(0);

        // register this object as a global object that the client will get
        SERVER_GLOBAL_OBJECT_ID = server.createGlobalObject(globalRemoteServerObject);

        addEndPoint(server);
        server.bind(false);

        server.listeners()
              .add(new Listener.OnConnected<Connection>() {
                  @Override
                  public
                  void connected(final Connection connection) {
                      connection.getRemoteObject(CLIENT_GLOBAL_OBJECT_ID, new RemoteObjectCallback<TestCow>() {
                          @Override
                          public
                          void created(final TestCow remoteObject) {
                              // MUST run on a separate thread because remote object method invocations are blocking
                              new Thread() {
                                  @Override
                                  public
                                  void run() {
                                      System.err.println("Running test for: Server (LOCAL) -> Client (REMOTE)");
                                      runTest(connection, globalRemoteClientObject, remoteObject, CLIENT_GLOBAL_OBJECT_ID);
                                      System.err.println("Done with test for: Server (LOCAL) -> Client (REMOTE)");
                                  }
                              }.start();
                          }
                      });
                  }
              });

        server.listeners()
              .add(new Listener.OnMessageReceived<Connection, MessageWithTestCow>() {
                  @Override
                  public
                  void received(Connection connection, MessageWithTestCow m) {
                      System.err.println("Received finish signal for test for: Client (LOCAL) -> Server (REMOTE)");

                      TestCow object = m.getTestCow();
                      final int id = object.id();
                      assertEquals(SERVER_GLOBAL_OBJECT_ID, id);

                      System.err.println("Finished test for: Client (LOCAL) -> Server (REMOTE)");

                      stopEndPoints(2000);
                  }
              });


        // ----
        configuration = new Configuration();
        config.apply(configuration);

        configuration.serialization = Serialization.DEFAULT();
        register(configuration.serialization);

        // NOTICE: none of the super classes/interfaces are registered!
        configuration.serialization.registerRmi(TestCow.class, TestCowImpl.class);


        final Client client = new Client(configuration);
        client.setIdleTimeout(0);


        // register this object as a global object that the server will get
        CLIENT_GLOBAL_OBJECT_ID = client.createGlobalObject(globalRemoteClientObject);

        addEndPoint(client);

        client.listeners()
              .add(new Listener.OnMessageReceived<Connection, MessageWithTestCow>() {
                  @Override
                  public
                  void received(final Connection connection, MessageWithTestCow m) {
                      System.err.println("Received finish signal for test for: Server (LOCAL) -> Client (REMOTE)");

                      // this TestCow object should be the implementation, not the proxy.
                      TestCow object = m.getTestCow();
                      final int id = object.id();
                      assertEquals(CLIENT_GLOBAL_OBJECT_ID, id);

                      System.err.println("Finished test for: Server (LOCAL) -> Client (REMOTE)");

                      // normally this is in the 'connected', but we do it here, so that it's more linear and easier to debug
                      connection.getRemoteObject(SERVER_GLOBAL_OBJECT_ID, new RemoteObjectCallback<TestCow>() {
                          @Override
                          public
                          void created(final TestCow remoteObject) {
                              // MUST run on a separate thread because remote object method invocations are blocking
                              new Thread() {
                                  @Override
                                  public
                                  void run() {
                                      System.err.println("Running test for: Client (LOCAL) -> Server (REMOTE)");
                                      runTest(connection, globalRemoteServerObject, remoteObject, SERVER_GLOBAL_OBJECT_ID);
                                      System.err.println("Done with test for: Client (LOCAL) -> Server (REMOTE)");
                                  }
                              }.start();
                          }
                      });
                  }
              });

        client.connect(5000);
        waitForThreads();
    }

    private static
    class ConnectionAware {
        private
        ConnectionImpl connection;

        public
        ConnectionImpl getConnection() {
            return connection;
        }

        public
        void setConnection(final ConnectionImpl connection) {
            this.connection = connection;
        }
    }


    private static
    class TestCowImpl extends ConnectionAware implements TestCow {
        public long value = System.currentTimeMillis();
        public int moos;
        private final int id = 0; // the RMI id should be == to this for each direction.

        public
        TestCowImpl() {
        }

        @Override
        public
        void throwException() {
            throw new UnsupportedOperationException("Why would I do that?");
        }

        @Override
        public
        void moo() {
            this.moos++;
            System.out.println("Moo!");
        }

        @Override
        public
        void moo(String value) {
            this.moos += 2;
            System.out.println("Moo: " + value);
        }

        @Override
        public
        void moo(String value, long delay) {
            this.moos += 4;
            System.out.println("Moo: " + value + " (" + delay + ")");
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        @Override
        public
        int id() {
            return id;
        }

        @Override
        public
        float slow() {
            System.out.println("Slowdown!!");
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return 123.0F;
        }
    }
}
