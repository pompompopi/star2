package me.pompompopi.star2.util;

import me.pompompopi.star2.Star2;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

public final class ExceptionUtil {
    private ExceptionUtil() {

    }

    public static <T, E extends Exception> T wrap(final Class<E> expectedExceptionClass, final UnstableSupplier<T> unstableSupplier, final Function<E, RuntimeException> exceptionMapper) {
        try {
            return unstableSupplier.get();
        } catch (Throwable ex) {
            if (expectedExceptionClass.isInstance(ex))
                throw exceptionMapper.apply(expectedExceptionClass.cast(ex));
            if (ex instanceof RuntimeException)
                throw (RuntimeException) ex;
            throw new RuntimeException(ex);
        }
    }

    public static <E extends Exception> void wrap(final Class<E> expectedExceptionClass, final UnstableRunnable unstableRunnable, final Function<E, RuntimeException> exceptionMapper) {
        try {
            unstableRunnable.run();
        } catch (Throwable ex) {
            if (expectedExceptionClass.isInstance(ex))
                throw exceptionMapper.apply(expectedExceptionClass.cast(ex));
            if (ex instanceof RuntimeException)
                throw (RuntimeException) ex;
            throw new RuntimeException(ex);
        }
    }

    public static <T> CompletableFuture<T> handleException(final CompletableFuture<T> completableFuture, final Consumer<Throwable> handler) {
        return completableFuture.whenCompleteAsync((r, t) -> {
            if (t == null)
                return;
            handler.accept(t);
        });
    }

    public static <T> CompletableFuture<T> handleExceptionAndLog(final CompletableFuture<T> completableFuture, final String where) {
        return handleException(completableFuture, t -> Star2.LOGGER.error("An exception occurred in {}", where, t));
    }

    public static void ignore(final UnstableRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable ignored) {

        }
    }

    @FunctionalInterface
    public interface UnstableRunnable {
        void run() throws Throwable;
    }

    @FunctionalInterface
    public interface UnstableSupplier<T> {
        T get() throws Throwable;
    }
}
