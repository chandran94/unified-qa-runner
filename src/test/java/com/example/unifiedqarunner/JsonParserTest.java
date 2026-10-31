package com.example.unifiedqarunner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonParserTest {
    @Test
    void parsesNestedCollectionDataAndEscapedStrings() {
        Object parsed = JsonParser.parse("""
                {"item":[{"request":{"url":{"raw":"{{baseUrl}}/health"}}}],
                 "label":"line\\nfeed"}
                """);

        assertTrue(parsed instanceof Map<?, ?>);
        Map<?, ?> root = (Map<?, ?>) parsed;
        assertEquals("line\nfeed", root.get("label"));
        assertEquals("{{baseUrl}}/health",
                ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>)
                        ((List<?>) root.get("item")).get(0)).get("request"))
                        .get("url")).get("raw"));
    }

    @Test
    void rejectsDuplicateKeysAndTrailingInput() {
        assertThrows(IllegalArgumentException.class,
                () -> JsonParser.parse("{\"safe\":true,\"safe\":false}"));
        assertThrows(IllegalArgumentException.class,
                () -> JsonParser.parse("{\"safe\":true} trailing"));
    }

    @Test
    void encodesArgumentsAsJsonStrings() {
        String encoded = JsonParser.encodeStringArray(List.of("space here", "quote\"", "line\n"));
        assertEquals(List.of("space here", "quote\"", "line\n"),
                JsonParser.parse(encoded));
    }
}
