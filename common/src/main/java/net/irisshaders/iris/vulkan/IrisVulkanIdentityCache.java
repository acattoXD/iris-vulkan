package net.irisshaders.iris.vulkan;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Weak identity keys with allocation-free lookups and updates of existing entries. */
public final class IrisVulkanIdentityCache<K, V> {
    private final ReferenceQueue<Object> staleKeys = new ReferenceQueue<>();
    private final Map<Object, Value<V>> entries = new HashMap<>();
    private final Lookup lookup = new Lookup();

    public synchronized V get(K key) {
        if (key == null) return null;
        expungeStaleEntries();
        Value<V> entry = find(key);
        return entry == null ? null : entry.value;
    }

    public synchronized void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        expungeStaleEntries();
        Value<V> entry = find(key);
        if (entry == null) entries.put(new WeakKey(key, staleKeys), new Value<>(value));
        else entry.value = value;
    }

    public synchronized int size() {
        expungeStaleEntries();
        return entries.size();
    }

    private Value<V> find(Object key) {
        lookup.key = key;
        try { return entries.get(lookup); }
        finally { lookup.key = null; }
    }

    private void expungeStaleEntries() {
        Object stale;
        while ((stale = staleKeys.poll()) != null) entries.remove(stale);
    }

    private static final class Value<V> {
        private V value;
        private Value(V value) { this.value = value; }
    }

    private static final class WeakKey extends WeakReference<Object> {
        private final int hash;
        private WeakKey(Object key, ReferenceQueue<Object> queue) {
            super(key, queue);
            hash = System.identityHashCode(key);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            Object key = get();
            return key != null && (other instanceof WeakKey weak ? key == weak.get()
                    : other instanceof Lookup lookup && key == lookup.key);
        }
    }

    /** Used only under the cache monitor, and never inserted into the map. */
    private static final class Lookup {
        private Object key;
        @Override public int hashCode() { return System.identityHashCode(key); }
        @Override public boolean equals(Object other) { return other instanceof WeakKey weak && key == weak.get(); }
    }
}
