package com.mmts.journey.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Small helpers to read fields out of an event payload (a JSON-like map).
 * Numbers may arrive as Integer, Long or Double, and times as text or Instant, so we convert carefully.
 */
final class EventPayloads {

    private EventPayloads() {
    }

    static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    static Double number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Double.parseDouble(s);
        }
        return null;
    }

    static double number(Map<String, Object> map, String key, double fallback) {
        Double value = number(map, key);
        return value == null ? fallback : value;
    }

    static boolean flag(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    static Instant time(Map<String, Object> map, String key, Instant fallback) {
        Object value = map.get(key);
        if (value instanceof Instant i) {
            return i;
        }
        return value == null ? fallback : Instant.parse(value.toString());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> objects(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
        }
        return List.of();
    }
}
