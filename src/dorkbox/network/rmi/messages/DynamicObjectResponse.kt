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
 */
package dorkbox.network.rmi.messages

/**
 * This is when we successfully created a new object (if there was an error, remoteObject is null)
 * SENT FROM "remote" -> "local"
 *
 * @param callbackId the rmi callback ID on the LOCAL side, to know which callback to use
 */
class DynamicObjectResponse(interfaceClass: Class<*>, rmiId: Int, callbackId: Int, remoteObject: Any?) : RmiMessage {
    /**
     * this is null if there are problems creating an object on the remote side, otherwise it is non-null.
     */
    var remoteObject: Any? = null

    /**
     * this is used to create a NEW rmi object on the REMOTE side (these are bound the to connection. They are NOT GLOBAL, ie: available on all connections)
     */
    var interfaceClass: Class<*>

    /**
     * this is used to get specific, GLOBAL rmi objects (objects that are not bound to a single connection)
     */
    var rmiId: Int

    /**
     * this is the callback ID assigned by the LOCAL side, to know WHICH RMI callback to call when we have a remote object available
     */
    var callbackId: Int


    init {
        this.interfaceClass = interfaceClass
        this.rmiId = rmiId
        this.callbackId = callbackId
        this.remoteObject = remoteObject
    }
}
