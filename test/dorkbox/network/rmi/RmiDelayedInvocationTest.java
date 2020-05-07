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

import java.beans.Transient;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import dorkbox.network.BaseTest;
import dorkbox.network.Client;
import dorkbox.network.Configuration;
import dorkbox.network.Server;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.Listener;
import dorkbox.network.serialization.NetworkSerializationManager;
import dorkbox.network.serialization.Serialization;
import dorkbox.util.exceptions.SecurityException;

@SuppressWarnings("Duplicates")
public
class RmiDelayedInvocationTest extends BaseTest {

    private final Object iterateLock = new Object();

    @Test
    public
    void rmiNetwork() throws SecurityException, IOException {
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
    void rmiLocal() throws SecurityException, IOException {
        rmi(new Config() {
            @Override
            public
            void apply(final Configuration configuration) {
                configuration.localChannelName = EndPoint.LOCAL_CHANNEL;
            }
        });
    }

    void register(final NetworkSerializationManager serialization) {
        serialization.registerRmi(TestObject.class, TestObjectImpl.class);
    }

    /**
     * In this test the server has two objects in an object space. The client
     * uses the first remote object to get the second remote object.
     */
    public
    void rmi(final Config config) throws SecurityException, IOException {
        Configuration configuration = new Configuration();
        config.apply(configuration);

        configuration.serialization = Serialization.DEFAULT();
        register(configuration.serialization);


        Server server = new Server(configuration);
        server.setIdleTimeout(0);


        addEndPoint(server);
        server.bind(false);

        final int testObjectInt = server.createGlobalObject(new TestObjectImpl(iterateLock));

        // ----
        configuration = new Configuration();
        config.apply(configuration);

        configuration.serialization = Serialization.DEFAULT();
        register(configuration.serialization);


        Client client = new Client(configuration);
        client.setIdleTimeout(0);

        addEndPoint(client);
        client.listeners()
              .add(new Listener.OnConnected<Connection>() {
                  @Override
                  public
                  void connected(final Connection connection) {
                      connection.getRemoteObject(testObjectInt, new RemoteObjectCallback<TestObject>() {
                          @Override
                          public
                          void created(final TestObject remoteObject) {
                              // MUST run on a separate thread because remote object method invocations are blocking
                              new Thread() {
                                  int totalRuns = 100_000_000;
                                  @Override
                                  public
                                  void run() {
                                      System.err.println("Running for " + totalRuns + " iterations....");

                                      for (int i = 0; i < totalRuns; i++) {
                                          if (i % 10000 == 0) {
                                              System.err.println(i);
                                          }
                                          // sometimes, this method is never called right away.
                                          remoteObject.setOther(i);

                                          synchronized (iterateLock) {
                                              try {
                                                  iterateLock.wait(1000);
                                              } catch (InterruptedException e) {
                                                  System.err.println("Failed after: " + i);
                                                  e.printStackTrace();
                                                  break;
                                              }
                                          }
                                      }

                                      System.err.println("Done with delay invocation test");
                                      stopEndPoints();
                                  }
                              }.start();
                          }
                      });
                  }
              });

        client.connect(0);

        waitForThreads(9999999);
    }

    private
    interface TestObject {
        void setOther(float aFloat);
        float other();
    }


    private static final AtomicInteger idCounter = new AtomicInteger();

    private static
    class TestObjectImpl implements TestObject {
        private final transient int ID = idCounter.getAndIncrement();

        private float aFloat;
        private Object iterateLock;

        public
        TestObjectImpl(final Object iterateLock) {
            this.iterateLock = iterateLock;
        }

        @Override
        public
        void setOther(final float aFloat) {
            this.aFloat = aFloat;
            synchronized (iterateLock) {
                iterateLock.notify();
            }
        }

        @Override
        public
        float other() {
            return aFloat;
        }

        @Override
        public
        int hashCode() {
            return ID;
        }
    }
}
