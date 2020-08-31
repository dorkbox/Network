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
package dorkboxTest.network.rmi

import dorkbox.network.connection.Connection
import dorkbox.network.rmi.RemoteObject
import dorkbox.network.serialization.Serialization
import dorkboxTest.network.rmi.cows.MessageWithTestCow
import dorkboxTest.network.rmi.cows.TestCow
import org.junit.Assert

object RmiCommonTest {
    suspend fun runTests(connection: Connection, test: TestCow, remoteObjectID: Int) {
        val remoteObject = test as RemoteObject

        // Default behavior. RMI is transparent, method calls behave like normal
        // (return values and exceptions are returned, call is synchronous)
        connection.logger.error("hashCode: " + test.hashCode())
        connection.logger.error("toString: $test")

        test.withSuspend("test", 32)
        val s1 = test.withSuspendAndReturn("test", 32)
        Assert.assertEquals(s1, 32)


        // see what the "remote" toString() method is
        val s = remoteObject.toString()
        remoteObject.enableToString(true)
        Assert.assertFalse(s == remoteObject.toString())
        test.moo()
        test.moo("Cow")
        Assert.assertEquals(remoteObjectID, test.id())

        // Test that RMI correctly waits for the remotely invoked method to exit
        remoteObject.responseTimeout = 5000
        test.moo("You should see this two seconds before...", 2000)
        connection.logger.error("...This")
        remoteObject.responseTimeout = 3000

        // Try exception handling
        try {
            test.throwException()
            Assert.fail("sync should be throwing an exception!")
        } catch (e: UnsupportedOperationException) {
            connection.logger.error("Expected exception (exception log should also be on the object impl side).", e)
        }

        try {
            test.throwSuspendException()
            Assert.fail("sync should be throwing an exception!")
        } catch (e: UnsupportedOperationException) {
            connection.logger.error("\tExpected exception (exception log should also be on the object impl side).", e)
        }

        // Non-blocking call tests
        // Non-blocking call tests
        // Non-blocking call tests
        connection.logger.error("I'm currently async: ${remoteObject.async}. Now testing ASYNC")

        remoteObject.async = true


        // calls that ignore the return value
        test.moo("Meow")
        // Non-blocking call that ignores the return value
        Assert.assertEquals(0, test.id().toLong())


        // exceptions are still dealt with properly
        test.moo("Baa")

        try {
            test.throwException()
        } catch (e: IllegalStateException) {
            // exceptions are not caught when async = true!
            Assert.fail("Async should not be throwing an exception!")
        }

        try {
            test.throwSuspendException()
        } catch (e: IllegalStateException) {
            // exceptions are not caught when async = true!
            Assert.fail("Async should not be throwing an exception!")
        }




        // Call will time out if non-blocking isn't working properly
        test.moo("Mooooooooo", 4000)


        // should wait for a small time
        remoteObject.async = false
        remoteObject.responseTimeout = 6000
        connection.logger.error("You should see this 2 seconds before")
        val slow = test.slow()
        connection.logger.error("...This")
        Assert.assertEquals(slow.toDouble(), 123.0, 0.0001)


        // Test sending a reference to a remote object.
        val m = MessageWithTestCow(test)
        m.number = 678
        m.text = "sometext"
        connection.send(m)

        connection.logger.error("Finished tests")
    }

    fun register(manager: Serialization) {
        manager.register(Any::class.java) // Needed for Object#toString, hashCode, etc.
        manager.register(TestCow::class.java)
        manager.register(MessageWithTestCow::class.java)
        manager.register(UnsupportedOperationException::class.java)
    }
}
