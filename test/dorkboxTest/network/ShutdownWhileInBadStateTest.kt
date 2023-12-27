/*
 * Copyright 2023 dorkbox, llc
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

package dorkboxTest.network

import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.network.connection.ConnectionParams
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.junit.Test

class ShutdownWhileInBadStateTest : BaseTest() {

    @Test
    fun shutdownWhileClientConnecting() {
        val isRunning = atomic(true)

        val client = run {
            val config = clientConfig()

            val client = object : Client<Connection>(config) {
                override fun newConnection(connectionParameters: ConnectionParams<Connection>): Connection {
                    return Connection(connectionParameters)
                }
            }

            addEndPoint(client)

            client
        }

        GlobalScope.launch(Dispatchers.IO) {
            // there might be errors while trying to reconnect. Make sure that we constantly retry.
            while (isRunning.value) {
                try {
                    // if the server isn't available, wait forever.
                    client.connect(LOCALHOST, 2222, connectionTimeoutSec = 0) // bad port
                    return@launch
                } catch (ignored: Exception) {
                }
            }
        }

        // at wait for the connection process to start
        Thread.sleep(4000)
        isRunning.lazySet(false)
        client.close()

        waitForThreads()
    }

    @Test
    fun shutdownServerBeforeBind() {
        val server = run {
            val configuration = serverConfig()

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)

            server
        }

        server.close()

        waitForThreads()
    }
}
