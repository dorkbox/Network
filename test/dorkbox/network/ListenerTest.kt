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
package dorkbox.network

import dorkbox.network.connection.ConnectionImpl
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.MediaDriverConnection
import dorkbox.util.exceptions.InitializationException
import dorkbox.util.exceptions.SecurityException
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ListenerTest : BaseTest() {
    private val origString = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // lots of a's to encourage compression
    private val limit = 20
    private val count = AtomicInteger(0)
    var checkFail1 = AtomicBoolean(false)
    var checkFail2 = AtomicBoolean(false)

    var check1 = AtomicBoolean(false)
    var check2 = AtomicBoolean(false)
    var check3 = AtomicBoolean(false)
    var check4 = AtomicBoolean(false)
    var check5 = AtomicBoolean(false)
    var check6 = AtomicBoolean(false)

    // quick and dirty test to also test connection sub-classing
    internal open inner class TestConnectionA(endPointConnection: EndPoint<TestConnectionA>, driverConnection: MediaDriverConnection) : ConnectionImpl(endPointConnection, driverConnection) {
        open fun check() {
            check1.set(true)
        }
    }

    @Test
    @Throws(SecurityException::class, InitializationException::class, IOException::class, InterruptedException::class)
    fun listener() {
        val server: Server<TestConnectionA> = object : Server<TestConnectionA>(serverConfig()) {
            override fun newConnection(endPoint: EndPoint<TestConnectionA>, mediaDriverConnection: MediaDriverConnection): TestConnectionA {
                return TestConnectionA(endPoint, mediaDriverConnection)
            }
        }
        addEndPoint(server)

        // standard listener
        server.onMessage<String> { connection, message ->
            // should be called
            connection.check()
            connection.send(message)
        }

        // generic listener
        server.onMessage<Any> { _, _ ->
            // should be called!
            check2.set(true)
        }

        // standard connect check
        server.onConnect {
            check3.set(true)
        }

        // standard listener disconnect check
        server.onDisconnect {
            check4.set(true)
        }


        server.bind(false)




        // ----
        val client: Client<TestConnectionA> = object : Client<TestConnectionA>(clientConfig()) {
            override fun newConnection(endPoint: EndPoint<TestConnectionA>, mediaDriverConnection: MediaDriverConnection): TestConnectionA {
                return TestConnectionA(endPoint, mediaDriverConnection)
            }
        }
        addEndPoint(client)


        client.onConnect { connection ->
            connection.send(origString) // 20 a's
        }

        client.onMessage<String> { connection, message ->
            if (origString != message) {
                checkFail2.set(true)
                System.err.println("original string not equal to the string received")
                stopEndPoints()
                return@onMessage
            }

            if (count.getAndIncrement() < limit) {
                connection.send(message)
            } else {
                stopEndPoints()
            }
        }

        // standard connect check
        client.onConnect {
            check5.set(true)
        }


        // standard listener disconnect check
        client.onDisconnect {
            check6.set(true)
        }


        runBlocking {
            client.connect(LOOPBACK)
        }

        waitForThreads()

        // -1 BECAUSE we are `getAndIncrement` for each check earlier
        Assert.assertEquals(limit.toLong(), count.get() - 1.toLong())
        Assert.assertTrue(check1.get())
        Assert.assertTrue(check2.get())
        Assert.assertTrue(check3.get())
        Assert.assertTrue(check4.get())
        Assert.assertTrue(check5.get())
        Assert.assertTrue(check6.get())

        Assert.assertFalse(checkFail1.get())
        Assert.assertFalse(checkFail2.get())
    }
}
