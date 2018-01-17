package dorkbox.network.rmi;

import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 */
public
class TestCowBaseImpl implements TestCowBase {
    // has to start at 1, because UDP method invocations ignore return values
    static final AtomicInteger ID_COUNTER = new AtomicInteger(1);

    public
    TestCowBaseImpl() {
    }

    @Override
    public
    void throwException() {
        throw new UnsupportedOperationException("Why would I do that?");
    }

    public
    int id() {
        return Integer.MAX_VALUE;
    }
}
