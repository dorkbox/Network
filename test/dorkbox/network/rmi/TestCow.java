package dorkbox.network.rmi;

/**
 *
 */
public
interface TestCow {
    void throwException();

    void moo();

    void moo(String value);

    void moo(String value, long delay);

    int id();

    float slow();
}
