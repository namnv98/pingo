package com.pingo.core.common.support;

import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static java.lang.Thread.currentThread;
import static java.lang.Thread.onSpinWait;

@Log4j2
public class ThreadUtils {

    @FunctionalInterface
    public static interface ShutdownTaskDisposable {
        boolean dispose();
    }

    private final static AtomicBoolean shuttingDownFlag = new AtomicBoolean(false);
    private final static AtomicInteger shutdownTaskIdSeed = new AtomicInteger(0);
    private final static Map<Integer, List<Runnable>> shutdownTasks = new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(ThreadUtils::doShutdown, "SHUTDOWN HOOK"));
    }

    /**
     * Return current process's state
     *
     * @return true if process is shutting down, false otherwise
     */
    public static boolean isShuttingDown() {
        return shuttingDownFlag.get();
    }

    /**
     * Sleep current thread inside try, catch block
     *
     * @param millis time to sleep
     * @throws ThreadingException on interrupted
     */
    @SneakyThrows
    public static final void sleep(long millis) {
        Thread.sleep(millis);
    }

    /**
     * Sleep current thread without exception throwing
     *
     * @param millis time to sleep
     *
     * @return false if current thread got interrupted, true otherwise
     */
    public static final boolean sleepSilence(long millis) {
        if (isShuttingDown())
            return false;
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            // ignore
        }
        return false;
    }

    /**
     * Stop current thread using Thread.onBusySpin() calling inside a while loop
     * <br>
     * Break if process/currentThread shutdown or breakSignal return true
     *
     * @param continueUntilFalse continue spin when return true, break loop and
     *                           return when false
     */
    public static final void busySpin(Supplier<Boolean> continueUntilFalse) {
        while (continueUntilFalse.get()) {
            if (isShuttingDown() || currentThread().isInterrupted())
                break;
            onSpinWait();
        }
    }

    /**
     * Stop current thread using Thread.onBusySpin() calling inside a while loop
     * <br>
     * Break if process/currentThread shutdown or breakSignal return true
     *
     * @param continueUntilFalse continue spin when true, break loop and return when
     *                           false
     */
    public static final void busySpinUntilFalse(@NonNull AtomicBoolean continueUntilFalse) {
        busySpin(continueUntilFalse::get);
    }

    /**
     * Stop current thread using Thread.onBusySpin() calling inside a while loop
     * <br>
     * Break if process/currentThread shutdown or breakSignal return true
     *
     * @param continueUntilTrue continue spin when false, break loop and return when
     *                          true
     */
    public static final void busySpinUntilTrue(@NonNull AtomicBoolean continueUntilTrue) {
        busySpin(() -> !continueUntilTrue.get());
    }

    /**
     * register a task which can be processed when process shutdown
     *
     * @param task the task to be registered
     * @return disposable object
     */
    public static ShutdownTaskDisposable registerShutdownTask(@NonNull Runnable task) {
        return registerShutdownTask(task, shutdownTaskIdSeed.incrementAndGet());
    }

    public static ShutdownTaskDisposable registerShutdownTask(@NonNull Runnable task, int order) {
        if (isShuttingDown()) {
            return null;
        }

        shutdownTasks.computeIfAbsent(order, key -> new CopyOnWriteArrayList<Runnable>()).add(task);
        return () -> {
            if (isShuttingDown()) {
                return false;
            }

            var tasks = shutdownTasks.get(order);
            if (tasks == null) {
                return false;
            }

            return tasks.remove(task);
        };
    }

    protected static void doShutdown() {
        shuttingDownFlag.set(true);

        // process shutdown tasks...
        if (shutdownTasks.size() == 0)
            return;

        var list = new ArrayList<Integer>();
        var remaining = new LinkedList<Integer>();

        list.addAll(shutdownTasks.keySet());

        list.sort((i1, i2) -> i1 - i2);

        for (Integer order : list) {
            if (order >= 0) {
                runShutdownTasks(order);
            } else {
                remaining.add(0, order);
            }
        }

        for (int order : remaining) {
            runShutdownTasks(order);
        }

        // Cac shutdown task da chay xong (hoac timeout, xem HarborApp/ColonyApp/BeaconApp) -- ep
        // JVM thoat ngay bang halt() thay vi doi thoat tu nhien. Ly do: cac thread cua Vert.x
        // (vert.x-eventloop-thread-*) la non-daemon, nen ngay ca khi shutdown task da hoan tat,
        // JVM van khong tu thoat duoc neu nhung thread do van con song (vd vertx.close() khong
        // thuc su dong het event loop trong 1 so tinh huong voi cluster manager Hazelcast). Dung
        // halt() chu khong phai exit(): goi exit() tu ben trong chinh shutdown hook dang chay se
        // deadlock vo thoi han theo dung spec cua Runtime.exit() -- halt() moi la cach an toan de
        // ep thoat ngay tu ben trong 1 shutdown hook.
        Runtime.getRuntime().halt(0);
    }

    private static void runShutdownTasks(int order) {
        var tasks = shutdownTasks.get(order);
        if (tasks == null) {
            return;
        }

        for (var task : tasks) {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Error while trying to run shutdown task", e);
            }
        }
    }
}

