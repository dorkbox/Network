/*
 * Copyright 2021 dorkbox, llc
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


@file:Suppress("UNUSED_PARAMETER")

package dorkbox.network.ping

import dorkbox.network.connection.Connection
import dorkbox.network.connection.EndPoint
import dorkbox.network.handshake.RandomIdAllocator
import dorkbox.network.rmi.ResponseManager
import kotlinx.coroutines.CoroutineScope
import mu.KLogger

/**
 *
 */
class PingManager<CONNECTION : Connection>(logger: KLogger, actionDispatch: CoroutineScope) {
    /**
     * allocates ID's for use when pinging a remote endpoint
     */
    internal val pingIdAllocator = RandomIdAllocator(Integer.MIN_VALUE, Integer.MAX_VALUE)

    internal val responseManager = ResponseManager(logger, actionDispatch)

    /**
     * Updates the ping times for this connection (called when this connection gets a REPLY ping message).
     */
    fun updatePingResponse(ping: PingMessage) {
//
//        @Volatile
//        private var pingFuture: PingFuture? = null
//
//        pingFuture?.setSuccess(this, ping)
    }


    suspend fun manage(endPoint: EndPoint<CONNECTION>, connection: CONNECTION, message: PingMessage, logger: KLogger) {
//        if (message.isReply) {
//            connection.updatePingResponse(message)
//        } else {
//            // return the ping from whence it came
//            message.isReply = true
//            connection.send(message)
//        }
    }

    suspend fun ping(function1: Connection, function: suspend Ping.() -> Unit): Boolean {
//        val ping = PingMessage()
//        ping.id = pingIdAllocator.allocate()
//
//
//        pingFuture = PingFuture()
//
////        function: suspend (CONNECTION) -> Unit
//        // TODO: USE AERON FOR THIS
////        val pingFuture2 = pingFuture
////        if (pingFuture2 != null && !pingFuture2.isSuccess) {
////            pingFuture2.cancel()
////        }
////        val newPromise: Promise<PingTuple<out Connection?>>
////        newPromise = if (channelWrapper.udp() != null) {
////            channelWrapper.udp()
////                    .newPromise()
////        } else {
////            channelWrapper.tcp()
////                    .newPromise()
////        }
//        pingFuture = PingFuture()
////        val ping = PingMessage()
////        ping.id = pingFuture!!.id
////        ping0(ping)
////        return pingFuture!!
//        TODO()
        return false
    }

}
