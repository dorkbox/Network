package dorkbox.network.rmi;

/**
 *
 */
public
class TestCowBaseImpl implements TestCowBase {
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
