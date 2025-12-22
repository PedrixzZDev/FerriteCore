package malte0811.ferritecore.fastmap;

import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Maps a Property->Value assignment to a value, while allowing fast access to "neighbor" states.
 * Optimized to deduplicate metadata across blocks with identical property sets and use arrays for storage.
 */
public class FastMap<Value> {
    private static final int INVALID_INDEX = -1;
    // Cache for shared metadata to deduplicate keys and maps across similar blocks (e.g. all Stairs share the same structure)
    private static final Map<IdentityListKey, SharedData> CACHE = new HashMap<>();

    private final SharedData sharedData;
    private final Value[] valueMatrix;

    @SuppressWarnings("unchecked")
    public FastMap(
            Collection<Property<?>> properties, Map<Map<Property<?>, Comparable<?>>, Value> valuesMap, boolean compact
    ) {
        // Snapshot the properties into a list to ensure stable order and access
        List<Property<?>> propList = ImmutableList.copyOf(properties);
        IdentityListKey cacheKey = new IdentityListKey(propList, compact);

        // Get or create the shared metadata for this property set configuration
        synchronized (CACHE) {
            this.sharedData = CACHE.computeIfAbsent(cacheKey, k -> new SharedData(k.list, k.compact));
        }

        // Allocate the value matrix as a raw array to save memory compared to ArrayList
        this.valueMatrix = (Value[]) new Object[sharedData.matrixSize];
        
        // Populate the matrix
        for (Map.Entry<Map<Property<?>, Comparable<?>>, Value> state : valuesMap.entrySet()) {
            this.valueMatrix[getIndexOf(state.getKey())] = state.getValue();
        }
    }

    /**
     * Computes the value for a neighbor state
     */
    @Nullable
    public Value with(int oldIndex, Property<?> prop, Object value) {
        if (!(value instanceof Comparable<?> valueComparable)) {
            return null;
        }
        final FastMapKey<?> keyToChange = getKeyFor(prop);
        if (keyToChange == null) {
            return null;
        }
        int newIndex = keyToChange.replaceIn(oldIndex, valueComparable);
        if (newIndex < 0) {
            return null;
        }
        return valueMatrix[newIndex];
    }

    /**
     * @return The map index corresponding to the given property-value assignment
     */
    public int getIndexOf(Map<Property<?>, Comparable<?>> state) {
        int id = 0;
        for (FastMapKey<?> k : sharedData.keys) {
            id += k.toPartialMapIndex(state.get(k.getProperty()));
        }
        return id;
    }

    /**
     * Returns the value assigned to a property at a given map index
     */
    @Nullable
    public <T extends Comparable<T>>
    T getValue(int stateIndex, Property<T> property) {
        final FastMapKey<T> propId = getKeyFor(property);
        if (propId == null) {
            return null;
        }
        return propId.getValue(stateIndex);
    }

    @Nullable
    public Comparable<?> getValue(int stateIndex, Object key) {
        if (key instanceof Property<?>) {
            return getValue(stateIndex, (Property<?>) key);
        } else {
            return null;
        }
    }

    public int numProperties() {
        return sharedData.keys.size();
    }

    public FastMapKey<?> getKey(int keyIndex) {
        return sharedData.keys.get(keyIndex);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public <T extends Comparable<T>>
    FastMapKey<T> getKeyFor(Property<T> prop) {
        int index = sharedData.toKeyIndex.getInt(prop);
        if (index == INVALID_INDEX) {
            return null;
        } else {
            return (FastMapKey<T>) getKey(index);
        }
    }

    public ReferenceSet<Property<?>> getPropertySet() {
        return sharedData.propertySet;
    }

    public Value getStateByIndex(int neighborIndex) {
        return valueMatrix[neighborIndex];
    }

    private static boolean useArrayMapForSize(int numElements) {
        return numElements < 5;
    }

    /**
     * Holds the structural data for a FastMap.
     * This is immutable and deduplicated across all blocks sharing the same properties.
     */
    private static class SharedData {
        final List<FastMapKey<?>> keys;
        final Reference2IntMap<Property<?>> toKeyIndex;
        final ReferenceSet<Property<?>> propertySet;
        final int matrixSize;

        SharedData(List<Property<?>> properties, boolean compact) {
            List<FastMapKey<?>> keysList = new ArrayList<>(properties.size());
            int factorUpTo = 1;
            
            if (useArrayMapForSize(properties.size())) {
                this.toKeyIndex = new Reference2IntArrayMap<>();
            } else {
                this.toKeyIndex = new Reference2IntOpenHashMap<>();
            }
            this.toKeyIndex.defaultReturnValue(INVALID_INDEX);

            for (Property<?> prop : properties) {
                this.toKeyIndex.put(prop, keysList.size());
                FastMapKey<?> nextKey;
                if (compact) {
                    nextKey = new CompactFastMapKey<>(prop, factorUpTo);
                } else {
                    nextKey = new BinaryFastMapKey<>(prop, factorUpTo);
                }
                keysList.add(nextKey);
                factorUpTo *= nextKey.getFactorToNext();
            }
            this.keys = ImmutableList.copyOf(keysList);
            this.matrixSize = factorUpTo;

            if (useArrayMapForSize(properties.size())) {
                this.propertySet = new ReferenceArraySet<>(properties);
            } else {
                this.propertySet = new ReferenceOpenHashSet<>(properties);
            }
        }
    }

    /**
     * Key used to cache SharedData.
     * Uses identity comparison for the property list elements to ensure safety.
     */
    private static class IdentityListKey {
        private final List<Property<?>> list;
        private final boolean compact;
        private final int hash;

        public IdentityListKey(List<Property<?>> list, boolean compact) {
            this.list = list;
            this.compact = compact;
            // Precompute hash code based on identity
            int h = (compact ? 1 : 0);
            for (Property<?> p : list) {
                h = 31 * h + System.identityHashCode(p);
            }
            this.hash = h;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IdentityListKey)) return false;
            IdentityListKey that = (IdentityListKey) o;
            if (compact != that.compact || hash != that.hash) return false;
            if (list.size() != that.list.size()) return false;
            // Strict identity check for properties
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) != that.list.get(i)) return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
