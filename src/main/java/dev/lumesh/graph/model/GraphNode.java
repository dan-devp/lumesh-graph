package dev.lumesh.graph.model;

import java.util.HashMap;
import java.util.Map;

public record GraphNode(String label, Map<String, Object> properties) {

    public static GraphNode of(String label, String fqn) {
        Map<String, Object> props = new HashMap<>();
        props.put("fqn", fqn);
        return new GraphNode(label, props);
    }

    public GraphNode with(String key, Object value) {
        Map<String, Object> copy = new HashMap<>(properties);
        if (value != null) {
            copy.put(key, value);
        }
        return new GraphNode(label, copy);
    }
}
