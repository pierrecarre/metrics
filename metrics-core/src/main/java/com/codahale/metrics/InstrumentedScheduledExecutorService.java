package com.codahale.metrics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An {@link ScheduledExecutorService} that monitors the number of tasks submitted, running,
 * completed and also keeps a {@link Timer} for the task duration.
 * <p/>
 * It will register the metrics using the given (or auto-generated) name as classifier, e.g:
 * "your-executor-service.submitted", "your-executor-service.running", etc.
 */
public class InstrumentedScheduledExecutorService implements ScheduledExecutorService {
    private static final AtomicLong nameCounter = new AtomicLong();

    private final ScheduledExecutorService delegate;

    private final Meter submitted;
    private final Counter running;
    private final Meter completed;
    private final Timer duration;

    private final Meter scheduledOnce;
    private final Meter scheduledRepetitively;
    private final Counter scheduledOverrun;
    private final Histogram percentOfPeriod;

    /**
     * Wraps an {@link ScheduledExecutorService} uses an auto-generated default name.
     *
     * @param delegate {@link ScheduledExecutorService} to wrap.
     * @param registry {@link MetricRegistry} that will contain the metrics.
     */
    public InstrumentedScheduledExecutorService(ScheduledExecutorService delegate, MetricRegistry registry) {
        this(delegate, registry, "instrumented-scheduled-executor-service-" + nameCounter.incrementAndGet());
    }

    /**
     * Wraps an {@link ScheduledExecutorService} with an explicit name.
     *
     * @param delegate {@link ScheduledExecutorService} to wrap.
     * @param registry {@link MetricRegistry} that will contain the metrics.
     * @param name     name for this executor service.
     */
    public InstrumentedScheduledExecutorService(ScheduledExecutorService delegate, MetricRegistry registry, String name) {
        this.delegate = delegate;

        this.submitted = registry.meter(MetricRegistry.name(name, "submitted"));

        this.running = registry.counter(MetricRegistry.name(name, "running"));
        this.completed = registry.meter(MetricRegistry.name(name, "completed"));
        this.duration = registry.timer(MetricRegistry.name(name, "duration"));

        this.scheduledOnce = registry.meter(MetricRegistry.name(name, "scheduled.once"));
        this.scheduledRepetitively = registry.meter(MetricRegistry.name(name, "scheduled.repetitively"));
        this.scheduledOverrun = registry.counter(MetricRegistry.name(name, "scheduled.overrun"));
        this.percentOfPeriod = registry.histogram(MetricRegistry.name(name, "scheduled.percent-of-period"));
    }

    /**
     * {@inheritDoc}
     */

    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        scheduledOnce.mark();
        return delegate.schedule(new InstrumentedRunnable(command), delay, unit);
    }

    /**
     * {@inheritDoc}
     */

    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        scheduledOnce.mark();
        return delegate.schedule(new InstrumentedCallable<V>(callable), delay, unit);
    }

    /**
     * {@inheritDoc}
     */

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        scheduledRepetitively.mark();
        return delegate.scheduleAtFixedRate(new InstrumentedPeriodicRunnable(command, period, unit), initialDelay, period, unit);
    }

    /**
     * {@inheritDoc}
     */

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        scheduledRepetitively.mark();
        return delegate.scheduleAtFixedRate(new InstrumentedRunnable(command), initialDelay, delay, unit);
    }

    /**
     * {@inheritDoc}
     */

    public void shutdown() {
        delegate.shutdown();
    }

    /**
     * {@inheritDoc}
     */

    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    /**
     * {@inheritDoc}
     */

    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    /**
     * {@inheritDoc}
     */

    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    /**
     * {@inheritDoc}
     */

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    /**
     * {@inheritDoc}
     */

    public <T> Future<T> submit(Callable<T> task) {
        submitted.mark();
        return delegate.submit(new InstrumentedCallable<T>(task));
    }

    /**
     * {@inheritDoc}
     */

    public <T> Future<T> submit(Runnable task, T result) {
        submitted.mark();
        return delegate.submit(new InstrumentedRunnable(task), result);
    }

    /**
     * {@inheritDoc}
     */

    public Future<?> submit(Runnable task) {
        submitted.mark();
        return delegate.submit(new InstrumentedRunnable(task));
    }

    /**
     * {@inheritDoc}
     */

    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        submitted.mark(tasks.size());
        Collection<? extends Callable<T>> instrumented = instrument(tasks);
        return delegate.invokeAll(instrumented);
    }

    /**
     * {@inheritDoc}
     */

    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        submitted.mark(tasks.size());
        Collection<? extends Callable<T>> instrumented = instrument(tasks);
        return delegate.invokeAll(instrumented, timeout, unit);
    }

    /**
     * {@inheritDoc}
     */

    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        submitted.mark(tasks.size());
        Collection<? extends Callable<T>> instrumented = instrument(tasks);
        return delegate.invokeAny(instrumented);
    }

    /**
     * {@inheritDoc}
     */

    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        submitted.mark(tasks.size());
        Collection<? extends Callable<T>> instrumented = instrument(tasks);
        return delegate.invokeAny(instrumented, timeout, unit);
    }

    private <T> Collection<? extends Callable<T>> instrument(Collection<? extends Callable<T>> tasks) {
        final List<InstrumentedCallable<T>> instrumented = new ArrayList<InstrumentedCallable<T>>(tasks.size());
        for (Callable<T> task : tasks) {
            instrumented.add(new InstrumentedCallable(task));
        }
        return instrumented;
    }

    /**
     * {@inheritDoc}
     */

    public void execute(Runnable command) {
        submitted.mark();
        delegate.execute(new InstrumentedRunnable(command));
    }

    private class InstrumentedRunnable implements Runnable {
        private final Runnable command;

        InstrumentedRunnable(Runnable command) {
            this.command = command;
        }


        public void run() {
            running.inc();
            final Timer.Context context = duration.time();
            try {
                command.run();
            } finally {
                context.stop();
                running.dec();
                completed.mark();
            }
        }
    }

    private class InstrumentedPeriodicRunnable implements Runnable {
        private final Runnable command;
        private final long periodInNanos;

        InstrumentedPeriodicRunnable(Runnable command, long period, TimeUnit unit) {
            this.command = command;
            this.periodInNanos = unit.toNanos(period);
        }


        public void run() {
            running.inc();
            final Timer.Context context = duration.time();
            try {
                command.run();
            } finally {
                final long elapsed = context.stop();
                running.dec();
                completed.mark();
                if (elapsed > periodInNanos) {
                    scheduledOverrun.inc();
                }
                percentOfPeriod.update((100L * elapsed) / periodInNanos);
            }
        }
    }

    private class InstrumentedCallable<T> implements Callable<T> {
        private final Callable<T> task;

        InstrumentedCallable(Callable<T> task) {
            this.task = task;
        }


        public T call() throws Exception {
            running.inc();
            final Timer.Context context = duration.time();
            try {
                return task.call();
            } finally {
                context.stop();
                running.dec();
                completed.mark();
            }
        }
    }
}
