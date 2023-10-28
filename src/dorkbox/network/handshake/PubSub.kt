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

package dorkbox.network.handshake

import dorkbox.network.connection.EndPoint
import io.aeron.Publication
import io.aeron.Subscription
import java.net.Inet4Address
import java.net.InetAddress

data class PubSub(
    val pub: Publication,
    val sub: Subscription,
    val sessionIdPub: Int,
    val sessionIdSub: Int,
    val streamIdPub: Int,
    val streamIdSub: Int,
    val reliable: Boolean,
    val remoteAddress: InetAddress?,
    val remoteAddressString: String,
    val portPub: Int,
    val portSub: Int,
    val tagName: String  // will either be "", or will be "[tag_name]"
) {
    val isIpc get() = remoteAddress == null

    fun getLogInfo(extraDetails: Boolean): String {
        return if (isIpc) {
            val prefix = if (tagName.isNotEmpty()) {
                EndPoint.IPC_NAME + " $tagName"
            } else {
                EndPoint.IPC_NAME
            }

            if (extraDetails) {
                if (tagName.isNotEmpty()) {
                    "$prefix sessionID: p=${sessionIdPub} s=${sessionIdSub}, streamID: p=${streamIdPub} s=${streamIdSub}, reg: p=${pub.registrationId()} s=${sub.registrationId()}"
                } else {
                    "$prefix sessionID: p=${sessionIdPub} s=${sessionIdSub}, streamID: p=${streamIdPub} s=${streamIdSub}, reg: p=${pub.registrationId()} s=${sub.registrationId()}"
                }
            } else {
                prefix
            }
        } else {
            var prefix = if (remoteAddress is Inet4Address) {
                "IPv4 $remoteAddressString"
            } else {
                "IPv6 $remoteAddressString"
            }

            if (tagName.isNotEmpty()) {
                prefix += " $tagName"
            }

            if (extraDetails) {
                "$prefix sessionID: p=${sessionIdPub} s=${sessionIdSub}, streamID: p=${streamIdPub} s=${streamIdSub}, port: p=${portPub} s=${portSub}, reg: p=${pub.registrationId()} s=${sub.registrationId()}"
            } else {
                prefix
            }
        }
    }
}
