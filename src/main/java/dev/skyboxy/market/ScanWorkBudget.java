package dev.skyboxy.market;

/** Counts scanner block reads/comparisons for a single server tick. */
final class ScanWorkBudget {
    private final int limit;
    private int used;

    ScanWorkBudget(int limit) {
        if (limit < 0) throw new IllegalArgumentException("Scan work limit cannot be negative");
        this.limit = limit;
    }

    boolean tryConsume() {
        if (used >= limit) return false;
        used++;
        return true;
    }

    int used() {
        return used;
    }

    int remaining() {
        return limit - used;
    }
}
