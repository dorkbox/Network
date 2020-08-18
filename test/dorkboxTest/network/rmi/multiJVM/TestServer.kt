package dorkboxTest.network.rmi.multiJVM
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
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkboxTest.network.BaseTest
import dorkboxTest.network.rmi.RmiTest
import dorkboxTest.network.rmi.classes.TestCow
import dorkboxTest.network.rmi.classes.TestCowImpl
import dorkboxTest.network.rmi.multiJVM.TestClient.setup

/**
 *
 */
object TestServer {
    @JvmStatic
    fun main(args: Array<String>) {
        setup()

        val configuration = BaseTest.serverConfig()

        RmiTest.register(configuration.serialization)
        configuration.serialization.registerRmi(TestCow::class.java, TestCowImpl::class.java)
        configuration.enableRemoteSignatureValidation = false

        val server = Server<Connection>(configuration)

        server.bind(false)
    }
}
