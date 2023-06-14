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

package dorkbox.network.aeron.mediaDriver

import io.aeron.Publication
import io.aeron.Subscription
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
    val portPub: Int = -1,
    val portSub: Int = -1
) {
    val isIpc get() = remoteAddress == null
}
