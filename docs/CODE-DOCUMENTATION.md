# Documentación de código (Javadoc)

Estándar para documentar el código de este proyecto (Java; sin overengineering).

## Regla mínima

Todo símbolo **público** (clase o método exportado / parte de la API del componente) lleva Javadoc.
Símbolos internos/privados: comentario corto solo si la intención no es obvia.

## Javadoc (Java)

```java
/**
 * Bus de eventos asíncrono en memoria.
 * Propaga eventos a los suscriptores mediante un pool de hilos.
 *
 * @param <E> tipo del evento
 */
public class AsyncEventBus<E> {
    /**
     * Publica un evento a todos los suscriptores registrados.
     *
     * @param event evento a propagar (no nulo)
     * @throws IllegalArgumentException si event es nulo
     */
    public void publish(E event) { ... }
}
```

Etiquetas comunes: `@param`, `@return`, `@throws`, `@see`, `@since`, `{@code ...}`.

## Por qué

- El IDE muestra el contrato sin leer el cuerpo.
- Graphify y otras tools parsean los docstrings para el grafo.
- El código tipo FIRST/SOLID se autodocumenta; el Javadoc aclara el *contrato*, no la implementación.
