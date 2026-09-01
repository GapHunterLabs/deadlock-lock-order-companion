public class AccountService {
    private final Object lockA = new Object();
    private final Object lockB = new Object();

    // Flagged: acquires lockA then lockB -- transferReverse() below
    // acquires the same two locks in the opposite order, forming a cycle.
    void transfer() {
        synchronized (lockA) {
            synchronized (lockB) {
                // move funds A -> B
            }
        }
    }

    // Flagged: acquires lockB then lockA -- opposite order from transfer()
    // above. If two threads hit transfer()/transferReverse() at the same
    // time, each can hold one lock while waiting for the other: a classic
    // deadlock.
    void transferReverse() {
        synchronized (lockB) {
            synchronized (lockA) {
                // move funds B -> A
            }
        }
    }

    // Not flagged: same order as transfer() (lockA then lockB) -- no cycle.
    void audit() {
        synchronized (lockA) {
            synchronized (lockB) {
                // read-only audit
            }
        }
    }
}
