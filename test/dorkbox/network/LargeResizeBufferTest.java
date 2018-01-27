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

import static org.junit.Assert.fail;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.network.serialization.Serialization;
import dorkbox.util.exceptions.SecurityException;
import dorkbox.util.serialization.SerializationManager;

public
class LargeResizeBufferTest extends BaseTest {
    private static final int OBJ_SIZE = 1024 * 100;

    private volatile int finalCheckAmount = 0;
    private volatile int serverCheck = -1;
    private volatile int clientCheck = -1;

    @Test
    public
    void manyLargeMessages() throws SecurityException, IOException {
        final int messageCount = 1024;

        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        configuration.udpPort = udpPort;
        configuration.host = host;
        configuration.serialization = Serialization.DEFAULT();
        register(configuration.serialization);

        Server server = new Server(configuration);
        addEndPoint(server);
        server.bind(false);

        server.listeners()
              .add(new Listener.OnMessageReceived<Connection, LargeMessage>() {
                  AtomicInteger received = new AtomicInteger();
                  AtomicInteger receivedBytes = new AtomicInteger();

                  @Override
                  public
                  void received(Connection connection, LargeMessage object) {
                      // System.err.println("Server ack message: " + received.get());

                      connection.send()
                                .TCP(object);
                      this.receivedBytes.addAndGet(object.bytes.length);

                      if (this.received.incrementAndGet() == messageCount) {
                          System.out.println("Server received all " + messageCount + " messages!");
                          System.out.println("Server received and sent " + this.receivedBytes.get() + " bytes.");
                          LargeResizeBufferTest.this.serverCheck = LargeResizeBufferTest.this.finalCheckAmount - this.receivedBytes.get();
                          System.out.println("Server missed " + LargeResizeBufferTest.this.serverCheck + " bytes.");
                          stopEndPoints();
                      }
                  }
              });

        Client client = new Client(configuration);
        addEndPoint(client);

        client.listeners()
              .add(new Listener.OnMessageReceived<Connection, LargeMessage>() {
                  AtomicInteger received = new AtomicInteger();
                  AtomicInteger receivedBytes = new AtomicInteger();

                  @Override
                  public
                  void received(Connection connection, LargeMessage object) {
                      this.receivedBytes.addAndGet(object.bytes.length);

                      int count = this.received.getAndIncrement();
                      // System.out.println("Client received message: " + count);

                      if (count == messageCount) {
                          System.out.println("Client received all " + messageCount + " messages!");
                          System.out.println("Client received and sent " + this.receivedBytes.get() + " bytes.");
                          LargeResizeBufferTest.this.clientCheck = LargeResizeBufferTest.this.finalCheckAmount - this.receivedBytes.get();
                          System.out.println("Client missed " + LargeResizeBufferTest.this.clientCheck + " bytes.");
                      }
                  }
              });
        client.connect(5000);

        SecureRandom random = new SecureRandom();

        System.err.println("  Client sending " + messageCount + " messages");
        for (int i = 0; i < messageCount; i++) {
            this.finalCheckAmount += OBJ_SIZE; // keep increasing size

            byte[] b = new byte[OBJ_SIZE];
            random.nextBytes(b);

            // set some of the bytes to be all `244`, just so some compression can occur (to test that as well)
            for (int j = 0; j < 400; j++) {
                b[j] = (byte) 244;
            }

//            System.err.println("Sending " + b.length + " bytes");
            client.send()
                  .TCP(new LargeMessage(b));
        }

        System.err.println("Client has queued " + messageCount + " messages.");

        waitForThreads();

        if (this.clientCheck > 0) {
            fail("Client missed " + this.clientCheck + " bytes.");
        }

        if (this.serverCheck > 0) {
            fail("Server missed " + this.serverCheck + " bytes.");
        }
    }

    private
    void register(SerializationManager manager) {
        manager.register(byte[].class);
        manager.register(LargeMessage.class);
    }

    public static
    class LargeMessage {
        public byte[] bytes;

        public
        LargeMessage() {
        }

        public
        LargeMessage(byte[] bytes) {
            this.bytes = bytes;
        }
    }
}
