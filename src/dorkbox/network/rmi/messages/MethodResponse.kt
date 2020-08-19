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
package dorkbox.network.rmi.messages

import dorkbox.network.rmi.RmiUtils

/**
 * Internal message to return the result of a remotely invoked method.
 */
class MethodResponse : RmiMessage {
    // this is packed
    // LEFT -> rmiObjectId   (the registered rmi ID)
    // RIGHT -> rmiId  (ID to match requests <-> responses)
    var packedId: Int = 0

    // this is the result of the invoked method
    var result: Any? = null

    override fun toString(): String {
        return "MethodResponse(rmiObjectId=${RmiUtils.unpackLeft(packedId)}, rmiId=${RmiUtils.unpackRight(packedId)}, result=$result)"
    }
}
