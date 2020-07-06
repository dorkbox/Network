package dorkbox.network.rmi

import dorkbox.util.collections.LockFreeIntMap
import kotlinx.coroutines.CoroutineScope
import mu.KLogger

/**
 * Cache for implementation and proxy objects.
 *
 * The impl/proxy objects CANNOT be stored in the same data structure, because their IDs are not tied to the same ID source (and there
 * would be conflicts in the data structure)
 */
open class RmiSupportCache(logger: KLogger, actionDispatch: CoroutineScope) {

    private val responseStorage = RmiResponseStorage(actionDispatch)
    private val implObjects = RemoteObjectStorage(logger)
    private val proxyObjects = LockFreeIntMap<RemoteObject>()

    fun registerImplObject(rmiObject: Any): Int {
        return implObjects.register(rmiObject)
    }

    fun getImplObject(rmiId: Int): Any {
        return implObjects[rmiId]
    }

    fun removeImplObject(rmiId: Int) {
        implObjects.remove(rmiId) as Any
    }

    /**
     * Removes a proxy object from the system
     */
    fun removeProxyObject(rmiId: Int) {
        proxyObjects.remove(rmiId)
    }

    fun getProxyObject(rmiId: Int): RemoteObject? {
        return proxyObjects[rmiId]
    }

    fun saveProxyObject(rmiId: Int, remoteObject: RemoteObject) {
        proxyObjects.put(rmiId, remoteObject)
    }

    fun getResponseStorage(): RmiResponseStorage {
        return responseStorage
    }

    open fun close() {
        implObjects.close()
        proxyObjects.clear()
        responseStorage.close()
    }
}
