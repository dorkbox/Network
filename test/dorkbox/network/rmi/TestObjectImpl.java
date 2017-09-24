package dorkbox.network.rmi;

import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 */
public
class TestObjectImpl implements TestObject {
    // has to start at 1, because UDP/UDT method invocations ignore return values
    static final AtomicInteger ID_COUNTER = new AtomicInteger(1);

    public long value = System.currentTimeMillis();
    public int moos;
    private final int id = ID_COUNTER.getAndIncrement();

    public
    TestObjectImpl() {
    }

    @Override
    public
    void throwException() {
        throw new UnsupportedOperationException("Why would I do that?");
    }

    @Override
    public
    void moo() {
        this.moos++;
        System.out.println("Moo!");
    }

    @Override
    public
    void moo(String value) {
        this.moos += 2;
        System.out.println("Moo: " + value);
    }

    @Override
    public
    void moo(String value, long delay) {
        this.moos += 4;
        System.out.println("Moo: " + value + " (" + delay + ")");
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

        final TestObjectImpl that = (TestObjectImpl) o;

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
