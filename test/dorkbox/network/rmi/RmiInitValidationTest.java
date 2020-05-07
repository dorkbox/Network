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
import static org.junit.Assert.fail;

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
class RmiInitValidationTest extends BaseTest {

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
        serialization.register(Command1.class);
        serialization.register(Command2.class);
        serialization.register(Command3.class);
        serialization.register(Command4.class);
        serialization.register(Command5.class);
        serialization.register(Command6.class);
        serialization.register(Command7.class);
        serialization.register(Command8.class);
        serialization.register(Command9.class);
        serialization.register(Command10.class);
        serialization.register(Command20.class);
        serialization.register(Command30.class);
        serialization.register(Command40.class);
        serialization.register(Command50.class);
        serialization.register(Command60.class);
        serialization.register(Command70.class);
        serialization.register(Command80.class);
        serialization.register(Command90.class);
        serialization.register(Command11.class);
        serialization.register(Command12.class);
        serialization.register(Command13.class);
        serialization.register(Command14.class);
        serialization.register(Command15.class);
        serialization.register(Command16.class);
        serialization.register(Command17.class);
        serialization.register(Command18.class);
        serialization.register(Command19.class);
        serialization.register(Command21.class);
        serialization.register(Command22.class);
        serialization.register(Command23.class);
        serialization.register(Command24.class);
        serialization.register(Command25.class);
        serialization.register(Command26.class);
        serialization.register(Command27.class);
        serialization.register(Command28.class);
        serialization.register(Command29.class);
        serialization.register(Command31.class);
        serialization.register(Command32.class);
        serialization.register(Command33.class);
        serialization.register(Command34.class);
        serialization.register(Command35.class);
        serialization.register(Command36.class);
        serialization.register(Command37.class);
        serialization.register(Command38.class);
        serialization.register(Command39.class);
        serialization.register(Command41.class);
        serialization.register(Command42.class);
        serialization.register(Command43.class);
        serialization.register(Command44.class);
        serialization.register(Command45.class);
        serialization.register(Command46.class);
        serialization.register(Command47.class);
        serialization.register(Command48.class);
        serialization.register(Command49.class);
        serialization.register(Command51.class);
        serialization.register(Command52.class);
        serialization.register(Command53.class);
        serialization.register(Command54.class);
        serialization.register(Command55.class);
        serialization.register(Command56.class);
        serialization.register(Command57.class);
        serialization.register(Command58.class);
        serialization.register(Command59.class);
        serialization.register(Command61.class);
        serialization.register(Command62.class);
        serialization.register(Command63.class);
        serialization.register(Command64.class);
        serialization.register(Command65.class);
        serialization.register(Command66.class);
        serialization.register(Command67.class);
        serialization.register(Command68.class);
        serialization.register(Command69.class);
        serialization.register(Command71.class);
        serialization.register(Command72.class);
        serialization.register(Command73.class);
        serialization.register(Command74.class);
        serialization.register(Command75.class);
        serialization.register(Command76.class);
        serialization.register(Command77.class);
        serialization.register(Command78.class);
        serialization.register(Command79.class);
        serialization.register(Command81.class);
        serialization.register(Command82.class);
        serialization.register(Command83.class);
        serialization.register(Command84.class);
        serialization.register(Command85.class);
        serialization.register(Command86.class);
        serialization.register(Command87.class);
        serialization.register(Command88.class);
        serialization.register(Command89.class);
        serialization.register(Command91.class);
        serialization.register(Command92.class);
        serialization.register(Command93.class);
        serialization.register(Command94.class);
        serialization.register(Command95.class);
        serialization.register(Command96.class);
        serialization.register(Command97.class);
        serialization.register(Command98.class);
        serialization.register(Command99.class);
        serialization.register(Command100.class);

        serialization.register(Command101.class);
        serialization.register(Command102.class);
        serialization.register(Command103.class);
        serialization.register(Command104.class);
        serialization.register(Command105.class);
        serialization.register(Command106.class);
        serialization.register(Command107.class);
        serialization.register(Command108.class);
        serialization.register(Command109.class);
        serialization.register(Command110.class);
        serialization.register(Command120.class);
        serialization.register(Command130.class);
        serialization.register(Command140.class);
        serialization.register(Command150.class);
        serialization.register(Command160.class);
        serialization.register(Command170.class);
        serialization.register(Command180.class);
        serialization.register(Command190.class);
        serialization.register(Command111.class);
        serialization.register(Command112.class);
        serialization.register(Command113.class);
        serialization.register(Command114.class);
        serialization.register(Command115.class);
        serialization.register(Command116.class);
        serialization.register(Command117.class);
        serialization.register(Command118.class);
        serialization.register(Command119.class);
        serialization.register(Command121.class);
        serialization.register(Command122.class);
        serialization.register(Command123.class);
        serialization.register(Command124.class);
        serialization.register(Command125.class);
        serialization.register(Command126.class);
        serialization.register(Command127.class);
        serialization.register(Command128.class);
        serialization.register(Command129.class);
        serialization.register(Command131.class);
        serialization.register(Command132.class);
        serialization.register(Command133.class);
        serialization.register(Command134.class);
        serialization.register(Command135.class);
        serialization.register(Command136.class);
        serialization.register(Command137.class);
        serialization.register(Command138.class);
        serialization.register(Command139.class);
        serialization.register(Command141.class);
        serialization.register(Command142.class);
        serialization.register(Command143.class);
        serialization.register(Command144.class);
        serialization.register(Command145.class);
        serialization.register(Command146.class);
        serialization.register(Command147.class);
        serialization.register(Command148.class);
        serialization.register(Command149.class);
        serialization.register(Command151.class);
        serialization.register(Command152.class);
        serialization.register(Command153.class);
        serialization.register(Command154.class);
        serialization.register(Command155.class);
        serialization.register(Command156.class);
        serialization.register(Command157.class);
        serialization.register(Command158.class);
        serialization.register(Command159.class);
        serialization.register(Command161.class);
        serialization.register(Command162.class);
        serialization.register(Command163.class);
        serialization.register(Command164.class);
        serialization.register(Command165.class);
        serialization.register(Command166.class);
        serialization.register(Command167.class);
        serialization.register(Command168.class);
        serialization.register(Command169.class);
        serialization.register(Command171.class);
        serialization.register(Command172.class);
        serialization.register(Command173.class);
        serialization.register(Command174.class);
        serialization.register(Command175.class);
        serialization.register(Command176.class);
        serialization.register(Command177.class);
        serialization.register(Command178.class);
        serialization.register(Command179.class);
        serialization.register(Command181.class);
        serialization.register(Command182.class);
        serialization.register(Command183.class);
        serialization.register(Command184.class);
        serialization.register(Command185.class);
        serialization.register(Command186.class);
        serialization.register(Command187.class);
        serialization.register(Command188.class);
        serialization.register(Command189.class);
        serialization.register(Command191.class);
        serialization.register(Command192.class);
        serialization.register(Command193.class);
        serialization.register(Command194.class);
        serialization.register(Command195.class);
        serialization.register(Command196.class);
        serialization.register(Command197.class);
        serialization.register(Command198.class);
        serialization.register(Command199.class);

        serialization.register(FinishedCommand.class);
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


        server.listeners()
              .add(new Listener.OnMessageReceived<Connection, FinishedCommand>() {
                  @Override
                  public
                  void received(Connection connection, FinishedCommand object) {
                      stopEndPoints();
                  }
              });

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
                      // MUST run on a separate thread because remote object method invocations are blocking
                      new Thread() {
                          @Override
                          public
                          void run() {
                              connection.send()
                                        .TCP(new FinishedCommand())
                                        .flush();
                          }
                      }.start();
                  }
              });

        client.connect(0);

        waitForThreads();
    }

    private static class Command1 {}
    private static class Command2 {}
    private static class Command3 {}
    private static class Command4 {}
    private static class Command5 {}
    private static class Command6 {}
    private static class Command7 {}
    private static class Command8 {}
    private static class Command9 {}

    private static class Command10 {}
    private static class Command20 {}
    private static class Command30 {}
    private static class Command40 {}
    private static class Command50 {}
    private static class Command60 {}
    private static class Command70 {}
    private static class Command80 {}
    private static class Command90 {}

    private static class Command11 {}
    private static class Command12 {}
    private static class Command13 {}
    private static class Command14 {}
    private static class Command15 {}
    private static class Command16 {}
    private static class Command17 {}
    private static class Command18 {}
    private static class Command19 {}

    private static class Command21 {}
    private static class Command22 {}
    private static class Command23 {}
    private static class Command24 {}
    private static class Command25 {}
    private static class Command26 {}
    private static class Command27 {}
    private static class Command28 {}
    private static class Command29 {}

    private static class Command31 {}
    private static class Command32 {}
    private static class Command33 {}
    private static class Command34 {}
    private static class Command35 {}
    private static class Command36 {}
    private static class Command37 {}
    private static class Command38 {}
    private static class Command39 {}

    private static class Command41 {}
    private static class Command42 {}
    private static class Command43 {}
    private static class Command44 {}
    private static class Command45 {}
    private static class Command46 {}
    private static class Command47 {}
    private static class Command48 {}
    private static class Command49 {}

    private static class Command51 {}
    private static class Command52 {}
    private static class Command53 {}
    private static class Command54 {}
    private static class Command55 {}
    private static class Command56 {}
    private static class Command57 {}
    private static class Command58 {}
    private static class Command59 {}

    private static class Command61 {}
    private static class Command62 {}
    private static class Command63 {}
    private static class Command64 {}
    private static class Command65 {}
    private static class Command66 {}
    private static class Command67 {}
    private static class Command68 {}
    private static class Command69 {}

    private static class Command71 {}
    private static class Command72 {}
    private static class Command73 {}
    private static class Command74 {}
    private static class Command75 {}
    private static class Command76 {}
    private static class Command77 {}
    private static class Command78 {}
    private static class Command79 {}

    private static class Command81 {}
    private static class Command82 {}
    private static class Command83 {}
    private static class Command84 {}
    private static class Command85 {}
    private static class Command86 {}
    private static class Command87 {}
    private static class Command88 {}
    private static class Command89 {}

    private static class Command91 {}
    private static class Command92 {}
    private static class Command93 {}
    private static class Command94 {}
    private static class Command95 {}
    private static class Command96 {}
    private static class Command97 {}
    private static class Command98 {}
    private static class Command99 {}



    private static class Command100 {}
    private static class Command101 {}
    private static class Command102 {}
    private static class Command103 {}
    private static class Command104 {}
    private static class Command105 {}
    private static class Command106 {}
    private static class Command107 {}
    private static class Command108 {}
    private static class Command109 {}

    private static class Command110 {}
    private static class Command120 {}
    private static class Command130 {}
    private static class Command140 {}
    private static class Command150 {}
    private static class Command160 {}
    private static class Command170 {}
    private static class Command180 {}
    private static class Command190 {}

    private static class Command111 {}
    private static class Command112 {}
    private static class Command113 {}
    private static class Command114 {}
    private static class Command115 {}
    private static class Command116 {}
    private static class Command117 {}
    private static class Command118 {}
    private static class Command119 {}

    private static class Command121 {}
    private static class Command122 {}
    private static class Command123 {}
    private static class Command124 {}
    private static class Command125 {}
    private static class Command126 {}
    private static class Command127 {}
    private static class Command128 {}
    private static class Command129 {}

    private static class Command131 {}
    private static class Command132 {}
    private static class Command133 {}
    private static class Command134 {}
    private static class Command135 {}
    private static class Command136 {}
    private static class Command137 {}
    private static class Command138 {}
    private static class Command139 {}

    private static class Command141 {}
    private static class Command142 {}
    private static class Command143 {}
    private static class Command144 {}
    private static class Command145 {}
    private static class Command146 {}
    private static class Command147 {}
    private static class Command148 {}
    private static class Command149 {}

    private static class Command151 {}
    private static class Command152 {}
    private static class Command153 {}
    private static class Command154 {}
    private static class Command155 {}
    private static class Command156 {}
    private static class Command157 {}
    private static class Command158 {}
    private static class Command159 {}

    private static class Command161 {}
    private static class Command162 {}
    private static class Command163 {}
    private static class Command164 {}
    private static class Command165 {}
    private static class Command166 {}
    private static class Command167 {}
    private static class Command168 {}
    private static class Command169 {}

    private static class Command171 {}
    private static class Command172 {}
    private static class Command173 {}
    private static class Command174 {}
    private static class Command175 {}
    private static class Command176 {}
    private static class Command177 {}
    private static class Command178 {}
    private static class Command179 {}

    private static class Command181 {}
    private static class Command182 {}
    private static class Command183 {}
    private static class Command184 {}
    private static class Command185 {}
    private static class Command186 {}
    private static class Command187 {}
    private static class Command188 {}
    private static class Command189 {}

    private static class Command191 {}
    private static class Command192 {}
    private static class Command193 {}
    private static class Command194 {}
    private static class Command195 {}
    private static class Command196 {}
    private static class Command197 {}
    private static class Command198 {}
    private static class Command199 {}


    private static class FinishedCommand {}
}
