package dorkbox.network.rmi

import dorkbox.network.connection.Connection
import dorkbox.network.connection.Connection_
import dorkbox.network.connection.EndPoint
import dorkbox.network.rmi.messages.ConnectionObjectCreateRequest
import dorkbox.network.rmi.messages.ConnectionObjectCreateResponse
import dorkbox.network.serialization.NetworkSerializationManager
import kotlinx.coroutines.CoroutineScope
import mu.KLogger

class RmiSupportConnection<C: Connection>(logger: KLogger,
                                          private val rmiGlobalSupport: RmiSupport<out Connection>,
                                          private val serialization: NetworkSerializationManager,
                                          actionDispatch: CoroutineScope) : RmiSupportCache(logger, actionDispatch) {

    /**
     * on the "client" to get a connection-specific remote object (that exists on the server)
     */
    fun <Iface> getRemoteObject(connection: Connection, endPoint: EndPoint<Connection_>, objectId: Int, interfaceClass: Class<Iface>): Iface {
        // this immediately returns BECAUSE the object must have already been created on the server (this is why we specify the rmiId)!

        // so we can just instantly create the proxy object (or get the cached one)
        var proxyObject = getProxyObject(objectId)
        if (proxyObject == null) {
            proxyObject = RmiSupport.createProxyObject(false, connection, serialization, rmiGlobalSupport, endPoint.type.simpleName, objectId, interfaceClass)
            saveProxyObject(objectId, proxyObject)
        }

        @Suppress("UNCHECKED_CAST")
        return proxyObject as Iface
    }


    /**
     * on the "client" to create a connection-specific remote object (that exists on the server)
     */
    suspend fun <Iface> createRemoteObject(connection: C, interfaceClassId: Int, callback: suspend (Iface) -> Unit) {
        val callbackId = rmiGlobalSupport.registerCallback(callback)

        // There is no rmiID yet, because we haven't created it!
        val message = ConnectionObjectCreateRequest(RmiUtils.packShorts(interfaceClassId, callbackId))

        // We use a callback to notify us when the object is ready. We can't "create this on the fly" because we
        // have to wait for the object to be created + ID to be assigned on the remote system BEFORE we can create the proxy instance here.

        // this means we are creating a NEW object on the server
        connection.send(message)
    }

    /**
     * called on "server"
     */
    internal suspend fun onConnectionObjectCreateRequest(
            endPoint: EndPoint<Connection_>, connection: Connection_, message: ConnectionObjectCreateRequest, logger: KLogger) {

        val interfaceClassId = RmiUtils.unpackLeft(message.packedIds)
        val callbackId = RmiUtils.unpackRight(message.packedIds)

        // We have to lookup the iface, since the proxy object requires it
        val implObject = endPoint.serialization.createRmiObject(interfaceClassId)
        val rmiId = registerImplObject(implObject)

        if (rmiId != RemoteObjectStorage.INVALID_RMI) {
            // this means we could register this object.

            // next, scan this object to see if there are any RMI fields
            RmiSupport.scanImplForRmiFields(logger, implObject) {
                registerImplObject(implObject)
            }
        } else {
            logger.error {
                "Trying to create an RMI object with the INVALID_RMI id!!"
            }
        }

        // we send the message ANYWAYS, because the client needs to know it did NOT succeed!
        connection.send(ConnectionObjectCreateResponse(RmiUtils.packShorts(rmiId, callbackId)))
    }
}
