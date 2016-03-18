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
class RmiSendObjectTest extends BaseTest {

    /**
     * In this test the server has two objects in an object space. The client
     * uses the first remote object to get the second remote object.
     */
    @Test
    public
    void rmi() throws InitializationException, SecurityException, IOException, InterruptedException {
        KryoCryptoSerializationManager.DEFAULT = KryoCryptoSerializationManager.DEFAULT();
        KryoCryptoSerializationManager.DEFAULT.register(TestObject.class);

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
                      if (object.value() == 12.34F) {
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
                                  assertEquals(43.21f, test.other(), 0.0001F);

                                  // Make a remote method call that returns another remote proxy object.
                                  OtherObject otherObject = test.getOtherObject();
                                  // Normal remote method call on the second object.
                                  otherObject.setValue(12.34f);
                                  float value = otherObject.value();
                                  assertEquals(12.34f, value, 0.0001F);

                                  // When a remote proxy object is sent, the other side receives its actual remote object.
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
            this.aFloat = aFloat;
        }

        @Override
        public
        float other() {
            return aFloat;
        }

        @Override
        public
        OtherObject getOtherObject() {
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