package com.nedder3.cache.core.event;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * High-performance EventBus supporting polymorphic subscription matching,
 * exception isolation, and synchronous or asynchronous (Virtual Threads) dispatching.
 */
public class EventBus {

    private final ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Consumer<?>>> subscriptions = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final boolean isAsync;

    /**
     * Creates a synchronous direct-dispatch EventBus.
     */
    public EventBus() {
        this.executor = null;
        this.isAsync = false;
    }

    private EventBus(ExecutorService executor) {
        this.executor = executor;
        this.isAsync = true;
    }

    /**
     * Creates an asynchronous EventBus using Java 21 Virtual Threads.
     */
    public static EventBus async() {
        return new EventBus(Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Creates an asynchronous EventBus with a custom ExecutorService.
     */
    public static EventBus async(ExecutorService executor) {
        return new EventBus(Objects.requireNonNull(executor, "executor cannot be null"));
    }

    /**
     * Registers a consumer for events matching the given type token.
     */
    public <T extends CacheEvent> void subscribe(Class<T> eventType, Consumer<T> subscriber) {
        Objects.requireNonNull(eventType, "eventType cannot be null");
        Objects.requireNonNull(subscriber, "subscriber cannot be null");
        subscriptions.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(subscriber);
    }

    /**
     * Unregisters a previously registered consumer for the given type token.
     */
    public <T extends CacheEvent> boolean unsubscribe(Class<T> eventType, Consumer<T> subscriber) {
        Objects.requireNonNull(eventType, "eventType cannot be null");
        Objects.requireNonNull(subscriber, "subscriber cannot be null");
        CopyOnWriteArrayList<Consumer<?>> subs = subscriptions.get(eventType);
        if (subs != null) {
            boolean removed = subs.remove(subscriber);
            if (subs.isEmpty()) {
                subscriptions.remove(eventType, subs);
            }
            return removed;
        }
        return false;
    }

    /**
     * Publishes an event to all subscribers matching its class or assignable supertypes/interfaces.
     */
    @SuppressWarnings("unchecked")
    public void publish(CacheEvent event) {
        Objects.requireNonNull(event, "event cannot be null");

        Runnable dispatchTask = () -> {
            for (var entry : subscriptions.entrySet()) {
                Class<?> subscribedType = entry.getKey();
                if (subscribedType.isInstance(event)) {
                    List<Consumer<?>> subs = entry.getValue();
                    for (Consumer<?> rawSubscriber : subs) {
                        try {
                            ((Consumer<Object>) rawSubscriber).accept(event);
                        } catch (Exception ignored) {
                            // Subscriber exceptions are isolated to prevent cascading failures
                        }
                    }
                }
            }
        };

        if (isAsync && executor != null && !executor.isShutdown()) {
            executor.execute(dispatchTask);
        } else {
            dispatchTask.run();
        }
    }

    /**
     * Returns true if there are registered subscribers for the given event type.
     */
    public boolean hasSubscribers(Class<? extends CacheEvent> eventType) {
        Objects.requireNonNull(eventType, "eventType cannot be null");
        CopyOnWriteArrayList<Consumer<?>> subs = subscriptions.get(eventType);
        return subs != null && !subs.isEmpty();
    }

    /**
     * Returns the number of subscribers registered for this exact type.
     */
    public int subscriberCount(Class<? extends CacheEvent> eventType) {
        Objects.requireNonNull(eventType, "eventType cannot be null");
        CopyOnWriteArrayList<Consumer<?>> subs = subscriptions.get(eventType);
        return subs != null ? subs.size() : 0;
    }

    /**
     * Clears all subscriptions from the bus.
     */
    public void clear() {
        subscriptions.clear();
    }

    /**
     * Shuts down the asynchronous executor if one is running.
     */
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
