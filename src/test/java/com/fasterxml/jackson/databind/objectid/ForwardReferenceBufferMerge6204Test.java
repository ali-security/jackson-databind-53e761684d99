package com.fasterxml.jackson.databind.objectid;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.testutil.DatabindTestUtil;

import static org.junit.jupiter.api.Assertions.*;

// [databind#6204]
/**
 * Complements {@link ForwardReferenceResolutionOrder6204Test}: verifies that entries
 * buffered behind still unresolved forward references in a Map are moved just once,
 * instead of being re-inserted into the buffer of each preceding reference as those
 * resolve in reverse order -- which takes time quadratic in the number of pending
 * references times buffered entries, even when id lookups themselves are cheap.
 */
public class ForwardReferenceBufferMerge6204Test extends DatabindTestUtil
{
    /**
     * Map key type that counts {@code hashCode()} calls, to measure -- deterministically,
     * without relying on timing -- how often buffered entries get (re)inserted.
     */
    static class CountingKey
    {
        public final static AtomicLong HASH_CALLS = new AtomicLong();

        private final String _value;

        public CountingKey(String value) { _value = value; }

        @Override
        public boolean equals(Object o) {
            return (o instanceof CountingKey) && _value.equals(((CountingKey) o)._value);
        }

        @Override
        public int hashCode() {
            HASH_CALLS.incrementAndGet();
            return _value.hashCode();
        }

        @Override
        public String toString() { return _value; }
    }

    @JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
    static class Item
    {
        public String id;
        public String name;
    }

    // Number of forward references, resolved in reverse order
    private final static int REFS = 200;

    // Number of plain entries buffered behind the last forward reference
    private final static int PADDING = 2000;

    private final ObjectMapper MAPPER = newJsonMapper();

    private final TypeReference<Map<CountingKey,Item>> MAP_TYPE
        = new TypeReference<Map<CountingKey,Item>>() { };

    @Test
    public void bufferedEntriesMovedOnceInMap() throws Exception
    {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < REFS; ++i) {
            sb.append("'ref").append(i).append("':'").append(i).append("',");
        }
        for (int i = 0; i < PADDING; ++i) {
            sb.append("'pad").append(i).append("':{'id':'p").append(i).append("'},");
        }
        for (int i = REFS; --i >= 0; ) {
            sb.append("'def").append(i).append("':");
            sb.append("{'id':'").append(i).append("','name':'n").append(i).append("'}");
            sb.append((i == 0) ? '}' : ',');
        }

        CountingKey.HASH_CALLS.set(0);
        Map<CountingKey,Item> result = MAPPER.readValue(a2q(sb.toString()), MAP_TYPE);
        long hashCalls = CountingKey.HASH_CALLS.get();

        final int total = 2 * REFS + PADDING;
        assertEquals(total, result.size());

        // Insertion order of the result Map must match the document
        List<String> keys = new ArrayList<>();
        List<Item> values = new ArrayList<>();
        for (Map.Entry<CountingKey,Item> entry : result.entrySet()) {
            keys.add(entry.getKey().toString());
            values.add(entry.getValue());
        }
        for (int i = 0; i < REFS; ++i) {
            assertEquals("ref"+i, keys.get(i));
            assertEquals(String.valueOf(i), values.get(i).id);
            // Reference and definition must resolve to the very same instance
            int defIndex = REFS + PADDING + (REFS - 1 - i);
            assertEquals("def"+i, keys.get(defIndex));
            assertSame(values.get(i), values.get(defIndex),
                    "Entry 'ref"+i+"' not same instance as 'def"+i+"'");
        }
        for (int i = 0; i < PADDING; ++i) {
            assertEquals("pad"+i, keys.get(REFS + i));
            assertEquals("p"+i, values.get(REFS + i).id);
        }

        // Moving each buffered entry once needs a small multiple of the entry count;
        // re-inserting buffered entries on every resolution ~REFS * PADDING
        long max = 20L * (REFS + PADDING);
        if (hashCalls > max) {
            fail("Deserializing "+total+" Map entries behind "+REFS
                    +" forward references took "+hashCalls
                    +" key hashCode() calls, expected at most "+max);
        }
    }
}
