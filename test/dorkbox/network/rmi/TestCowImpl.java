package dorkbox.network.rmi;

/**
 *
 */
public
class TestCowImpl extends TestCowBaseImpl implements TestCow {
    private int moos;
    private final int id = ID_COUNTER.getAndIncrement();

    public
    TestCowImpl() {
    }

    @Override
    public
    void moo() {
        this.moos++;
        System.out.println("Moo! " + this.moos);
    }

    @Override
    public
    void moo(String value) {
        this.moos += 2;
        System.out.println("Moo! " + this.moos + " :" + value);
    }

    @Override
    public
    void moo(String value, long delay) {
        this.moos += 4;
        System.out.println("Moo! " + this.moos + " :" + value + " (" + delay + ")");
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public
    int id() {
        return id;
    }

    @Override
    public
    float slow() {
        System.out.println("Slowdown!!");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return 123.0F;
    }

    @Override
    public
    boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final TestCowImpl that = (TestCowImpl) o;

        return id == that.id;

    }

    @Override
    public
    int hashCode() {
        return id;
    }

    @Override
    public
    String toString() {
        return "Tada! This is a remote object!";
    }
}
