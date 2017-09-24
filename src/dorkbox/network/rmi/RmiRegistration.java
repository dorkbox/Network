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
package dorkbox.network.rmi;

/**
 * Message specifically to register a class implementation for RMI
 */
public
class RmiRegistration {
    public Object remoteObject;
    public Class<?> interfaceClass;

    // this is used to get specific, GLOBAL rmi objects (objects that are not bound to a single connection)
    public int remoteObjectId;

    public int rmiID;

    @SuppressWarnings("unused")
    private
    RmiRegistration() {
        // for serialization
    }

    // When requesting a new remote object to be created.
    // SENT FROM "client" -> "server"
    public
    RmiRegistration(final Class<?> interfaceClass, final int rmiID) {
        this.interfaceClass = interfaceClass;
        this.rmiID = rmiID;
    }

    // When requesting a new remote object to be created.
    // SENT FROM "client" -> "server"
    public
    RmiRegistration(final int remoteObjectId, final int rmiID) {
        this.remoteObjectId = remoteObjectId;
        this.rmiID = rmiID;
    }


    // When there was an error creating the remote object.
    // SENT FROM "server" -> "client"
    public
    RmiRegistration(final int rmiID) {
        this.rmiID = rmiID;
    }

    // This is when we successfully created a new object
    // SENT FROM "server" -> "client"
    public
    RmiRegistration(final Object remoteObject, final int rmiID) {
        this.remoteObject = remoteObject;
        this.rmiID = rmiID;
    }
}
