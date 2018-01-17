package dorkbox.network.rmi;

/**
 *
 */
public
interface TestCow extends TestCowBase {
    void moo();

    void moo(String value);

    void moo(String value, long delay);

    int id();

    float slow();
}
