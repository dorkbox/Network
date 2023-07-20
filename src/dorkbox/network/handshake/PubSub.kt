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
    val remoteAddress: InetAddress? = null,
    val remoteAddressString: String = "IPC",
    val portPub: Int = 0,
    val portSub: Int = 0
) {
    val isIpc get() = remoteAddress == null

    fun getLogInfo(debugEnabled: Boolean): String {
        return if (isIpc) {
            if (debugEnabled) {
                "IPC sessionID: p=${sessionIdPub} s=${sessionIdSub}, streamID: p=${streamIdPub} s=${streamIdSub}, reg: p=${pub.registrationId()} s=${sub.registrationId()}"
            } else {
                "IPC [${sessionIdPub}|${sessionIdSub}|${streamIdPub}|${streamIdSub}]"
            }
        } else {
            val prefix = if (remoteAddress is Inet4Address) {
                "IPv4 $remoteAddressString"
            } else {
                "IPv6 $remoteAddressString"
            }

            if (debugEnabled) {
                "$prefix sessionID: p=${sessionIdPub} s=${sessionIdSub}, streamID: p=${streamIdPub} s=${streamIdSub}, port: p=${portPub} s=${portSub}, reg: p=${pub.registrationId()} s=${sub.registrationId()}"
            } else {
                "$prefix [${sessionIdPub}|${sessionIdSub}|${streamIdPub}|${streamIdSub}|${portPub}|${portSub}]"
            }
        }
    }
}
