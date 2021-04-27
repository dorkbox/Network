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

package dorkbox.network.aeron

import java.net.InetAddress

/**
 * This represents the connection PAIR between a server<->client
 * A connection timeout of 0, means to wait forever
 */
internal class UdpMediaDriverPairedConnection(listenAddress: InetAddress,
                                              val remoteAddress: InetAddress,
                                              val remoteAddressString: String,
                                              publicationPort: Int,
                                              subscriptionPort: Int,
                                              streamId: Int,
                                              sessionId: Int,
                                              connectionTimeoutMS: Long = 0,
                                              isReliable: Boolean = true) :
    UdpMediaDriverServerConnection(listenAddress, publicationPort, subscriptionPort, streamId, sessionId, connectionTimeoutMS, isReliable) {

    override fun toString(): String {
        return "$remoteAddressString [$subscriptionPort|$publicationPort] [$streamId|$sessionId] (reliable:$isReliable)"
    }
}
