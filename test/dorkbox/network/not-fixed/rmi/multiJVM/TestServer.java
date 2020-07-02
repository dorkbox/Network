/*
 * Copyright 2019 dorkbox, llc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dorkbox.network.Server;
import dorkbox.network.rmi.RmiTest;
import dorkbox.network.rmi.TestCow;
import dorkbox.network.rmi.TestCowImpl;
import dorkbox.network.serialization.Serialization;
import dorkbox.util.exceptions.SecurityException;

/**
 *
 */
public
class TestServer
{
    public static
    void main(String[] args) {
        TestClient.setup();

        dorkbox.network.Configuration configuration = new dorkbox.network.Configuration();
        configuration.tcpPort = 2000;
        configuration.udpPort = 2001;

        configuration.serialization = Serialization.DEFAULT();
        RmiTest.register(configuration.serialization);

        configuration.serialization.registerRmi(TestCow.class, TestCowImpl.class);

        Server server = null;
        try {
            server = new Server(configuration);
            server.disableRemoteKeyValidation();
        } catch (SecurityException e) {
            e.printStackTrace();
        }

        server.setIdleTimeout(0);
        server.bind(true);
    }}
