package dorkbox.network.rmi;

/**
 *
 */
public
class MessageWithTestCow {
    public int number;
    public String text;
    private TestCow testCow;

    private
    MessageWithTestCow() {
        // for kryo
    }

    public
    MessageWithTestCow(final TestCow test) {
        testCow = test;
    }

    public
    TestCow getTestCow() {
        return testCow;
    }
}
