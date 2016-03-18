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

import dorkbox.network.connection.*;
import dorkbox.network.rmi.RmiBridge;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;
import org.junit.Test;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public
class ListenerTest extends BaseTest {

    private final String origString = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"; // lots of a's to encourage compression
    private final int limit = 20;
    private AtomicInteger count = new AtomicInteger(0);

    volatile String fail = null;
    AtomicBoolean subClassWorkedOK = new AtomicBoolean(false);
    AtomicBoolean subClassWorkedOK2 = new AtomicBoolean(false);
    AtomicBoolean superClassWorkedOK = new AtomicBoolean(false);
    AtomicBoolean superClass2WorkedOK = new AtomicBoolean(false);
    AtomicBoolean disconnectWorkedOK = new AtomicBoolean(false);


    // quick and dirty test to also test connection sub-classing
    class TestConnectionA extends ConnectionImpl {
        public
        TestConnectionA(final Logger logger, final EndPoint endPoint, final RmiBridge rmiBridge) {
            super(logger, endPoint, rmiBridge);
        }

        public
        void check() {
            ListenerTest.this.subClassWorkedOK.set(true);
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
            ListenerTest.this.subClassWorkedOK.set(true);
        }
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

        server.listeners()
              .add(new ListenerRaw<TestConnectionA, String>() {
                  @Override
                  public
                  void received(TestConnectionA connection, String string) {
                      connection.check();
//                System.err.println("default check");
                      connection.send()
                                .TCP(string);
                  }
              });

        server.listeners()
              .add(new Listener<String>() {
                  @Override
                  public
                  void received(Connection connection, String string) {
//                System.err.println("subclass check");
                      ListenerTest.this.subClassWorkedOK2.set(true);
                  }
              });

        // should be able to happen!
        server.listeners()
              .add(new Listener() {
                  @Override
                  public
                  void received(Connection connection, Object string) {
//                System.err.println("generic class check");
                      ListenerTest.this.superClassWorkedOK.set(true);
                  }
              });


        // should be able to happen!
        server.listeners()
              .add(new ListenerRaw() {
                  @Override
                  public
                  void received(Connection connection, Object string) {
//                System.err.println("generic class check");
                      ListenerTest.this.superClass2WorkedOK.set(true);
                  }
              });

        server.listeners()
              .add(new Listener() {
                  @Override
                  public
                  void disconnected(Connection connection) {
//                System.err.println("disconnect check");
                      ListenerTest.this.disconnectWorkedOK.set(true);
                  }
              });

        // should not let this happen!
        try {
            server.listeners()
                  .add(new ListenerRaw<TestConnectionB, String>() {
                      @Override
                      public
                      void received(TestConnectionB connection, String string) {
                          connection.check();
                          System.err.println(string);
                          connection.send()
                                    .TCP(string);
                      }
                  });
            this.fail = "Should not be able to ADD listeners that are NOT the basetype or the interface";
        } catch (Exception e) {
            System.err.println("Successfully did NOT add listener that was not the base class");
        }


        // ----

        Client client = new Client(configuration);

        addEndPoint(client);
        client.listeners()
              .add(new Listener<String>() {
                  @Override
                  public
                  void connected(Connection connection) {
                      connection.send()
                                .TCP(ListenerTest.this.origString); // 20 a's
                  }

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
                              ListenerTest.this.fail = "original string not equal to the string received";
                          }
                          stopEndPoints();
                      }
                  }
              });


        client.connect(5000);

        waitForThreads();
        assertEquals(this.limit, this.count.get());
        assertTrue(this.subClassWorkedOK.get());
        assertTrue(this.subClassWorkedOK2.get());
        assertTrue(this.superClassWorkedOK.get());
        assertTrue(this.superClass2WorkedOK.get());
        assertTrue(this.disconnectWorkedOK.get());

        if (this.fail != null) {
            fail(this.fail);
        }
    }
}
