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

import dorkbox.network.BaseTest;
import dorkbox.network.Client;
import dorkbox.network.Configuration;
import dorkbox.network.Server;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.KryoCryptoSerializationManager;
import dorkbox.network.connection.Listener;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;
import dorkbox.util.serialization.IgnoreSerialization;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@SuppressWarnings("Duplicates")
public
class RmiSendObjectOverrideMethodTest extends BaseTest {

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
     // In situations where we want to pass in the Connection (to an RMI method), we have to be able to override method A, with method B.
     // This is to support calling RMI methods from an interface (that does pass the connection reference) to
     // an implType, that DOES pass the connection reference. The remote side (that initiates the RMI calls), MUST use
     // the interface, and the implType may override the method, so that we add the connection as the first in
     // the list of parameters.
     //
     // for example:
     // Interface: foo(String x)
     //      Impl: foo(Connection c, String x)
     //
     // The implType (if it exists, with the same name, and with the same signature+connection) will be called from the interface.
     // This MUST hold valid for both remote and local connection types.

     // To facilitate this functionality, for methods with the same name, the "overriding" method is the one that inherits the Connection
     // interface as the first parameter, and  .registerRemote(ifaceClass, implClass)  must be called.
     */
    @Test
    public
    void rmi() throws InitializationException, SecurityException, IOException, InterruptedException {
        KryoCryptoSerializationManager.DEFAULT = KryoCryptoSerializationManager.DEFAULT();
        KryoCryptoSerializationManager.DEFAULT.registerRemote(TestObject.class, TestObjectImpl.class);
        KryoCryptoSerializationManager.DEFAULT.registerRemote(OtherObject.class, OtherObjectImpl.class);



        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.host = host;
        configuration.rmiEnabled = true;

        Server server = new Server(configuration);
        server.disableRemoteKeyValidation();
        server.setIdleTimeout(0);


        addEndPoint(server);
        server.bind(false);


        server.listeners()
              .add(new Listener<OtherObjectImpl>() {
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
        Client client = new Client(configuration);
        client.disableRemoteKeyValidation();
        client.setIdleTimeout(0);

        addEndPoint(client);
        client.listeners()
              .add(new Listener<Object>() {
                  @Override
                  public
                  void connected(final Connection connection) {
                      new Thread(new Runnable() {
                          @Override
                          public
                          void run() {


                              TestObject test = null;
                              try {
                                  test = connection.createProxyObject(TestObjectImpl.class);

                                  test.setOther(43.21f);
                                  // Normal remote method call.
                                  assertEquals(43.21f, test.other(), .0001f);

                                  // Make a remote method call that returns another remote proxy object.
                                  // the "test" object exists in the REMOTE side, as does the "OtherObject" that is created.
                                  //  here we have a proxy to both of them.
                                  OtherObject otherObject = test.getOtherObject();

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
                              } catch (IOException e) {
                                  e.printStackTrace();
                                  fail();
                              }
                          }
                      }).start();
                  }
              });

        client.connect(5000);

        waitForThreads();
    }

    public
    interface TestObject {
        void setOther(float aFloat);

        float other();

        OtherObject getOtherObject();
    }


    public
    interface OtherObject {
        void setValue(float aFloat);
        float value();
    }


    private static final AtomicInteger idCounter = new AtomicInteger();


    public static
    class TestObjectImpl implements TestObject {
        @IgnoreSerialization
        private final int ID = idCounter.getAndIncrement();

        @RMI
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


    public static
    class OtherObjectImpl implements OtherObject {
        @IgnoreSerialization
        private final int ID = idCounter.getAndIncrement();

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
