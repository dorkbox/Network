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

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.Listener;
import dorkbox.network.connection.ListenerBridge;
import dorkbox.network.rmi.RmiBridge;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;
import org.junit.Test;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public
class ListenerTest extends BaseTest {

    private final String origString = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"; // lots of a's to encourage compression
    private final int limit = 20;
    private AtomicInteger count = new AtomicInteger(0);

    AtomicBoolean checkFail1 = new AtomicBoolean(false);
    AtomicBoolean checkFail2 = new AtomicBoolean(false);

    AtomicBoolean check1 = new AtomicBoolean(false);
    AtomicBoolean check2 = new AtomicBoolean(false);
    AtomicBoolean check3 = new AtomicBoolean(false);
    AtomicBoolean check4 = new AtomicBoolean(false);
    AtomicBoolean check5 = new AtomicBoolean(false);
    AtomicBoolean check6 = new AtomicBoolean(false);
    AtomicBoolean check7 = new AtomicBoolean(false);
    AtomicBoolean check8 = new AtomicBoolean(false);
    AtomicBoolean check9 = new AtomicBoolean(false);


    // quick and dirty test to also test connection sub-classing
    class TestConnectionA extends ConnectionImpl {
        public
        TestConnectionA(final Logger logger, final EndPoint endPoint, final RmiBridge rmiBridge) {
            super(logger, endPoint, rmiBridge);
        }

        public
        void check() {
            ListenerTest.this.check1.set(true);
        }
    }


    class TestConnectionB extends TestConnectionA {
        public
        TestConnectionB(final Logger logger, final EndPoint endPoint, final RmiBridge rmiBridge) {
            super(logger, endPoint, rmiBridge);
        }

        @Override
        public
        void check() {
            ListenerTest.this.checkFail1.set(true);
        }
    }

    abstract class SubListener implements Listener.OnMessageReceived<Connection, String> {
    }

    abstract class SubListener2 extends SubListener {
    }

    abstract class SubListener3 implements Listener.OnMessageReceived<Connection, String>, Listener.SelfDefinedType {
    }


    @SuppressWarnings("rawtypes")
    @Test
    public
    void listener() throws SecurityException, InitializationException, IOException, InterruptedException {
        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.host = host;

        Server server = new Server(configuration) {
            @Override
            public
            TestConnectionA newConnection(final Logger logger, final EndPoint endPoint, final RmiBridge rmiBridge) {
                return new TestConnectionA(logger, endPoint, rmiBridge);
            }
        };

        addEndPoint(server);
        server.bind(false);
        final ListenerBridge listeners = server.listeners();

        // standard listener
        listeners.add(new Listener.OnMessageReceived<TestConnectionA, String>() {
            @Override
            public
            void received(TestConnectionA connection, String string) {
                connection.check();
                connection.send()
                          .TCP(string);
            }
        });

        // standard listener with connection subclassed
        listeners.add(new Listener.OnMessageReceived<Connection, String>() {
            @Override
            public
            void received(Connection connection, String string) {
                ListenerTest.this.check2.set(true);
            }
        });

        // standard listener with message subclassed
        listeners.add(new Listener.OnMessageReceived<TestConnectionA, Object>() {
            @Override
            public
            void received(TestConnectionA connection, Object string) {
                ListenerTest.this.check3.set(true);
            }
        });

        // standard listener with connection subclassed AND message subclassed
        listeners.add(new Listener.OnMessageReceived<Connection, Object>() {
            @Override
            public
            void received(Connection connection, Object string) {
                ListenerTest.this.check4.set(true);
            }
        });

        // standard listener with connection subclassed AND message subclassed NO GENERICS
        listeners.add(new Listener.OnMessageReceived() {
            @Override
            public
            void received(Connection connection, Object string) {
                ListenerTest.this.check5.set(true);
            }
        });

        // subclassed listener with connection subclassed AND message subclassed NO GENERICS
        listeners.add(new SubListener() {
            @Override
            public
            void received(Connection connection, String string) {
                ListenerTest.this.check6.set(true);
            }
        });

        // subclassed listener with connection subclassed AND message subclassed NO GENERICS
        listeners.add(new SubListener() {
            @Override
            public
            void received(Connection connection, String string) {
                ListenerTest.this.check6.set(true);
            }
        });


        // subclassed listener with connection subclassed x 2 AND message subclassed NO GENERICS
        listeners.add(new SubListener2() {
            @Override
            public
            void received(Connection connection, String string) {
                ListenerTest.this.check8.set(true);
            }
        });


        // subclassed listener with connection subclassed AND message subclassed NO GENERICS
        listeners.add(new SubListener3() {
            @Override
            public
            Class<?> getType() {
                return String.class;
            }

            @Override
            public
            void received(Connection connection, String string) {
                ListenerTest.this.check9.set(true);
            }
        });


        // standard listener disconnect check
        listeners.add(new Listener.OnDisconnected<Connection>() {
            @Override
            public
            void disconnected(Connection connection) {
                ListenerTest.this.check7.set(true);
            }
        });


        // should not let this happen!
        try {
            listeners.add(new Listener.OnMessageReceived<TestConnectionB, String>() {
                @Override
                public
                void received(TestConnectionB connection, String string) {
                    connection.check();
                    System.err.println(string);
                    connection.send()
                              .TCP(string);
                }
            });
            fail("Should not be able to ADD listeners that are NOT the basetype or the interface");
        } catch (Exception e) {
            System.err.println("Successfully did NOT add listener that was not the base class");
        }


        // ----

        Client client = new Client(configuration);

        addEndPoint(client);
        client.listeners()
              .add(new Listener.OnConnected<Connection>() {
                  @Override
                  public
                  void connected(Connection connection) {
                      connection.send()
                                .TCP(ListenerTest.this.origString); // 20 a's
                  }
              });

        client.listeners()
              .add(new Listener.OnMessageReceived<Connection, String>() {
                  @Override
                  public
                  void received(Connection connection, String string) {
                      if (ListenerTest.this.count.get() < ListenerTest.this.limit) {
                          ListenerTest.this.count.getAndIncrement();
                          connection.send()
                                    .TCP(string);
                      }
                      else {
                          if (!ListenerTest.this.origString.equals(string)) {
                              checkFail2.set(true);
                              System.err.println("original string not equal to the string received");
                          }
                          stopEndPoints();
                      }
                  }
              });


        client.connect(5000);

        waitForThreads();

        assertEquals(this.limit, this.count.get());

        assertTrue(this.check1.get());
        assertTrue(this.check2.get());
        assertTrue(this.check3.get());
        assertTrue(this.check4.get());
        assertTrue(this.check5.get());
        assertTrue(this.check6.get());
        assertTrue(this.check7.get());
        assertTrue(this.check8.get());
        assertTrue(this.check9.get());

        assertFalse(this.checkFail1.get());
        assertFalse(this.checkFail2.get());
    }
}
