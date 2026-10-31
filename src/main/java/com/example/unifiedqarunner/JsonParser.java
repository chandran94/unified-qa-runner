package com.example.unifiedqarunner;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonParser {
    private static final int MAX_DEPTH = 100;

    private JsonParser() {
    }

    static Object parse(String json) {
        Parser parser = new Parser(json);
        Object value = parser.value(0);
        parser.whitespace();
        if (!parser.finished()) {
            throw parser.invalid();
        }
        return value;
    }

    static String encodeStringArray(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('"');
            for (char character : values.get(index).toCharArray()) {
                switch (character) {
                    case '"' -> json.append("\\\"");
                    case '\\' -> json.append("\\\\");
                    case '\b' -> json.append("\\b");
                    case '\f' -> json.append("\\f");
                    case '\n' -> json.append("\\n");
                    case '\r' -> json.append("\\r");
                    case '\t' -> json.append("\\t");
                    default -> {
                        if (character < 0x20) {
                            json.append(String.format("\\u%04x", (int) character));
                        } else {
                            json.append(character);
                        }
                    }
                }
            }
            json.append('"');
        }
        return json.append(']').toString();
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) {
            this.input = input;
        }

        private Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw invalid();
            }
            whitespace();
            if (finished()) {
                throw invalid();
            }
            return switch (input.charAt(index)) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object(int depth) {
            index++;
            whitespace();
            Map<String, Object> result = new LinkedHashMap<>();
            if (consume('}')) {
                return result;
            }
            while (true) {
                whitespace();
                if (finished() || input.charAt(index) != '"') {
                    throw invalid();
                }
                String key = string();
                whitespace();
                require(':');
                Object value = value(depth);
                if (result.containsKey(key)) {
                    throw invalid();
                }
                result.put(key, value);
                whitespace();
                if (consume('}')) {
                    return result;
                }
                require(',');
            }
        }

        private List<Object> array(int depth) {
            index++;
            whitespace();
            List<Object> result = new ArrayList<>();
            if (consume(']')) {
                return result;
            }
            while (true) {
                result.add(value(depth));
                whitespace();
                if (consume(']')) {
                    return result;
                }
                require(',');
            }
        }

        private String string() {
            require('"');
            StringBuilder result = new StringBuilder();
            while (!finished()) {
                char character = input.charAt(index++);
                if (character == '"') {
                    return result.toString();
                }
                if (character < 0x20) {
                    throw invalid();
                }
                if (character != '\\') {
                    result.append(character);
                    continue;
                }
                if (finished()) {
                    throw invalid();
                }
                char escape = input.charAt(index++);
                switch (escape) {
                    case '"', '\\', '/' -> result.append(escape);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(unicodeEscape());
                    default -> throw invalid();
                }
            }
            throw invalid();
        }

        private char unicodeEscape() {
            if (index + 4 > input.length()) {
                throw invalid();
            }
            int value = 0;
            for (int digit = 0; digit < 4; digit++) {
                int hexadecimal = Character.digit(input.charAt(index++), 16);
                if (hexadecimal < 0) {
                    throw invalid();
                }
                value = value * 16 + hexadecimal;
            }
            return (char) value;
        }

        private Object number() {
            int start = index;
            consume('-');
            if (!consume('0')) {
                digits(true);
            }
            if (consume('.')) {
                digits(true);
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) {
                    consume('-');
                }
                digits(true);
            }
            if (index == start) {
                throw invalid();
            }
            try {
                return new BigDecimal(input.substring(start, index));
            } catch (NumberFormatException exception) {
                throw invalid();
            }
        }

        private void digits(boolean required) {
            int start = index;
            while (!finished() && Character.isDigit(input.charAt(index))) {
                index++;
            }
            if (required && start == index) {
                throw invalid();
            }
        }

        private Object literal(String text, Object value) {
            if (!input.startsWith(text, index)) {
                throw invalid();
            }
            index += text.length();
            return value;
        }

        private void whitespace() {
            while (!finished()) {
                char character = input.charAt(index);
                if (character != ' ' && character != '\t' && character != '\r' && character != '\n') {
                    return;
                }
                index++;
            }
        }

        private boolean consume(char expected) {
            if (!finished() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            if (!consume(expected)) {
                throw invalid();
            }
        }

        private boolean finished() {
            return index >= input.length();
        }

        private IllegalArgumentException invalid() {
            return new IllegalArgumentException("Invalid JSON at character " + index + ".");
        }
    }
}
