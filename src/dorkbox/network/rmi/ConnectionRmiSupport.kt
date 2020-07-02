/*
 * Copyright 2019 dorkbox, llc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network.rmi

import dorkbox.network.connection.*
import dorkbox.network.rmi.messages.DynamicObjectRequest
import dorkbox.network.rmi.messages.DynamicObjectResponse
import dorkbox.network.rmi.messages.MethodRequest
import dorkbox.network.rmi.messages.MethodResponse
import dorkbox.network.serialization.NetworkSerializationManager
import dorkbox.util.classes.ClassHelper
import dorkbox.util.collections.LockFreeHashMap
import dorkbox.util.collections.LockFreeIntMap
import org.slf4j.Logger
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

class ConnectionRmiSupport internal constructor(private val rmiGlobal: RmiServer) {
    private val rmiLocal: RmiServer
    private val proxyIdCache: MutableMap<Int, RemoteObject?>
    private val proxyListeners: MutableList<OnMessageReceived<Connection, MethodResponse>>
    private val rmiRegistrationCallbacks: LockFreeIntMap<RemoteObjectCallback<*>>


    @Volatile
    private var rmiCallbackId = 0

    init {
        //     * @param executor
        //      *                 Sets the executor used to invoke methods when an invocation is received from a remote endpoint. By default, no
        //      *                 executor is set and invocations occur on the network thread, which should not be blocked for long, May be null.
        rmiLocal = RmiServer(rmiGlobal.logger, false)
        proxyIdCache = LockFreeHashMap()
        proxyListeners = CopyOnWriteArrayList()
        rmiRegistrationCallbacks = LockFreeIntMap()
    }

    fun close() {
        // proxy listeners are cleared in the removeAll() call (which happens BEFORE close)
        proxyIdCache.clear()
        rmiRegistrationCallbacks.clear()
    }

    /**
     * This will remove the invoke and invoke response listeners for this remote object
     */
    fun removeAllListeners() {
        proxyListeners.clear()
    }

    suspend fun <Iface> createRemoteObject(
            connection: ConnectionImpl,
            interfaceClass: Class<Iface>,
            callback: RemoteObjectCallback<Iface>) {

        require(interfaceClass.isInterface) { "Cannot create a proxy for RMI access. It must be an interface." }

        // because this is PER CONNECTION, there is no need for synchronize(), since there will not be any issues with concurrent
        // access, but there WILL be issues with thread visibility because a different worker thread can be called for different connections
        val nextRmiCallbackId = rmiCallbackId++
        rmiRegistrationCallbacks.put(nextRmiCallbackId, callback)
        val message = DynamicObjectRequest(interfaceClass, RmiServer.INVALID_RMI, nextRmiCallbackId)

        // We use a callback to notify us when the object is ready. We can't "create this on the fly" because we
        // have to wait for the object to be created + ID to be assigned on the remote system BEFORE we can create the proxy instance here.

        // this means we are creating a NEW object on the server, bound access to only this connection
        connection.send(message)
    }

    suspend fun <Iface> getRemoteObject(connection: ConnectionImpl, objectId: Int, callback: RemoteObjectCallback<Iface>) {

        check(objectId >= 0) { "Object ID cannot be < 0" }
        check(objectId < RmiServer.INVALID_RMI) { "Object ID cannot be >= " + RmiServer.INVALID_RMI }

        val iFaceClass = ClassHelper.getGenericParameterAsClassForSuperClass(RemoteObjectCallback::class.java, callback.javaClass, 0)

        // because this is PER CONNECTION, there is no need for synchronize(), since there will not be any issues with concurrent
        // access, but there WILL be issues with thread visibility because a different worker thread can be called for different connections
        val nextRmiCallbackId = rmiCallbackId++
        rmiRegistrationCallbacks.put(nextRmiCallbackId, callback)
        val message = DynamicObjectRequest(iFaceClass, objectId, nextRmiCallbackId)

        // We use a callback to notify us when the object is ready. We can't "create this on the fly" because we
        // have to wait for the object to be created + ID to be assigned on the remote system BEFORE we can create the proxy instance here.

        // this means we are getting an EXISTING object on the server, bound access to only this connection
        connection.send(message)
    }

    /**
     * Manages the RMI stuff for a connection. Will register/invoke/etc on the RMI object
     */
    suspend fun manage(connection: Connection_, message: Any, logger: Logger) {
        when (message) {
            is MethodRequest -> {
                val objectID = message.objectId

                // have to make sure to get the correct object (global vs local)
                // This is what is overridden when registering interfaces/classes for RMI.
                // objectID is the interface ID, and this returns the implementation ID.
                val target = getImplementationObject(objectID)
                if (target == null) {
                    logger.warn("Ignoring remote invocation request for unknown object ID: {}", objectID)
                    return
                }

                try {
                    val result = RmiServer.invoke(connection, target, message, logger)
                    if (result != null) {
                        // System.err.println("Sending: " + invokeMethod.responseID);
                        connection.send(result)
                    }
                } catch (e: IOException) {
                    logger.error("Unable to invoke method.", e)
                }
            }
            is MethodResponse -> {
                for (proxyListener in proxyListeners) {
                    proxyListener.received(connection, message)
                }
            }
            is DynamicObjectRequest -> {
                // Check if we are creating a new REMOTE object. This check is always first.
                if (message.rmiId == RmiServer.INVALID_RMI) {
                    // THIS IS ON THE REMOTE CONNECTION (where the object will really exist as an implementation)
                    //
                    // CREATE a new ID, and register the ID and new object (must create a new one) in the object maps

                    // have to lookup the implementation class
                    val serialization = connection.endPoint().serialization

                    // For network connections, the interface class kryo ID == implementation class kryo ID, so they switch automatically.
                    val registrationResult = createNewRmiObject(serialization, message.interfaceClass, message.callbackId, logger)
                    connection.send(registrationResult)
                    // connection transport is flushed in calling method (don't need to do it here)
                } else {
                    // THIS IS ON THE REMOTE CONNECTION (where the object implementation will really exist)
                    //
                    // GET a LOCAL rmi object, if none get a specific, GLOBAL rmi object (objects that are not bound to a single connection).
                    val implementationObject = getImplementationObject(message.rmiId)
                    connection.send(DynamicObjectResponse(message.interfaceClass, message.rmiId, message.callbackId, implementationObject!!))
                    // connection transport is flushed in calling method (don't need to do it here)
                }
            }
            is DynamicObjectResponse -> {
                if (message.rmiId == RmiServer.INVALID_RMI) {
                    logger.error("RMI ID '{}' is invalid. Unable to create RMI object.", message.rmiId)
                }

                // this is the response.
                // THIS IS ON THE LOCAL CONNECTION SIDE, which is the side that called 'getRemoteObject()'
                @Suppress("UNCHECKED_CAST")
                val callback: RemoteObjectCallback<Any> = rmiRegistrationCallbacks.remove(message.callbackId) as RemoteObjectCallback<Any>

                try {
                    callback.created(message.remoteObject!!)
                } catch (e: Exception) {
                    logger.error("Error getting or creating the remote object ${message.interfaceClass}", e)
                }
            }
        }
    }

    /**
     * Used by RMI by the LOCAL side when setting up the to fetch an object for the REMOTE side
     *
     * @return the registered ID for a specific object, or RmiBridge.INVALID_RMI if there was no ID.
     */
    fun <T> getRegisteredId(`object`: T): Int {
        // always check global before checking local, because less contention on the synchronization
        val objectId = rmiGlobal.getRegisteredId(`object`)
        return if (objectId != RmiServer.INVALID_RMI) {
            objectId
        } else {
            // might return RmiBridge.INVALID_RMI;
            rmiLocal.getRegisteredId(`object`)
        }
    }

    /**
     * This is used by RMI for the SERVER side, to get the implementation
     *
     * @param objectId this is the RMI object ID
     */
    fun getImplementationObject(objectId: Int): Any? {
        return if (RmiServer.isGlobal(objectId)) {
            rmiGlobal.getRegisteredObject(objectId)
        } else {
            rmiLocal.getRegisteredObject(objectId)
        }
    }

    /**
     * Removes a proxy object from the system
     */
    fun removeProxyObject(rmiProxyHandler: RmiClient) {
        proxyListeners.remove(rmiProxyHandler.listener)
        proxyIdCache.remove(rmiProxyHandler.rmiObjectId)
    }

    /**
     * For network connections, the interface class kryo ID == implementation class kryo ID, so they switch automatically.
     * For local connections, we have to switch it appropriately in the LocalRmiProxy
     */
    fun createNewRmiObject(serialization: NetworkSerializationManager, interfaceClass: Class<*>, callbackId: Int, logger: Logger): DynamicObjectResponse {
        var kryo: KryoExtra? = null
        var remoteObject: Any? = null
        var rmiId = 0
        val implementationClass = serialization.getRmiImpl(interfaceClass)

        try {
            kryo = serialization.takeKryo()

            // because the INTERFACE is what is registered with kryo (not the impl) we have to temporarily permit unregistered classes (which have an ID of -1)
            // so we can cache the instantiator for this class.
            val registrationRequired = kryo.isRegistrationRequired
            kryo.isRegistrationRequired = false

            // this is what creates a new instance of the impl class, and stores it as an ID.
            remoteObject = kryo.newInstance(implementationClass)
            if (registrationRequired) {
                // only if it's different should we call this again.
                kryo.isRegistrationRequired = true
            }
            rmiId = rmiLocal.register(remoteObject)

            if (rmiId == RmiServer.INVALID_RMI) {
                // this means that there are too many RMI ids (either global or connection specific!)
                remoteObject = null
            } else {
                // if we are invalid, skip going over fields that might also be RMI objects, BECAUSE our object will be NULL!

                // the @Rmi annotation allows an RMI object to have fields with objects that are ALSO RMI objects
                val classesToCheck = LinkedList<Map.Entry<Class<*>, Any?>>()
                classesToCheck.add(AbstractMap.SimpleEntry(implementationClass, remoteObject))

                var remoteClassObject: Map.Entry<Class<*>, Any?>
                while (!classesToCheck.isEmpty()) {
                    remoteClassObject = classesToCheck.removeFirst()

                    // we have to check the IMPLEMENTATION for any additional fields that will have proxy information.
                    // we use getDeclaredFields() + walking the object hierarchy, so we get ALL the fields possible (public + private).
                    for (field in remoteClassObject.key.declaredFields) {
                        if (field.getAnnotation(Rmi::class.java) != null) {
                            val type = field.type
                            if (!type.isInterface) {
                                // the type must be an interface, otherwise RMI cannot create a proxy object
                                logger.error("Error checking RMI fields for: {}.{} -- It is not an interface!",
                                        remoteClassObject.key,
                                        field.name)
                                continue
                            }

                            val prev = field.isAccessible
                            field.isAccessible = true

                            val o: Any
                            try {
                                o = field[remoteClassObject.value]
                                rmiLocal.register(o)
                                classesToCheck.add(AbstractMap.SimpleEntry(type, o))
                            } catch (e: IllegalAccessException) {
                                logger.error("Error checking RMI fields for: {}.{}", remoteClassObject.key, field.name, e)
                            } finally {
                                field.isAccessible = prev
                            }
                        }
                    }


                    // have to check the object hierarchy as well
                    val superclass = remoteClassObject.key
                            .superclass
                    if (superclass != null && superclass != Any::class.java) {
                        classesToCheck.add(AbstractMap.SimpleEntry(superclass, remoteClassObject.value))
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error registering RMI class $implementationClass", e)
        } finally {
            if (kryo != null) {
                // we use kryo to create a new instance - so only return it on error or when it's done creating a new instance
                serialization.returnKryo(kryo)
            }
        }

        return DynamicObjectResponse(interfaceClass, rmiId, callbackId, remoteObject!!)
    }

    /**
     * Warning. This is an advanced method. You should probably be using [Connection.createRemoteObject]
     *
     *
     *
     *
     * Returns a proxy object that implements the specified interface, and the methods invoked on the proxy object will be invoked
     * remotely.
     *
     *
     * Methods that return a value will throw [TimeoutException] if the response is not received with the [ ][RemoteObject.setResponseTimeout].
     *
     *
     * If [non-blocking][RemoteObject.setAsync] is false (the default), then methods that return a value must not be
     * called from the update thread for the connection. An exception will be thrown if this occurs. Methods with a void return value can be
     * called on the update thread.
     *
     *
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side will
     * have the proxy object replaced with the registered object.
     *
     * @see RemoteObject
     *
     * @param rmiId this is the remote object ID (assigned by RMI). This is NOT the kryo registration ID
     * @param iFace this is the RMI interface
     */
    fun getProxyObject(connection: Connection_, rmiId: Int, iFace: Class<*>?): RemoteObject {
        requireNotNull(iFace) { "iface cannot be null." }
        require(iFace.isInterface) { "iface must be an interface." }

        // we want to have a connection specific cache of IDs
        // because this is PER CONNECTION, there is no need for synchronize(), since there will not be any issues with concurrent
        // access, but there WILL be issues with thread visibility because a different worker thread can be called for different connections
        var remoteObject = proxyIdCache[rmiId]
        if (remoteObject == null) {
            // duplicates are fine, as they represent the same object (as specified by the ID) on the remote side.

            // the ACTUAL proxy is created in the connection impl. Our proxy handler MUST BE suspending because of:
            //  1) how we send data on the wire
            //  2) how we must (sometimes) wait for a response
            val proxyObject = RmiClient(connection, this, rmiId, iFace)
            proxyListeners.add(proxyObject.listener)

            // This is the interface inheritance by the proxy object
            val interfaces: Array<Class<*>> = arrayOf(RemoteObject::class.java, iFace)

            remoteObject = Proxy.newProxyInstance(RmiServer::class.java.classLoader, interfaces, proxyObject) as RemoteObject
            proxyIdCache[rmiId] = remoteObject
        }
        return remoteObject
    }




}
