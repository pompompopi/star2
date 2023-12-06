package me.pompompopi.star2.util;

import java.util.Arrays;
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

    @SafeVarargs
    public static <T> T wrapMultiple(final UnstableSupplier<T> unstableSupplier, final Function<Exception, RuntimeException> exceptionMapper, final Class<? extends Exception>... exceptionClasses) {
        if (!Arrays.stream(exceptionClasses).allMatch(Exception.class::isAssignableFrom))
            throw new IllegalArgumentException("Provided non-exception class in accepted exceptions");
        try {
            return unstableSupplier.get();
        } catch (Throwable ex) {
            if (Arrays.stream(exceptionClasses).anyMatch(c -> c.isInstance(ex)))
                throw exceptionMapper.apply((Exception) ex);
            if (ex instanceof RuntimeException)
                throw (RuntimeException) ex;
            throw new RuntimeException(ex);
        }
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
