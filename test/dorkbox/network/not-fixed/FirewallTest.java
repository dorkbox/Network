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

import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.network.serialization.Serialization;
import dorkbox.util.exceptions.SecurityException;
import dorkbox.util.serialization.SerializationManager;

public
class FirewallTest extends BaseTest {

    private AtomicBoolean checkPassed = new AtomicBoolean(false);

    @Test
    public
    void sendDataFromClientClass() throws SecurityException, IOException {
        Configuration configuration = new Configuration();
        configuration.tcpPort = tcpPort;
        // configuration.udpPort = udpPort;
        configuration.host = host;
        configuration.serialization = Serialization.DEFAULT();
        register(configuration.serialization);

        Server server = new Server(configuration);

        // server.addIpFilter(Server.permitLocalHostRule);
        // server.addIpFilter(new IpSubnetFilterRule(NetUtil.LOCALHOST, 32, IpFilterRuleType.REJECT));

        // server.addConnectionTypeFilter(new ConnectionRule(NetUtil.LOCALHOST, 32, ConnectionType.COMPRESS_AND_ENCRYPT));

        addEndPoint(server);
        server.bind(false);

        server.listeners()
              .add(new Listener.OnMessageReceived<Connection, AMessage>() {
                  @Override
                  public
                  void received(Connection connection, AMessage object) {
                      System.err.println("Server received message from client. Bouncing back.");
                      connection.send(object);
                  }
              });

        Client client = new Client(configuration);
        addEndPoint(client);
        client.connect(5000);

        client.listeners()
              .add(new Listener.OnMessageReceived<Connection, AMessage>() {
                  @Override
                  public
                  void received(Connection connection, AMessage object) {
                      FirewallTest.this.checkPassed.set(true);
                      stopEndPoints();
                  }
              });

        client.send(new AMessage());

        waitForThreads();

        if (!this.checkPassed.get()) {
            fail("Client and server failed to send messages!");
        }
    }

    private static
    void register(SerializationManager manager) {
        manager.register(AMessage.class);
    }

    public static
    class AMessage {
        public
        AMessage() {
        }
    }
}
