package dorkbox.network.other

import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation

@FunctionalInterface
private interface SuspendFunction {
    suspend fun invoke(): Any?
}

// we access this via reflection, because we have to be able to pass the continuation object as the LAST parameter. We don't know what
// the method signature actually is, so this is necessary
private val SuspendMethod = SuspendFunction::class.java.methods[0]

internal inline fun handleInvocationTargetException(action: () -> Any?): Any? {
    return try {
        action()
    } catch (e: InvocationTargetException) {
        throw e.cause!!
    }
}


internal fun invokeSuspendFunction(continuation: Continuation<*>, suspendFunction: suspend () -> Any?): Any? {
    return handleInvocationTargetException {
        SuspendMethod.invoke(
                object : SuspendFunction {
                    override suspend fun invoke() = suspendFunction()
                },
                continuation
        )
    }
}

