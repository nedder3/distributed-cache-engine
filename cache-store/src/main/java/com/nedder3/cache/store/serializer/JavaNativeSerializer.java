package com.nedder3.cache.store.serializer;

import com.nedder3.cache.core.port.SerializerPort;

import java.io.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class JavaNativeSerializer<T> implements SerializerPort<T> {

    private final List<SerializationEventListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public byte[] serialize(T value) {
        if (value == null) {
            byte[] empty = new byte[0];
            notifyListeners(new SerializationEvent("SERIALIZE", empty));
            return empty;
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(value);
            oos.flush();
            byte[] data = baos.toByteArray();
            notifyListeners(new SerializationEvent("SERIALIZE", data));
            return data;
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize object: " + value, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public T deserialize(byte[] data) {
        if (data == null || data.length == 0) {
            notifyListeners(new SerializationEvent("DESERIALIZE", data));
            return null;
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            T obj = (T) ois.readObject();
            notifyListeners(new SerializationEvent("DESERIALIZE", data));
            return obj;
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize byte array", e);
        }
    }

    @Override
    public void addListener(SerializationEventListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(SerializationEventListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners(SerializationEvent event) {
        for (SerializationEventListener listener : listeners) {
            listener.onEvent(event);
        }
    }
}
