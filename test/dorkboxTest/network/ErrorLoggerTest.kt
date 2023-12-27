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
import org.junit.Test

class ErrorLoggerTest : BaseTest() {
    class TestObj {
        var entry = "1234"
    }

    @Test
    fun customErrorLoggerTest() {
        val exception = Exception("server ERROR. SHOULD BE CAUGHT")

        val server = run {
            val configuration = serverConfig()
            configuration.aeronErrorFilter = {
                true // log all errors
            }

            configuration.serialization.register(TestObj::class.java)

            val server: Server<Connection> = Server(configuration)
            addEndPoint(server)


            server.onError { throwable ->
                println("Error on connection $this")
                throwable.printStackTrace()
            }

            server.onErrorGlobal { throwable ->
                println("Global error!!!!")
                throwable.printStackTrace()
            }

            server.onMessage<Any> {
                throw exception
            }

            server
        }

        val client = run {
            val config = clientConfig()
            config.aeronErrorFilter = {
                true // log all errors
            }

            val client: Client<Connection> = Client(config)
            addEndPoint(client)

            client.onConnect {
                // can be any message, we just want the error-log to log something
                send(TestObj())

                pause(200)
                stopEndPoints()
            }

            client
        }

        server.bind(2000)
        client.connect(LOCALHOST, 2000)

        waitForThreads() { errors ->
            // we don't want to fail the unit test for this exception
            errors.filter { it != exception }
        }
    }
}
