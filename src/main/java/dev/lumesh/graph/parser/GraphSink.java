package dev.lumesh.graph.parser;

import dev.lumesh.graph.model.GraphNode;

public interface GraphSink {
    void writeNode(GraphNode node);
    void writeEdge(String from, String to, String type);
}
