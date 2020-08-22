package dorkboxTest.network.rmi
import junit.framework.TestCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
// https://gist.github.com/hiperbou/283e6531fb2b71b324d5064f820df889
// https://github.com/Kotlin/kotlinx.coroutines/pull/1667

class SuspendProxyTest : TestCase() {
    interface Adder{
        suspend fun add(a:Int, b:Int):Int
        fun addSync(a:Int, b:Int):Int
    }

    class SuspendHandler(private val delegate:Adder):InvocationHandler {
        override fun invoke(proxy: Any, method: Method, arguments: Array<Any>): Any {
            val suspendCoroutineObject = arguments?.lastOrNull()
            return if (suspendCoroutineObject is Continuation<*>) {
                val parameters = arguments.copyOf(arguments.size - 1)
                val continuation = suspendCoroutineObject as Continuation<Any?>
                val retVal = method.invoke(delegate, *parameters, Continuation<Any?>(EmptyCoroutineContext) {
                    val continuationResult = it.getOrNull()
                    continuation.resume(continuationResult)
                })

                retVal
            } else {
                val parameters = arguments ?: arrayOf()
                method.invoke(delegate, *parameters)
            }
        }
    }

    fun testCacheSimple() = runBlocking {
        val delegate = object:Adder {
            override suspend fun add(a:Int, b:Int):Int {
                println("delay 1")
                delay(1000)
                println("delay 2")
                delay(1000)
                println("delay 3")
                delay(1000)
                return a + b
            }

            override fun addSync(a:Int, b:Int):Int {
                return a + b
            }
        }

        val adder = Proxy.newProxyInstance(
                Adder::class.java.classLoader,
                arrayOf(Adder::class.java),
                SuspendHandler(delegate)
        ) as Adder

        println("starting")
        val result = adder.add(1,3)
        println("done 1")
        val result2 = adder.addSync(1,3)
        println("done 2")

        println(result)
        println(result2)
    }
}
