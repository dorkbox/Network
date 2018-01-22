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
 */
package dorkbox.network.rmi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import dorkbox.network.BaseTest;
import dorkbox.network.Client;
import dorkbox.network.Configuration;
import dorkbox.network.Server;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPointBase;
import dorkbox.network.connection.Listener;
import dorkbox.network.serialization.Serialization;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;

@SuppressWarnings("Duplicates")
public
class RmiSendObjectOverrideMethodTest extends BaseTest {

    @Test
    public
    void rmiNetwork() throws InitializationException, SecurityException, IOException, InterruptedException {
        rmi(new Config() {
            @Override
            public
            void apply(final Configuration configuration) {
                configuration.tcpPort = tcpPort;
                configuration.host = host;
            }
        });
    }

    @Test
    public
    void rmiLocal() throws InitializationException, SecurityException, IOException, InterruptedException {
        rmi(new Config() {
            @Override
            public
            void apply(final Configuration configuration) {
                configuration.localChannelName = EndPointBase.LOCAL_CHANNEL;
            }
        });
    }

    /**
     * In this test the server has two objects in an object space. The client
     * uses the first remote object to get the second remote object.
     *
     *
     * The MAJOR difference in this version, is that we use an interface to override the methods, so that we can have the RMI system pass
     * in the connection object.
     *
     * Specifically, from CachedMethod.java
     *
     * In situations where we want to pass in the Connection (to an RMI method), we have to be able to override method A, with method B.
     * This is to support calling RMI methods from an interface (that does pass the connection reference) to
     * an implType, that DOES pass the connection reference. The remote side (that initiates the RMI calls), MUST use
     * the interface, and the implType may override the method, so that we add the connection as the first in
     * the list of parameters.
     *
     * for example:
     * Interface: foo(String x)
     *      Impl: foo(Connection c, String x)
     *
     * The implType (if it exists, with the same name, and with the same signature + connection parameter) will be called from the interface
     * instead of the method that would NORMALLY be called.
     */
    public
    void rmi(final Config config) throws InitializationException, SecurityException, IOException, InterruptedException {
        Configuration configuration = new Configuration();
        config.apply(configuration);

        configuration.serialization = Serialization.DEFAULT();
        configuration.serialization.registerRmiImplementation(TestObject.class, TestObjectImpl.class);
        configuration.serialization.registerRmiImplementation(OtherObject.class, OtherObjectImpl.class);

        Server server = new Server(configuration);
        server.setIdleTimeout(0);


        addEndPoint(server);
        server.bind(false);

        server.listeners()
              .add(new Listener.OnMessageReceived<Connection, OtherObjectImpl>() {
                  @Override
                  public
                  void received(Connection connection, OtherObjectImpl object) {
                      // The test is complete when the client sends the OtherObject instance.

                      // this 'object' is the REAL object, not a proxy, because this object is created within this connection.
                      if (object.value() == 12.34f) {
                          stopEndPoints();
                      } else {
                          fail("Incorrect object value");
                      }
                  }
              });


        // ----
        configuration = new Configuration();
        config.apply(configuration);

        configuration.serialization = Serialization.DEFAULT();
        configuration.serialization.registerRmiInterface(TestObject.class);
        configuration.serialization.registerRmiInterface(OtherObject.class);

        Client client = new Client(configuration);
        client.setIdleTimeout(0);

        addEndPoint(client);
        client.listeners()
              .add(new Listener.OnConnected<Connection>() {
                  @Override
                  public
                  void connected(final Connection connection) {
                      // if this is called in the dispatch thread, it will block network comms while waiting for a response and it won't work...
                      connection.createRemoteObject(TestObject.class, new RemoteObjectCallback<TestObject>() {
                          @Override
                          public
                          void created(final TestObject remoteObject) {
                              // MUST run on a separate thread because remote object method invocations are blocking
                              new Thread() {
                                  @Override
                                  public
                                  void run() {
                                      remoteObject.setOther(43.21f);

                                      // Normal remote method call.
                                      assertEquals(43.21f, remoteObject.other(), .0001f);

                                      // Make a remote method call that returns another remote proxy object.
                                      // the "test" object exists in the REMOTE side, as does the "OtherObject" that is created.
                                      //  here we have a proxy to both of them.
                                      OtherObject otherObject = remoteObject.getOtherObject();

                                      // Normal remote method call on the second object.
                                      otherObject.setValue(12.34f);

                                      float value = otherObject.value();
                                      assertEquals(12.34f, value, .0001f);

                                      // When a proxy object is sent, the other side receives its ACTUAL object (not a proxy of it), because
                                      // that is where that object acutally exists.
                                      // we have to manually flush, since we are in a separate thread that does not auto-flush.
                                      connection.send()
                                                .TCP(otherObject)
                                                .flush();
                                  }
                              }.start();
                          }
                      });
                  }
              });

        client.connect(5000);

        waitForThreads();
    }

    private
    interface TestObject {
        void setOther(float aFloat);

        float other();

        OtherObject getOtherObject();
    }


    private
    interface OtherObject {
        void setValue(float aFloat);
        float value();
    }


    private static final AtomicInteger idCounter = new AtomicInteger();


    private static
    class TestObjectImpl implements TestObject {
        private final transient int ID = idCounter.getAndIncrement();

        @Rmi
        private final OtherObject otherObject = new OtherObjectImpl();
        private float aFloat;


        @Override
        public
        void setOther(final float aFloat) {
            throw new RuntimeException("Whoops!");
        }

        public
        void setOther(Connection connection, final float aFloat) {
            this.aFloat = aFloat;
        }

        @Override
        public
        float other() {
            throw new RuntimeException("Whoops!");
        }

        public
        float other(Connection connection) {
            return aFloat;
        }

        @Override
        public
        OtherObject getOtherObject() {
            throw new RuntimeException("Whoops!");
        }

        public
        OtherObject getOtherObject(Connection connection) {
            return this.otherObject;
        }

        @Override
        public
        int hashCode() {
            return ID;
        }
    }


    private static
    class OtherObjectImpl implements OtherObject {
        private final transient int ID = idCounter.getAndIncrement();

        private float aFloat;

        @Override
        public
        void setValue(final float aFloat) {
            this.aFloat = aFloat;
        }

        @Override
        public
        float value() {
            return aFloat;
        }

        @Override
        public
        int hashCode() {
            return ID;
        }
    }
}
