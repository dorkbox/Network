/*
 * Copyright 2018 dorkbox, llc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dorkbox.network.rmi;

import org.slf4j.Logger;

import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.Listener;

public
class RmiObjectNetworkHandler extends RmiObjectHandler {

    private final Logger logger;

    public
    RmiObjectNetworkHandler(final Logger logger) {
        this.logger = logger;
    }

    @Override
    public
    void invoke(final ConnectionImpl connection, final InvokeMethod message, final Listener.OnMessageReceived<ConnectionImpl, InvokeMethod> rmiInvokeListener) {
        // default, nothing fancy
        rmiInvokeListener.received(connection, message);
    }

    @Override
    public
    void registration(final ConnectionImpl connection, final RmiRegistration registration) {
        // manage creating/getting/notifying this RMI object

        // these fields are ALWAYS present!
        final Class<?> interfaceClass = registration.interfaceClass;
        final int callbackId = registration.callbackId;


        if (registration.isRequest) {
            // Check if we are creating a new REMOTE object. This check is always first.
            if (registration.rmiId == RmiBridge.INVALID_RMI) {
                // THIS IS ON THE REMOTE CONNECTION (where the object will really exist as an implementation)
                //
                // CREATE a new ID, and register the ID and new object (must create a new one) in the object maps

                // have to lookup the implementation class
                Class<?> rmiImpl = connection.getEndPoint().getSerialization().getRmiImpl(interfaceClass);


                // For network connections, the interface class kryo ID == implementation class kryo ID, so they switch automatically.
                RmiRegistration registrationResult = connection.createNewRmiObject(interfaceClass, rmiImpl, callbackId);
                connection.send(registrationResult);
                // connection transport is flushed in calling method (don't need to do it here)
            }

            // Check if we are getting an already existing REMOTE object. This check is always AFTER the check to create a new object
            else {
                // THIS IS ON THE REMOTE CONNECTION (where the object implementation will really exist)
                //
                // GET a LOCAL rmi object, if none get a specific, GLOBAL rmi object (objects that are not bound to a single connection).
                RmiRegistration registrationResult = connection.getExistingRmiObject(interfaceClass, registration.rmiId, callbackId);
                connection.send(registrationResult);
                // connection transport is flushed in calling method (don't need to do it here)
            }
        }
        else {
            if (registration.rmiId == RmiBridge.INVALID_RMI) {
                logger.error("RMI ID '{}' is invalid. Unable to create RMI object.", registration.rmiId);
            }

            // this is the response.
            // THIS IS ON THE LOCAL CONNECTION SIDE, which is the side that called 'getRemoteObject()'   This can be Server or Client.
            connection.runRmiCallback(interfaceClass, callbackId, registration.remoteObject);
        }
    }
}
