package dev.lumesh.graph.parser;

import dev.lumesh.graph.model.GraphEdge;
import dev.lumesh.graph.model.GraphNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InMemoryGraphSink implements GraphSink {

    private final Map<String, GraphNode> nodes = new LinkedHashMap<>();
    private final List<GraphEdge> edges = new ArrayList<>();

    @Override
    public void writeNode(GraphNode node) {
        nodes.put((String) node.properties().get("fqn"), node);
    }

    @Override
    public void writeEdge(String from, String to, String type) {
        edges.add(new GraphEdge(from, to, type));
    }

    public Map<String, GraphNode> nodes() { return nodes; }
    public List<GraphEdge> edges() { return edges; }
}
