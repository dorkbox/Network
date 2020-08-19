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

import dorkbox.network.rmi.CachedMethod
import dorkbox.network.rmi.RmiUtils

/**
 * Internal message to invoke methods remotely.
 */
class MethodRequest : RmiMessage {

    /**
     * true if this method is invoked on a global object, false if it is connection scoped
     */
    var isGlobal: Boolean = false

    /**
     * true if this method is a suspend function (with coroutine) or a normal method
     */
    var isCoroutine: Boolean = false

    // this is packed
    // LEFT -> rmiObjectId   (the registered rmi ID)
    // RIGHT -> rmiId  (ID to match requests <-> responses)
    var packedId: Int = 0

    // This field is NOT sent across the wire (but some of it's contents are).
    // We use a custom serializer to manage this because we have to ALSO be able to serialize the invocation arguments.
    // NOTE: the info we serialize is REALLY a short, but is represented as an int to make life easier. It is also packed!
    lateinit var cachedMethod: CachedMethod

    // these are the arguments for executing the method (they are serialized using the info from the cachedMethod field
    var args: Array<Any>? = null

    override fun toString(): String {
        return "MethodRequest(isGlobal=$isGlobal, rmiObjectId=${RmiUtils.unpackLeft(packedId)}, rmiId=${RmiUtils.unpackRight(packedId)}, cachedMethod=$cachedMethod, args=${args?.contentToString()})"
    }
}
