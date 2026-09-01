package com.nedder3.cache.core.port;

/**
 * Outbound port defining the serialization contract.
 *
 * @param <V> the type of values to serialize
 */
public interface SerializerPort<V> {

    /**
     * Serializes a value to bytes.
     *
     * @param value the value to serialize
     * @return the byte representation
     */
    byte[] serialize(V value);

    /**
     * Deserializes bytes to a value.
     *
     * @param data the byte representation
     * @return the deserialized value
     */
    V deserialize(byte[] data);

    /**
     * Adds a listener for serialization events.
     *
     * @param listener the event listener to add
     */
    void addListener(SerializationEventListener listener);

    /**
     * Removes a listener for serialization events.
     *
     * @param listener the event listener to remove
     */
    void removeListener(SerializationEventListener listener);

    /**
     * Interface for serialization event listeners.
     */
    interface SerializationEventListener {
        void onEvent(SerializationEvent event);
    }

    /**
     * Represents an event in the serialization system.
     */
    class SerializationEvent {
        private final String type;
        private final Object payload;

        public SerializationEvent(String type, Object payload) {
            this.type = type;
            this.payload = payload;
        }

        public String getType() {
            return type;
        }

        public Object getPayload() {
            return payload;
        }
    }
}
