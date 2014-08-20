package dorkbox.network.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The default thread factory with names.
 */
public class NamedThreadFactory implements ThreadFactory {
    private static final AtomicInteger poolId = new AtomicInteger();
    // permit this to be changed!
    /**
     * Stack size must be specified in bytes. Default is 8k
     */
    public static int stackSizeForNettyThreads = 8192;

    private final AtomicInteger nextId = new AtomicInteger();

    final ThreadGroup group;
    final String      namePrefix;

    public NamedThreadFactory(String poolNamePrefix, ThreadGroup group) {
        this.group = group;
        namePrefix = poolNamePrefix + '-' + poolId.incrementAndGet();
    }

    @Override
    public Thread newThread(Runnable r) {
        // stack size is arbitrary based on JVM implementation. Default is 0
        // 8k is the size of the android stack. Depending on the version of android, this can either change, or will always be 8k
        // To be honest, 8k is pretty reasonable for an asynchronous/event based system (32bit) or 16k (64bit)
        // Setting the size MAY or MAY NOT have any effect!!!
        Thread t = new Thread(group, r, namePrefix  + '-' + nextId.incrementAndGet(), stackSizeForNettyThreads);
        if (!t.isDaemon()) {
            t.setDaemon(true);
        }
        if (t.getPriority() != Thread.MAX_PRIORITY) {
            t.setPriority(Thread.MAX_PRIORITY);
        }
        return t;
    }
}