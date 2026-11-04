package org.sigil.app;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs blocking work (network, crypto) off the main thread and delivers results back on it. */
public final class Async {
    public interface Work<T> {
        T run() throws Exception;
    }

    public interface Done<T> {
        void done(T result, Exception error);
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Async() {}

    public static <T> void run(Work<T> work, Done<T> done) {
        POOL.execute(() -> {
            T result = null;
            Exception error = null;
            try {
                result = work.run();
            } catch (Exception e) {
                error = e;
            }
            final T r = result;
            final Exception err = error;
            MAIN.post(() -> done.done(r, err));
        });
    }

    public static String message(Exception e) {
        String m = e == null ? null : e.getMessage();
        return (m == null || m.isEmpty()) ? "Something went wrong" : m;
    }
}
