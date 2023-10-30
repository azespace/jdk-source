/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent.atomic;
import java.util.function.LongBinaryOperator;
import java.util.function.DoubleBinaryOperator;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A package-local class holding common representation and mechanics
 * for classes supporting dynamic striping on 64bit values. The class
 * extends Number so that concrete subclasses must publicly do so.
 */
@SuppressWarnings("serial")
abstract class Striped64 extends Number {
    /*
     * This class maintains a lazily-initialized table of atomically
     * updated variables, plus an extra "base" field. The table size
     * is a power of two. Indexing uses masked per-thread hash codes.
     * Nearly all declarations in this class are package-private,
     * accessed directly by subclasses.
     *
     * Table entries are of class Cell; a variant of AtomicLong padded
     * (via @sun.misc.Contended) to reduce cache contention. Padding
     * is overkill for most Atomics because they are usually
     * irregularly scattered in memory and thus don't interfere much
     * with each other. But Atomic objects residing in arrays will
     * tend to be placed adjacent to each other, and so will most
     * often share cache lines (with a huge negative performance
     * impact) without this precaution.
     *
     * In part because Cells are relatively large, we avoid creating
     * them until they are needed.  When there is no contention, all
     * updates are made to the base field.  Upon first contention (a
     * failed CAS on base update), the table is initialized to size 2.
     * The table size is doubled upon further contention until
     * reaching the nearest power of two greater than or equal to the
     * number of CPUS. Table slots remain empty (null) until they are
     * needed.
     *
     * A single spinlock ("cellsBusy") is used for initializing and
     * resizing the table, as well as populating slots with new Cells.
     * There is no need for a blocking lock; when the lock is not
     * available, threads try other slots (or the base).  During these
     * retries, there is increased contention and reduced locality,
     * which is still better than alternatives.
     *
     * The Thread probe fields maintained via ThreadLocalRandom serve
     * as per-thread hash codes. We let them remain uninitialized as
     * zero (if they come in this way) until they contend at slot
     * 0. They are then initialized to values that typically do not
     * often conflict with others.  Contention and/or table collisions
     * are indicated by failed CASes when performing an update
     * operation. Upon a collision, if the table size is less than
     * the capacity, it is doubled in size unless some other thread
     * holds the lock. If a hashed slot is empty, and lock is
     * available, a new Cell is created. Otherwise, if the slot
     * exists, a CAS is tried.  Retries proceed by "double hashing",
     * using a secondary hash (Marsaglia XorShift) to try to find a
     * free slot.
     *
     * The table size is capped because, when there are more threads
     * than CPUs, supposing that each thread were bound to a CPU,
     * there would exist a perfect hash function mapping threads to
     * slots that eliminates collisions. When we reach capacity, we
     * search for this mapping by randomly varying the hash codes of
     * colliding threads.  Because search is random, and collisions
     * only become known via CAS failures, convergence can be slow,
     * and because threads are typically not bound to CPUS forever,
     * may not occur at all. However, despite these limitations,
     * observed contention rates are typically low in these cases.
     *
     * It is possible for a Cell to become unused when threads that
     * once hashed to it terminate, as well as in the case where
     * doubling the table causes no thread to hash to it under
     * expanded mask.  We do not try to detect or remove such cells,
     * under the assumption that for long-running instances, observed
     * contention levels will recur, so the cells will eventually be
     * needed again; and for short-lived ones, it does not matter.
     */

    /**
     * Padded variant of AtomicLong supporting only raw accesses plus CAS.
     *
     * JVM intrinsics note: It would be possible to use a release-only
     * form of CAS here, if it were provided.
     */
    // 该注解@sun.misc.Contended用于处理CPU伪共享 具体可以看笔记CPU理论文章
    // 并发安全 允许在多线程环境中进行CAS操作 确保对Value的原子操作
    @sun.misc.Contended static final class Cell {
        // 需要进行原子操作的值
        volatile long value;
        Cell(long x) { value = x; }
        final boolean cas(long cmp, long val) {
            // 偏移量：在计算机科学中，表示的是某个参照点到达另一个点的距离或差值 偏移量可以用于直接访问对象的字段
            // 第一个值是对象，主要用于获取对象中某个值的基地址(对象的某个字段的起始地址)，可以理解为一个参照物
            // 第二个值是字段偏移量，也是从对象中获取，偏移量=基地址+字段偏移量 就可以获取到字段的真实内存地址，从而可以直接访问该字段
            // 第三个值是期望值，如果对象中的字段值与期望值相等，则更新该字段的值为第四个值
            return UNSAFE.compareAndSwapLong(this, valueOffset, cmp, val);
        }
        private static final sun.misc.Unsafe UNSAFE;
        private static final long valueOffset;
        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> ak = Cell.class;
                //
                valueOffset = UNSAFE.objectFieldOffset
                    (ak.getDeclaredField("value"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /** Number of CPUS, to place bound on table size */
    // CPU核心数
    static final int NCPU = Runtime.getRuntime().availableProcessors();

    /**
     * Table of cells. When non-null, size is a power of 2.
     */
    // Cell数组，长度为2的幂次方
    transient volatile Cell[] cells;

    /**
     * Base value, used mainly when there is no contention, but also as
     * a fallback during table initialization races. Updated via CAS.
     */
    // 基础值，当没有竞争时使用，也作为表初始化时的后备值。通过CAS更新
    transient volatile long base;

    /**
     * Spinlock (locked via CAS) used when resizing and/or creating Cells.
     */
    // 自旋锁标识，用于扩容和创建Cell时使用 0表示未加锁，1表示加锁
    transient volatile int cellsBusy;

    /**
     * Package-private default constructor
     */
    Striped64() {
    }

    /**
     * CASes the base field.
     */
    // 没有出现竞争的时候 通过CAS操作更新基础 base 值
    // 可能出现写失败,写失败就开始创建 cells 了
    final boolean casBase(long cmp, long val) {
        return UNSAFE.compareAndSwapLong(this, BASE, cmp, val);
    }

    /**
     * CASes the cellsBusy field from 0 to 1 to acquire lock.
     */
    // cas 操作 cellsbusy 的值 如果是 0 则修改为 1 表示加锁成功
    final boolean casCellsBusy() {
        return UNSAFE.compareAndSwapInt(this, CELLSBUSY, 0, 1);
    }

    /**
     * Returns the probe value for the current thread.
     * Duplicated from ThreadLocalRandom because of packaging restrictions.
     */
    // 获取当前线程的 hash 值 用作 cells 数组的下标计算
    static final int getProbe() {
        return UNSAFE.getInt(Thread.currentThread(), PROBE);
    }

    /**
     * Pseudo-randomly advances and records the given probe value for the
     * given thread.
     * Duplicated from ThreadLocalRandom because of packaging restrictions.
     */
    // 重置当前线程的hash值(计算cells数组下标)
    static final int advanceProbe(int probe) {
        // 为了减少竞争条件 采用了一种称为xorshift的伪随机数生成算法，用来生成伪随机的探测值
        // 用于线程调度或锁分配，从而减少高并发下的竞争条件并提高性能
        // 这是一种常见的并发编程技术
        probe ^= probe << 13;   // 位操作左移13位 生成伪随机数
        probe ^= probe >>> 17;  // 位操作右移17位 增强随机性
        probe ^= probe << 5;    // 位操作左移5位 增强随机性
        UNSAFE.putInt(Thread.currentThread(), PROBE, probe); // 将生成的probe值存储在当前线程的PROBE字段中
        return probe;
    }

    /**
     * Handles cases of updates involving initialization, resizing,
     * creating new Cells, and/or contention. See above for
     * explanation. This method suffers the usual non-modularity
     * problems of optimistic retry code, relying on rechecked sets of
     * reads.
     *
     * @param x the value
     * @param fn the update function, or null for add (this convention
     * avoids the need for an extra field or function in LongAdder).
     * @param wasUncontended false if CAS failed before call
     */
    // x 为需要累加的值 fn 为累加函数 wasUncontended 为是否发生竞争 false 表示发生竞争
    final void longAccumulate(long x, LongBinaryOperator fn,
                              boolean wasUncontended) {
        // 线程探测值 用于计算cells数组下标
        int h;
        // 如果当前线程的hash值是0(没有分配过线程第一次进入) 则重新生成hash值
        if ((h = getProbe()) == 0) {
            // 为当前线程生成伪随机数，解决Random的CAS多线程竞争性能问题 该类使用到了线性同余法可以参考笔记数学理论随机数章节 X[n + 1] = (a * X[n] + c) mod m
            ThreadLocalRandom.current(); // force initialization
            // 重新获取probe值  由于是第一次进来，该线程当前是没有竞争的，所以设置为true
            // 这里的h肯定不是 0 因为已经被随机了 那么数组的第一个位置一定是空的
            // 因为只有h = 0 才会落在第一个位置，0与任何数异或都是0 也就表示一定落在了cells数组的第一个位置 那么这里的第一个位置空出来了干什么呢
            // 因为cells数组是延迟初始化的，会在一开始直接初始化2个长度的cells数组，所以这里的第一个位置是空的
            // 后续用于执行 cells[0] = new Cell(x); 也就是无锁的更新base值
            h = getProbe();
            wasUncontended = true;
        }
        // 扩容标识 false 一定不扩容 true 可能会扩容
        boolean collide = false;
        for (;;) {
            // as cells数组引用,a 当前线程的cell,n 数组长度,v 期望值
            Cell[] as; Cell a; int n; long v;
            // cells 已经初始化了 开始操作cells数组 否则 进行初始化操作
            if ((as = cells) != null && (n = as.length) > 0) {
                // 对当前cell进行cas累加操作，扩容操作，创建cell操作
                if ((a = as[(n - 1) & h]) == null) {
                    // 如果锁是存在的 表示可以尝试去新建一个cell
                    if (cellsBusy == 0) {       // Try to attach new Cell
                        // 新建一个Cell
                        Cell r = new Cell(x);   // Optimistically create
                        // 双重校验,这一次校验是为了防止多个线程同时创建cell cellsBusy == 0判断效率也会好一些
                        // casCellsBusy() 为true 表示当前线程获取到了锁
                        if (cellsBusy == 0 && casCellsBusy()) {
                            boolean created = false;
                            try {               // Recheck under lock
                                // 再次校验cells数组是否为空，当前线程对应的cell是否为空
                                // 为空则创建成功，不为空则表示其他线程已经创建了cell
                                Cell[] rs; int m, j;
                                if ((rs = cells) != null &&
                                    (m = rs.length) > 0 &&
                                    rs[j = (m - 1) & h] == null) {
                                    rs[j] = r;
                                    created = true;
                                }
                            } finally {
                                cellsBusy = 0;
                            }
                            if (created)
                                break;
                            continue;           // Slot is now non-empty
                        }
                    }
                    collide = false;
                }
                // cells初始化后，当前线程对于的cell不为空，表示当前线程已经创建了cell
                // 直接对cell进行cas操作，如果成功则跳出循环，如果失败则表示有竞争 这里提前将wasUncontended设置为true表示没竞争
                else if (!wasUncontended)       // CAS already known to fail
                    wasUncontended = true;      // Continue after rehash
                // 这里进行cas对cell的累加操作
                else if (a.cas(v = a.value, ((fn == null) ? v + x :
                                             fn.applyAsLong(v, x))))
                    break;
                // 数组长度大于cpu个数，或者其他线程扩容处理了已经 就不扩容了
                else if (n >= NCPU || cells != as)
                    collide = false;            // At max size or stale
                // 反之就需要扩容
                else if (!collide)
                    collide = true;
                // 没有锁的时候才会抢锁然后进行扩容
                else if (cellsBusy == 0 && casCellsBusy()) {
                    try {
                        if (cells == as) {      // Expand table unless stale
                            // n是数组长度 左移1位表示扩容2倍
                            Cell[] rs = new Cell[n << 1];
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            cells = rs;
                        }
                    } finally {
                        cellsBusy = 0;
                    }
                    // 扩容完毕释放锁 并且将collide设置为false
                    collide = false;
                    continue;                   // Retry with expanded table
                }
                // 重新rehash h的 值可以保证每次线程都可以在不同的cell位置进行处理 减少冲突
                h = advanceProbe(h);
            }
            // 锁没人拿  判断数据是否相等 如果相等则表示没有其他线程在创建cells数组  尝试拿锁  casCellsBusy最后判断保证了线程安全和效率
            else if (cellsBusy == 0 && cells == as && casCellsBusy()) {
                boolean init = false;
                try {                           // Initialize table
                    // 判断数据是否相等 如果相等则表示没有其他线程在创建cells数组 双重检查
                    if (cells == as) {
                        // 第一次直接初始化2个长度的cells数组
                        Cell[] rs = new Cell[2];
                        // 给上需要初始化的值
                        rs[h & 1] = new Cell(x);
                        // 重新覆盖数组，并且初始化完成
                        cells = rs;
                        init = true;
                    }
                } finally {
                    // 锁释放操作放在 finally 里
                    cellsBusy = 0;
                }
                if (init)
                    break;
            }
            // 走到到这里说明cells数组还没初始化，或者正在初始化，或者正在扩容，或者正在创建cell
            // 这个时候就进行兜底操作，直接对base进行cas操作累加操作 不让当前线程进行等待
            else if (casBase(v = base, ((fn == null) ? v + x :
                                        fn.applyAsLong(v, x))))
                break;                          // Fall back on using base
        }
    }

    /**
     * Same as longAccumulate, but injecting long/double conversions
     * in too many places to sensibly merge with long version, given
     * the low-overhead requirements of this class. So must instead be
     * maintained by copy/paste/adapt.
     */
    final void doubleAccumulate(double x, DoubleBinaryOperator fn,
                                boolean wasUncontended) {
        int h;
        if ((h = getProbe()) == 0) {
            ThreadLocalRandom.current(); // force initialization
            h = getProbe();
            wasUncontended = true;
        }
        boolean collide = false;                // True if last slot nonempty
        for (;;) {
            Cell[] as; Cell a; int n; long v;
            if ((as = cells) != null && (n = as.length) > 0) {
                if ((a = as[(n - 1) & h]) == null) {
                    if (cellsBusy == 0) {       // Try to attach new Cell
                        Cell r = new Cell(Double.doubleToRawLongBits(x));
                        if (cellsBusy == 0 && casCellsBusy()) {
                            boolean created = false;
                            try {               // Recheck under lock
                                Cell[] rs; int m, j;
                                if ((rs = cells) != null &&
                                    (m = rs.length) > 0 &&
                                    rs[j = (m - 1) & h] == null) {
                                    rs[j] = r;
                                    created = true;
                                }
                            } finally {
                                cellsBusy = 0;
                            }
                            if (created)
                                break;
                            continue;           // Slot is now non-empty
                        }
                    }
                    collide = false;
                }
                else if (!wasUncontended)       // CAS already known to fail
                    wasUncontended = true;      // Continue after rehash
                else if (a.cas(v = a.value,
                               ((fn == null) ?
                                Double.doubleToRawLongBits
                                (Double.longBitsToDouble(v) + x) :
                                Double.doubleToRawLongBits
                                (fn.applyAsDouble
                                 (Double.longBitsToDouble(v), x)))))
                    break;
                else if (n >= NCPU || cells != as)
                    collide = false;            // At max size or stale
                else if (!collide)
                    collide = true;
                else if (cellsBusy == 0 && casCellsBusy()) {
                    try {
                        if (cells == as) {      // Expand table unless stale
                            Cell[] rs = new Cell[n << 1];
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            cells = rs;
                        }
                    } finally {
                        cellsBusy = 0;
                    }
                    collide = false;
                    continue;                   // Retry with expanded table
                }
                h = advanceProbe(h);
            }
            else if (cellsBusy == 0 && cells == as && casCellsBusy()) {
                boolean init = false;
                try {                           // Initialize table
                    if (cells == as) {
                        Cell[] rs = new Cell[2];
                        rs[h & 1] = new Cell(Double.doubleToRawLongBits(x));
                        cells = rs;
                        init = true;
                    }
                } finally {
                    cellsBusy = 0;
                }
                if (init)
                    break;
            }
            else if (casBase(v = base,
                             ((fn == null) ?
                              Double.doubleToRawLongBits
                              (Double.longBitsToDouble(v) + x) :
                              Double.doubleToRawLongBits
                              (fn.applyAsDouble
                               (Double.longBitsToDouble(v), x)))))
                break;                          // Fall back on using base
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long BASE;
    private static final long CELLSBUSY;
    private static final long PROBE;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> sk = Striped64.class;
            BASE = UNSAFE.objectFieldOffset
                (sk.getDeclaredField("base"));
            CELLSBUSY = UNSAFE.objectFieldOffset
                (sk.getDeclaredField("cellsBusy"));
            Class<?> tk = Thread.class;
            PROBE = UNSAFE.objectFieldOffset
                (tk.getDeclaredField("threadLocalRandomProbe"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
