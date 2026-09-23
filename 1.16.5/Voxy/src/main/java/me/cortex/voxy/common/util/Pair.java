package me.cortex.voxy.common.util;


public final class Pair<A, B> {
    private final A left;
    private final B right;

    public Pair(A left, B right) {
        this.left = left;
        this.right = right;
    }

    public A left() { return this.left; }
    public B right() { return this.right; }

    @Override public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Pair)) return false;
        Pair o = (Pair) obj;
        return java.util.Objects.equals(this.left, o.left) && java.util.Objects.equals(this.right, o.right);
    }

    @Override public int hashCode() { return java.util.Objects.hash(this.left, this.right); }

    @Override public String toString() { return "Pair[left=" + this.left + ", right=" + this.right + "]"; }

}

