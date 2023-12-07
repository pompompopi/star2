package me.pompompopi.star2.wrappers;

import me.pompompopi.star2.Star2;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

public class ExceptionLoggingExecutorService implements ExecutorService {
    private final ExecutorService executorService;

    public ExceptionLoggingExecutorService(final ExecutorService executorService) {
        this.executorService = executorService;
    }

    @Override
    public void shutdown() {
        executorService.shutdown();
    }

    @NotNull
    @Override
    public List<Runnable> shutdownNow() {
        return executorService.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return executorService.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return executorService.isTerminated();
    }

    @Override
    public boolean awaitTermination(final long timeout, @NotNull final TimeUnit unit) throws InterruptedException {
        return executorService.awaitTermination(timeout, unit);
    }

    @NotNull
    @Override
    public <T> Future<T> submit(@NotNull final Callable<T> task) {
        return executorService.submit(convertIntoLogging(task));
    }

    @NotNull
    @Override
    public <T> Future<T> submit(@NotNull final Runnable task, final T result) {
        return executorService.submit(convertIntoLogging(task), result);
    }

    @NotNull
    @Override
    public Future<?> submit(@NotNull final Runnable task) {
        return executorService.submit(convertIntoLogging(task));
    }

    @NotNull
    @Override
    public <T> List<Future<T>> invokeAll(@NotNull final Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return executorService.invokeAll(tasks.stream().map(this::convertIntoLogging).toList());
    }

    @NotNull
    @Override
    public <T> List<Future<T>> invokeAll(@NotNull final Collection<? extends Callable<T>> tasks, final long timeout, @NotNull final TimeUnit unit) throws InterruptedException {
        return executorService.invokeAll(tasks.stream().map(this::convertIntoLogging).toList(), timeout, unit);
    }

    @NotNull
    @Override
    public <T> T invokeAny(@NotNull final Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return executorService.invokeAny(tasks.stream().map(this::convertIntoLogging).toList());
    }

    @Override
    public <T> T invokeAny(@NotNull final Collection<? extends Callable<T>> tasks, final long timeout, @NotNull final TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return executorService.invokeAny(tasks.stream().map(this::convertIntoLogging).toList(), timeout, unit);
    }

    @Override
    public void execute(@NotNull Runnable command) {
        executorService.execute(command);
    }

    private <T> Callable<T> convertIntoLogging(final Callable<T> callable) {
        return () -> {
            try {
                return callable.call();
            } catch (Throwable ex) {
                Star2.LOGGER.warn("Exception occurred in executor service", ex);
                throw ex;
            }
        };
    }

    private Runnable convertIntoLogging(final Runnable runnable) {
        return () -> {
            try {
                runnable.run();
            } catch (Throwable ex) {
                Star2.LOGGER.warn("Exception occurred in executor service", ex);
                throw ex;
            }
        };
    }
}
