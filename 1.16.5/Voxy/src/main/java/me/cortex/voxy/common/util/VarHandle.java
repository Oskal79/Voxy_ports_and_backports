package me.cortex.voxy.common.util;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * Java 8 stand-in for the subset of {@code java.lang.invoke.VarHandle} that voxy uses.
 * <p>
 * VarHandle arrived in Java 9 and Forge 36.x runs on Java 8, so the atomic field access in
 * {@code WorldSection}, {@code WorldEngine} and {@code ActiveSectionTracker} needs a backing
 * implementation here. Call sites are unchanged apart from construction: the migration script
 * rewrites {@code MethodHandles.lookup().findVarHandle(C.class, "f", T.class)} to
 * {@link #find(Class, String, Class)}.
 * <p>
 * int, long and reference fields go straight to the corresponding {@code Unsafe} intrinsics.
 * Java 8's {@code Unsafe} has no compare-and-swap for {@code byte} or {@code boolean} -- those
 * only gained CAS in Java 9 -- so those widths synchronize on the owning object instead. That
 * is correct but not lock-free; it is only used for the {@code nonEmptyChildren},
 * {@code isDirty} and {@code inSaveQueue} flags, which are low-contention.
 * <p>
 * Values are passed and returned boxed, matching how the original call sites already cast
 * results (e.g. {@code (boolean) IS_DIRTY_HANDLE.getAndSet(this, false)}).
 */
public final class VarHandle {
    private static final Unsafe UNSAFE;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final int T_INT = 0, T_LONG = 1, T_BYTE = 2, T_BOOLEAN = 3, T_REF = 4;

    private final long offset;
    private final int kind;

    private VarHandle(long offset, int kind) {
        this.offset = offset;
        this.kind = kind;
    }

    // VarHandle's static fences, backed by the equivalents Java 8's Unsafe already provides.
    public static void fullFence() { UNSAFE.fullFence(); }
    public static void loadLoadFence() { UNSAFE.loadFence(); }
    public static void storeStoreFence() { UNSAFE.storeFence(); }
    public static void acquireFence() { UNSAFE.loadFence(); }
    public static void releaseFence() { UNSAFE.storeFence(); }

    /**
     * Mirrors {@code MethodHandles.Lookup#findVarHandle}, including its checked exceptions --
     * call sites wrap construction in a try/catch for them, and swallowing them here would make
     * those catch blocks unreachable and fail to compile.
     */
    public static VarHandle find(Class<?> owner, String name, Class<?> type)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        int kind;
        if (type == int.class) kind = T_INT;
        else if (type == long.class) kind = T_LONG;
        else if (type == byte.class) kind = T_BYTE;
        else if (type == boolean.class) kind = T_BOOLEAN;
        else if (!type.isPrimitive()) kind = T_REF;
        else throw new IllegalArgumentException("unsupported VarHandle field type: " + type);
        return new VarHandle(UNSAFE.objectFieldOffset(field), kind);
    }

    public Object get(Object o) {
        switch (this.kind) {
            case T_INT:     return UNSAFE.getIntVolatile(o, this.offset);
            case T_LONG:    return UNSAFE.getLongVolatile(o, this.offset);
            case T_BYTE:    return UNSAFE.getByteVolatile(o, this.offset);
            case T_BOOLEAN: return UNSAFE.getBooleanVolatile(o, this.offset);
            default:        return UNSAFE.getObjectVolatile(o, this.offset);
        }
    }

    public void set(Object o, Object v) {
        switch (this.kind) {
            case T_INT:     UNSAFE.putIntVolatile(o, this.offset, ((Number) v).intValue()); break;
            case T_LONG:    UNSAFE.putLongVolatile(o, this.offset, ((Number) v).longValue()); break;
            case T_BYTE:    UNSAFE.putByteVolatile(o, this.offset, ((Number) v).byteValue()); break;
            case T_BOOLEAN: UNSAFE.putBooleanVolatile(o, this.offset, (Boolean) v); break;
            default:        UNSAFE.putObjectVolatile(o, this.offset, v); break;
        }
    }

    public boolean compareAndSet(Object o, Object expected, Object value) {
        switch (this.kind) {
            case T_INT:
                return UNSAFE.compareAndSwapInt(o, this.offset,
                        ((Number) expected).intValue(), ((Number) value).intValue());
            case T_LONG:
                return UNSAFE.compareAndSwapLong(o, this.offset,
                        ((Number) expected).longValue(), ((Number) value).longValue());
            case T_REF:
                return UNSAFE.compareAndSwapObject(o, this.offset, expected, value);
            default:
                // No byte/boolean CAS before Java 9.
                synchronized (o) {
                    if (!this.get(o).equals(expected)) return false;
                    this.set(o, value);
                    return true;
                }
        }
    }

    /** Returns the witness value: the value present before the attempt, as VarHandle does. */
    public Object compareAndExchange(Object o, Object expected, Object value) {
        switch (this.kind) {
            case T_INT:
            case T_LONG:
            case T_REF:
                for (;;) {
                    Object witness = this.get(o);
                    if (!witness.equals(expected)) return witness;
                    if (this.compareAndSet(o, expected, value)) return expected;
                }
            default:
                synchronized (o) {
                    Object witness = this.get(o);
                    if (witness.equals(expected)) this.set(o, value);
                    return witness;
                }
        }
    }

    /** Returns the previous value. */
    public Object getAndSet(Object o, Object value) {
        switch (this.kind) {
            case T_INT:  return UNSAFE.getAndSetInt(o, this.offset, ((Number) value).intValue());
            case T_LONG: return UNSAFE.getAndSetLong(o, this.offset, ((Number) value).longValue());
            case T_REF:  return UNSAFE.getAndSetObject(o, this.offset, value);
            default:
                synchronized (o) {
                    Object prev = this.get(o);
                    this.set(o, value);
                    return prev;
                }
        }
    }

    /** Returns the previous value. */
    public Object getAndAdd(Object o, Object delta) {
        switch (this.kind) {
            case T_INT:  return UNSAFE.getAndAddInt(o, this.offset, ((Number) delta).intValue());
            case T_LONG: return UNSAFE.getAndAddLong(o, this.offset, ((Number) delta).longValue());
            case T_BYTE:
                synchronized (o) {
                    byte prev = (Byte) this.get(o);
                    this.set(o, (byte) (prev + ((Number) delta).byteValue()));
                    return prev;
                }
            default:
                throw new UnsupportedOperationException("getAndAdd on non-numeric field");
        }
    }
}
