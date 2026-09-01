package com.nedder3.cache.core.event;

/**
 * Test fixtures used exclusively by {@link EventBusTest}.
 *
 * All types implement the production {@link CacheEvent} so that the bus's
 * {@code subscribe(Class<T>, Consumer<T>)} method resolves correctly
 * (bound: {@code T extends CacheEvent}).
 *
 * The hierarchy is deliberately multi-level to exercise supertype dispatch:
 *
 *   CacheEvent (production interface)
 *   ├── WriteEvent (implements CacheEvent)
 *   │   └── CreateEvent (extends WriteEvent)  ← 3-level depth, tests subtype/supertype dispatch
 *   ├── EvictEvent (implements CacheEvent)    ← sibling branch, tests sibling isolation
 *   └── ClearAllEvent (implements CacheEvent) ← independent branch, tests cross-branch isolation
 */
public final class TestEvents {

    private TestEvents() {
        // utility holder — no instances
    }

    /** Base class for test events implementing CacheEvent. */
    public static class BaseTestEvent implements CacheEvent {
        private final String key;
        private final long timestamp;

        public BaseTestEvent(String key) {
            this.key = key;
            this.timestamp = System.nanoTime();
        }

        public String key() {
            return key;
        }

        @Override
        public long timestamp() {
            return timestamp;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "[key=" + key + "]";
        }
    }

    /** Subtype representing a write. */
    public static class WriteEvent extends BaseTestEvent {
        private final Object value;

        public WriteEvent(String key, Object value) {
            super(key);
            this.value = value;
        }

        public Object value() {
            return value;
        }
    }

    /** Subtype of {@link WriteEvent} — first-write / create event (3-level hierarchy). */
    public static class CreateEvent extends WriteEvent {
        public CreateEvent(String key, Object value) {
            super(key, value);
        }
    }

    /** Sibling branch: an eviction event (independent of write/create). */
    public static class EvictEvent extends BaseTestEvent {
        public EvictEvent(String key) {
            super(key);
        }
    }

    /** Independent branch: clear-all event. */
    public static class ClearAllEvent extends BaseTestEvent {
        public ClearAllEvent() {
            super("*");
        }
    }
}
