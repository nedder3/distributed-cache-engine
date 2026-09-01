# Code Documentation (Javadoc)

Standard conventions for documenting source code across this project.

## Minimal Rule

Every **public** symbol (exported classes, interfaces, records, and methods part of the component API) must contain a clear Javadoc block.
Internal/private symbols: brief comments only when the design intent or algorithmic constraint is non-obvious.

## Javadoc Example

```java
/**
 * Asynchronous in-memory event bus.
 * Dispatches domain events to subscribers using dedicated worker threads.
 *
 * @param <E> the event type hierarchy handled by this bus
 */
public class AsyncEventBus<E> {
    /**
     * Publishes an event to all registered subscribers.
     *
     * @param event the event to dispatch (must not be null)
     * @throws IllegalArgumentException if event is null
     */
    public void publish(E event) { ... }
}
```

Common tags: `@param`, `@return`, `@throws`, `@see`, `@since`, `{@code ...}`.

## Architectural Rationale

- IDEs surface contract specifications directly without requiring source body inspection.
- Knowledge graph tools parse structured docstrings for semantic analysis.
- Clean code principles ensure self-documenting code; Javadocs clarify the *contract* and concurrency guarantees, not implementation details.
