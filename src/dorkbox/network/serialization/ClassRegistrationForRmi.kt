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
package dorkbox.network.serialization

import dorkbox.network.rmi.messages.RmiServerSerializer

/**
 * This is to manage serializing RMI objects across the wire...
 *
 * NOTE:
 *   CLIENT: can never send the iface object, if it's RMI, it will send the java Proxy object instead.
 *   SERVER: can never send the iface object, it will always send the IMPL object instead (because of how kryo works)
 *
 *   **************************
 *   NOTE: This works because we TRICK kryo serialization by changing what the kryo ID serializer is on each end of the connection
 *   **************************
 *
 *   What we do is on the server, REWRITE the kryo ID for the impl so that it will send just the rmi ID instead of the object
 *   on the client, this SAME kryo ID must have this serializer as well, so the proxy object is re-assembled.
 *
 *   Kryo serialization works by inspecting the field VALUE type, not the field DEFINED type... So if you send an actual object, you must
 *   register specifically for the implementation object.
 *
 *
 * To recap:
 *  rmi-client: send proxy -> RmiIfaceSerializer -> network -> RmiIfaceSerializer -> impl object (rmi-server)
 *  rmi-server: send impl -> RmiImplSerializer -> network -> RmiImplSerializer -> proxy object (rmi-client)
 *
 *  rmi-server MUST registerRmi both the iface+impl
 *
 *  During the handshake, if the impl object 'lives' on the CLIENT, then the client must tell the server that the iface ID must use this serializer.
 *  If the impl object 'lives' on the SERVER, then the server must tell the client about the iface ID
 */
internal class ClassRegistrationForRmi(ifaceClass: Class<*>,
                                       val implClass: Class<*>,
                                       serializer: RmiServerSerializer) : ClassRegistration(ifaceClass, serializer) {
    /**
     * In general:
     *
     *  ALL kryo registrations must be for IMPL, because of how kryo works, kryo can ONLY send IMPL objects, and thus serialization
     *  can only be for IMPL objects. (we can write fields for an IFACE, but when constructing an object, it must be concrete)
     *
     * To recap how RMI works:
     *   rmi-client works ONLY with proxy objects
     *   rmi-server works ONLY with impl objects
     *   **NOTE: it is possible to have both ends of a connection be BOTH the rmi-client+server (for bi-directional RMI)
     *
     *
     *  ####
     * To SEND/RECEIVE an RMI object
     *
     * for rmi-client -> {object} -> rmi-server
     *  REQUIRES:
     *    rmi-client:   (java proxy object -> bytes)
     *       register InvocationHandler class with RmiClientSerializer
     *       register IFACE class
     *    rmi-server:   (bytes -> java impl object)
     *       register InvocationHandler class with RmiClientSerializer
     *       register IMPL class
     *       able to lookup rmiID -> IMPL object
     *
     *   rmi-client -> send proxy object -> {{kryo: InvocationHandler, sends rmiID}}
     *   {{network}}
     *   {{kryo: InvocationHandler looks up impl object based on ID}} -> rmi-server receives IMPL object
     *
     *
     * for rmi-server -> {object} -> rmi-client
     *  REQUIRES:
     *    rmi-server:   (java impl object -> bytes)
     *       register IMPL object class with RmiServerSerializer
     *       able to lookup IMPL object -> rmiID
     *    rmi-client:   (bytes -> java proxy object)
     *       register IFACE class with RmiServerSerializer
     *       able to lookup/create rmiID -> proxy object
     *
     *   rmi-server -> send impl object -> {{kryo: RmiServerSerializer, sends rmiId}}
     *   {{network}}
     *   {{kryo: RmiServerSerializer read rmiID, looks up/creates proxy object using IFACE}} -> rmi-client receives proxy object
     *
     *
     * Requirements for all cases of RMI
     *  rmi-client:
     *      send: register InvocationHandler class with RmiClientSerializer
     *   receive: register IMPL object class with RmiServerSerializer
     *            lookup IMPL object -> rmiID
     *
     * rmi-server:
     *     receive: register InvocationHandler class with RmiClientSerializer
     *              lookup rmiID -> IMPL object
     *        send: register IMPL object class with RmiServerSerializer
     *              lookup IMPL object -> rmiID
     */
    override fun register(kryo: KryoExtra, rmi: RmiHolder) {
        // we override this, because we ALWAYS will call our RMI registration!

        // have to get the ID for the interface (if it exists)
        val registration = kryo.classResolver.getRegistration(clazz) // this is ifaceClass, and must match what is defined on the rmi client
        if (registration != null) {
            id = registration.id

            // override that registration
            kryo.register(implClass, serializer, id)
        } else {
            // now register the impl class
            id = kryo.register(implClass, serializer).id
        }
        info = "Registered $id -> (RMI) ${implClass.name}"


        // now, we want to save the relationship between classes and kryoId
        rmi.ifaceToId[clazz] = id
        rmi.idToIface[id] = clazz

        // we have to know what the IMPL class is so we can create it for a "createObject" RMI command
        rmi.implToId[implClass] = id
        rmi.idToImpl[id] = implClass
    }

    override fun getInfoArray(): Array<Any> {
        // the info array has to match for the INTERFACE (not the impl!)
        return arrayOf(id, clazz.name, serializer!!::class.java.name)
    }
}
