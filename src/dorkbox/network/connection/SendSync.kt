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

package dorkbox.network.connection

import dorkbox.network.rmi.RmiUtils

class SendSync {
    var message: Any? = null

    // used to notify the remote endpoint that the message has been processed
    var id: Int = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SendSync) return false

        if (message != other.message) return false
        if (id != other.id) return false

        return true
    }

    override fun hashCode(): Int {
        var result = message?.hashCode() ?: 0
        result = 31 * result + id
        return result
    }

    override fun toString(): String {
        return "SendSync ${RmiUtils.unpackUnsignedRight(id)}  (message=$message)"
    }
}
