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
import dorkbox.network.connection.KryoCryptoSerializationManager;
import dorkbox.network.connection.Listener;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.fail;

public
class MultipleServerTest extends BaseTest {
    AtomicInteger received = new AtomicInteger();

    @Test
    public
    void multipleServers() throws InitializationException, SecurityException, IOException, InterruptedException {
        KryoCryptoSerializationManager.DEFAULT = KryoCryptoSerializationManager.DEFAULT();
        KryoCryptoSerializationManager.DEFAULT.register(String[].class);

        Configuration configuration1 = new Configuration();
        configuration1.tcpPort = tcpPort;
        configuration1.udpPort = udpPort;
        configuration1.localChannelName = "chan1";

        Server server1 = new Server(configuration1);
        addEndPoint(server1);

        server1.bind(false);
        server1.listeners()
               .add(new Listener.OnMessageReceived<Connection, String>() {
                   @Override
                   public
                   void received(Connection connection, String object) {
                       if (!object.equals("client1")) {
                           fail();
                       }
                       if (MultipleServerTest.this.received.incrementAndGet() == 2) {
                           stopEndPoints();
                       }
                   }
               });

        Configuration configuration2 = new Configuration();
        configuration2.tcpPort = tcpPort + 1;
        configuration2.udpPort = udpPort + 1;
        configuration2.localChannelName = "chan2";

        Server server2 = new Server(configuration2);

        addEndPoint(server2);
        server2.bind(false);
        server2.listeners()
               .add(new Listener.OnMessageReceived<Connection, String>() {
                   @Override
                   public
                   void received(Connection connection, String object) {
                       if (!object.equals("client2")) {
                           fail();
                       }
                       if (MultipleServerTest.this.received.incrementAndGet() == 2) {
                           stopEndPoints();
                       }
                   }
               });

        // ----

        configuration1.localChannelName = null;
        configuration1.host = host;

        Client client1 = new Client(configuration1);
        addEndPoint(client1);
        client1.listeners()
               .add(new Listener.OnConnected<Connection>() {
                   @Override
                   public
                   void connected(Connection connection) {
                       connection.send()
                                 .TCP("client1");
                   }
               });
        client1.connect(5000);


        configuration2.localChannelName = null;
        configuration2.host = host;

        Client client2 = new Client(configuration2);
        addEndPoint(client2);
        client2.listeners()
               .add(new Listener.OnConnected<Connection>() {
                   @Override
                   public
                   void connected(Connection connection) {
                       connection.send()
                                 .TCP("client2");
                   }
               });
        client2.connect(5000);

        waitForThreads(30);
    }
}
