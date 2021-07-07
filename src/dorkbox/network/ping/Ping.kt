/*
 * Copyright 2020 dorkbox, llc
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
package dorkbox.network.ping

import dorkbox.network.rmi.RmiUtils

class Ping {
    var packedId = 0

    // ping/pong times are the LOWER 8 bytes of a long, which gives us 65 seconds. This is the same as the max value timeout (a short) so this is acceptible

    var pingTime = 0L
    var pongTime = 0L

    @Transient
    var finishedTime = 0L

    /**
     * The time it took for the remote connection to return the ping to us. This is only accurate if the clocks are synchronized
     */
    val inbound: Long
        get() {
            return finishedTime - pongTime
        }

    /**
     * The time it took for us to ping the remote connection. This is only accurate if the clocks are synchronized.
     */
    val outbound: Long
        get() {
            return pongTime - pingTime
        }


    /**
     * The round-trip time it took to ping the remote connection
     */
    val roundtrip: Long
        get() {
            return  finishedTime - pingTime
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Ping

        if (packedId != other.packedId) return false
        if (pingTime != other.pingTime) return false
        if (pongTime != other.pongTime) return false
        if (finishedTime != other.finishedTime) return false

        return true
    }

    override fun hashCode(): Int {
        var result = packedId
        result = 31 * result + pingTime.hashCode()
        result = 31 * result + pongTime.hashCode()
        result = 31 * result + finishedTime.hashCode()
        return result
    }

    override fun toString(): String {
        return "Ping ${RmiUtils.unpackUnsignedRight(packedId)} (pingTime=$pingTime, pongTime=$pongTime, finishedTime=$finishedTime)"
    }
}
