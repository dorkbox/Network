package dorkbox.network.other;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function1;

/**
 * Class to access suspending invocation of methods from kotlin...
 *
 * ULTIMATELY, this is all java bytecode, and the bytecode signature here matches what kotlin expects. The generics type information is
 * discarded at compile time.
 */
public
class SuspendFunctionAccess {
    @SuppressWarnings("unchecked")
    @Nullable
    public static
    Object invokeSuspendFunction(@NotNull final Object suspendFunction, @NotNull final Continuation<?> continuation) {
        Function1<? super Continuation<? super Object>, ?> suspendFunction1 = (Function1<? super Continuation<? super Object>, ?>) suspendFunction;
        return suspendFunction1.invoke((Continuation<? super Object>) continuation);
    }
}
