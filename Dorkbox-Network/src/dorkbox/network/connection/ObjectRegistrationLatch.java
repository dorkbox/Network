package dorkbox.network.connection;

import java.util.concurrent.CountDownLatch;

class ObjectRegistrationLatch {
    final CountDownLatch latch = new CountDownLatch(1);
    Object remoteObject;
    boolean hasError = false;
}
