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
package dorkbox.network.connection.registration.remote;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.util.CryptoSerializationManager;

public
class RegistrationRemoteHandlerServer<C extends Connection> extends RegistrationRemoteHandler<C> {

    public
    RegistrationRemoteHandlerServer(final String name,
                                    final RegistrationWrapper<C> registrationWrapper,
                                    final CryptoSerializationManager serializationManager) {
        super(name, registrationWrapper, serializationManager);
    }

    /**
     * @return the direction that traffic is going to this handler (" <== " or " ==> ")
     */
    @Override
    protected
    String getConnectionDirection() {
        return " <== ";
    }

    /**
     * Registers the metachannel for the UDP server
     */
    @Override
    protected
    void setupServerUdpConnection(MetaChannel metaChannel) {
        registrationWrapper.registerServerUDP(metaChannel);
    }
}
