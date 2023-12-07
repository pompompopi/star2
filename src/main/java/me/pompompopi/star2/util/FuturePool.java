package me.pompompopi.star2.util;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class FuturePool {
    private final List<CompletableFuture<?>> futures = new ArrayList<>();
    private boolean finalized = false;

    @Nullable
    public CompletableFuture<?> poolAdd(final CompletableFuture<?> future) {
        if (finalized)
            return null;
        this.futures.add(future);
        return future;
    }

    @Nullable
    public CompletableFuture<?> poolRun(final Runnable runnable) {
        if (finalized)
            return null;
        final CompletableFuture<Void> future = CompletableFuture.runAsync(runnable);
        futures.add(future);
        return future;
    }

    public void join() {
        if (finalized)
            return;
        finalized = true;
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }
}
