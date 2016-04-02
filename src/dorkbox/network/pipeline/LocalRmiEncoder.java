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
import dorkbox.network.connection.EndPoint;
import dorkbox.network.rmi.RMI;
import dorkbox.util.FastThreadLocal;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

@Sharable
public
class LocalRmiEncoder extends MessageToMessageEncoder<Object> {

    private static final Map<Class<?>, Boolean> transformObjectCache = new ConcurrentHashMap<Class<?>, Boolean>(EndPoint.DEFAULT_THREAD_POOL_SIZE);
    private static final RmiFieldCache fieldCache = RmiFieldCache.INSTANCE();

    private final FastThreadLocal<Map<Object, Integer>> objectThreadLocals = new FastThreadLocal<Map<Object, Integer>>() {
        @Override
        public
        Map<Object, Integer> initialValue() {
            return new WeakHashMap<Object, Integer>(8);
        }
    };


    public
    LocalRmiEncoder() {
        super();
    }

    @Override
    protected
    void encode(final ChannelHandlerContext context, final Object msg, final List<Object> out) throws Exception {
        // have to change the rmi objects to proxy objects, with the server-assigned ID
        // HOWEVER -- we cannot use the connection here (otherwise the logic is backwards). The connection must be set in other side


        // we should check to see if this class is registered as having RMI methods present.
        // if YES, then we have to send the corresponding RMI proxy object INSTEAD of the actual object.
        // normally, it's OK to send the actual object.
        //
        // We specifically DO NOT do full serialization because it's not necessary --- we are running inside the same JVM.
        final Class<?> implClass = msg.getClass();
        Boolean needsTransform = transformObjectCache.get(implClass);

        if (needsTransform == null) {
            boolean hasRmi = implClass.getAnnotation(RMI.class) != null;

            if (hasRmi) {
                // replace object
                ConnectionImpl connection = (ConnectionImpl) context.pipeline()
                                                                    .last();
                out.add(replaceFieldObjects(connection, msg, implClass));
            }
            else {
                transformObjectCache.put(implClass, Boolean.FALSE);
                out.add(msg);
            }
        }
        else if (needsTransform) {
            ConnectionImpl connection = (ConnectionImpl) context.pipeline()
                                                                .last();
            // replace object
            out.add(replaceFieldObjects(connection, msg, implClass));
        } else {
            out.add(msg);
        }
    }

    private
    Object replaceFieldObjects(final ConnectionImpl connection, final Object object, final Class<?> implClass) {
        Field[] rmiFields = fieldCache.get(implClass);
        int length = rmiFields.length;

        Object rmiObject = null;
        int[] rmiFieldIds = new int[length];
        for (int i = 0; i < length; i++) {
            Field field = rmiFields[i];

            // if it's an RMI object we want to write out a proxy object in the field instead of the actual object
            try {
                rmiObject = field.get(object);
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }

            if (rmiObject == null) {
                rmiFieldIds[i] = 0; // 0 means it was null
            }

            final Map<Object, Integer> localWeakCache = objectThreadLocals.get();

            Integer id = localWeakCache.get(rmiObject);
            if (id == null) {
                // duplicates are fine, as they represent the same object (as specified by the ID) on the remote side.
                int registeredId = connection.getRegisteredId(rmiObject);
                rmiFieldIds[i] = registeredId;
                localWeakCache.put(rmiObject, registeredId);
            } else {
                rmiFieldIds[i] = id;
            }
        }

        LocalRmiClassEncoder localRmiClassEncoder = new LocalRmiClassEncoder();
        localRmiClassEncoder.rmiObject = object;
        localRmiClassEncoder.rmiFieldIds = rmiFieldIds;

        return localRmiClassEncoder;
    }
}
