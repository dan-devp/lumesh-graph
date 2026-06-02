package dev.lumesh.graph.parser;

import dev.lumesh.graph.model.GraphNode;

public interface GraphSink {
    void writeNode(GraphNode node);
    void writeEdge(String from, String to, String type);

    default boolean isFileUnchanged(String filePath, String hash) { return false; }
    default void purgeFile(String filePath) {}
    default void recordFile(String filePath, String hash) {}
}
