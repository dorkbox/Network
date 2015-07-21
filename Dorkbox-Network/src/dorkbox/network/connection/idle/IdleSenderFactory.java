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
package dorkbox.network.connection.idle;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ConnectionImpl;

@SuppressWarnings({"unchecked", "rawtypes"})
public
class IdleSenderFactory<C extends Connection, M> implements IdleBridge {
    private final ConnectionImpl connection;
    private final Object message;

    public
    IdleSenderFactory(final ConnectionImpl connection, final Object message) {
        this.connection = connection;
        this.message = message;
    }

    @Override
    public
    void TCP() {
        if (message instanceof IdleSender) {
            connection.listeners().add((IdleSender)message);
        } else {
            connection.listeners().add(new IdleObjectSender(new IdleListenerTCP<C, M>(), message));
        }
    }

    @Override
    public
    void UDP() {
        if (message instanceof IdleSender) {
            connection.listeners().add((IdleSender)message);
        } else {
            connection.listeners().add(new IdleObjectSender(new IdleListenerUDP<C, M>(), message));
        }
    }

    @Override
    public
    void UDT() {
        if (message instanceof IdleSender) {
            connection.listeners().add((IdleSender)message);
        } else {
            connection.listeners().add(new IdleObjectSender(new IdleListenerUDT<C, M>(), message));
        }
    }
}
