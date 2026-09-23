package me.cortex.voxy.common.util;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Java 8 stand-in for {@code java.lang.ref.Cleaner}, which was only added in Java 9.
 * <p>
 * Voxy leans on Cleaner to release native allocations (LMDB envs, zstd contexts, GL buffers)
 * when their owner is collected. Jabel lifts the language level for us but cannot conjure
 * runtime classes, and Forge 36.x runs on a Java 8 JVM, so the type has to be supplied here.
 * <p>
 * The API mirrors the JDK's closely enough that call sites are unchanged: {@link #create()},
 * {@link #register(Object, Runnable)} and {@link Cleanable#clean()} behave the same. As in the
 * JDK, the cleaning action must not hold a strong reference to the object it is registered
 * for, or that object can never become phantom-reachable and the action will never run.
 */
public final class Cleaner {
    /** Handle to a registered action, exactly as {@code Cleaner.Cleanable}. */
    public interface Cleanable {
        /** Runs the action now (at most once) and unregisters it. */
        void clean();
    }

    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
    // Keeps the phantom refs themselves reachable; without this they would be collected
    // before ever being enqueued and the actions would silently never run.
    private final Set<CleanableRef> registered =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private Cleaner() {}

    public static Cleaner create() {
        Cleaner cleaner = new Cleaner();
        Thread t = new Thread(cleaner::drain, "Voxy Cleaner");
        t.setDaemon(true);
        t.start();
        return cleaner;
    }

    public Cleanable register(Object obj, Runnable action) {
        if (obj == null || action == null) {
            throw new NullPointerException("obj and action must be non-null");
        }
        CleanableRef ref = new CleanableRef(obj, action);
        this.registered.add(ref);
        return ref;
    }

    private void drain() {
        while (true) {
            try {
                CleanableRef ref = (CleanableRef) this.queue.remove();
                ref.clean();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // A failing action must not take the cleaning thread down with it, or every
                // later registration leaks.
                t.printStackTrace();
            }
        }
    }

    private final class CleanableRef extends PhantomReference<Object> implements Cleanable {
        private Runnable action;

        public CleanableRef(Object referent, Runnable action) {
            super(referent, Cleaner.this.queue);
            this.action = action;
        }

        @Override
        public void clean() {
            Runnable toRun;
            synchronized (this) {
                toRun = this.action;
                this.action = null;      // at-most-once, whether via clean() or the queue
            }
            if (toRun == null) {
                return;
            }
            Cleaner.this.registered.remove(this);
            // Available since Java 8; lets the referent be reclaimed promptly.
            ((Reference<?>) this).clear();
            toRun.run();
        }
    }
}
