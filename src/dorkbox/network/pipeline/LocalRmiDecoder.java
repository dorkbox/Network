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
package dorkbox.network.pipeline;

import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.rmi.OverriddenMethods;
import dorkbox.network.rmi.RemoteObject;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.lang.reflect.Field;
import java.util.List;

public
class LocalRmiDecoder extends MessageToMessageDecoder<Object> {

    private static final RmiFieldCache fieldCache = RmiFieldCache.INSTANCE();
    private static final OverriddenMethods overriddenMethods = OverriddenMethods.INSTANCE();

    public
    LocalRmiDecoder() {
        super();
    }

    @Override
    protected
    void decode(final ChannelHandlerContext context, final Object msg, final List<Object> out) throws Exception {
        if (msg instanceof LocalRmiClassEncoder) {
            LocalRmiClassEncoder encoded = (LocalRmiClassEncoder) msg;

            final Object messageObject = encoded.rmiObject;
            final int[] rmiFieldIds = encoded.rmiFieldIds;

            final Class<?> messageClass = messageObject.getClass();
            ConnectionImpl connection = (ConnectionImpl) context.pipeline()
                                                                .last();

            Object localRmiObject = null;
            Field field;
            int registeredId;
            final Field[] rmiFields = fieldCache.get(messageClass);
            for (int i = 0; i < rmiFields.length; i++) {
                field = rmiFields[i];
                registeredId = rmiFieldIds[i];

                if (registeredId == 0) {
                    // the field was null/ignore
                } else {
                    // if it's an RMI object we want to write out a proxy object in the field instead of the actual object
                    try {
                        localRmiObject = field.get(messageObject);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }

                    if (localRmiObject == null) {
                        throw new RuntimeException("Unable to get RMI interface object for RMI implementation");
                    }

                    final Class<?> iface = overriddenMethods.getReverse(localRmiObject.getClass());
                    if (iface == null) {
                        throw new RuntimeException("Unable to get interface for RMI implementation");
                    }

                    RemoteObject remoteObject = connection.getProxyObject(registeredId, iface);
                    field.set(messageObject, remoteObject);
                }
            }

            out.add(messageObject);
        } else {
            out.add(msg);
        }
    }
}
