/*
 * Copyright 2019 dorkbox, llc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network.rmi;

import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 */
public
class TestCowImpl extends TestCowBaseImpl implements TestCow {
    // has to start at 1, because UDP method invocations ignore return values
    static final AtomicInteger ID_COUNTER = new AtomicInteger(1);

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
        System.out.println("Moo! " + this.moos + ": " + value);
    }

    @Override
    public
    void moo(String value, long delay) {
        this.moos += 4;
        System.out.println("Moo! " + this.moos + ": " + value + " (" + delay + ")");
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
