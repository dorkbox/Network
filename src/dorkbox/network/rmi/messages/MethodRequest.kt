/*
 * Copyright 2010 dorkbox, llc
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
 *
 * Copyright (c) 2008, Nathan Sweet
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package dorkbox.network.rmi.messages

import dorkbox.network.rmi.CachedMethod

/**
 * Internal message to invoke methods remotely.
 */
class MethodRequest : RmiMessage {
    // if this object was a global or connection specific object
    var isGlobal: Boolean = false

    // the registered kryo ID for the object
    // NOTE: this is REALLY a short, but is represented as an int to make life easier. It is also packed with the responseId for serialization
    var objectId: Int = 0

    // A value of 0 means to not respond, otherwise it is an ID to match requests <-> responses
    // NOTE: this is REALLY a short, but is represented as an int to make life easier. It is also packed with the objectId for serialization
    var responseId: Int = 0

    // This field is NOT sent across the wire (but some of it's contents are).
    // We use a custom serializer to manage this because we have to ALSO be able to serialize the invocation arguments.
    // NOTE: the info we serialze is REALLY a short, but is represented as an int to make life easier. It is also packed!
    lateinit var cachedMethod: CachedMethod

    // these are the arguments for executing the method (they are serialized using the info from the cachedMethod field
    var args: Array<Any>? = null


    override fun toString(): String {
        return "MethodRequest(isGlobal=$isGlobal, objectId=$objectId, responseId=$responseId, cachedMethod=$cachedMethod, args=${args?.contentToString()})"
    }
}
