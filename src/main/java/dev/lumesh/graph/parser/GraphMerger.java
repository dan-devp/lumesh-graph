package dev.lumesh.graph.parser;

import dev.lumesh.graph.ConsoleOutput;
import dev.lumesh.graph.model.GraphEdge;
import dev.lumesh.graph.model.GraphNode;
import dev.lumesh.graph.neo4j.GraphWriter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class GraphMerger {

    private final ConsoleOutput console;

    public GraphMerger(ConsoleOutput console) {
        this.console = console;
    }

    /**
     * Writes core nodes/edges to Neo4j, skipping any method that a customer class overrides.
     * Then writes all customer nodes/edges.
     */
    public void merge(InMemoryGraphSink core, InMemoryGraphSink customer, GraphWriter writer) {
        Set<String> overriddenCoreMethods = findOverriddenCoreMethods(core, customer);

        console.info(String.format("Core:     %d nodes, %d edges", core.nodes().size(), core.edges().size()));
        console.info(String.format("Customer: %d nodes, %d edges", customer.nodes().size(), customer.edges().size()));
        console.info(String.format("Overridden core methods: %d", overriddenCoreMethods.size()));

        int coreNodesWritten = 0;
        int coreNodeTotal = core.nodes().size();
        for (GraphNode node : core.nodes().values()) {
            String fqn = (String) node.properties().get("fqn");
            if (!overriddenCoreMethods.contains(fqn)) {
                writer.writeNode(node);
                coreNodesWritten++;
            }
            console.progress("core nodes", coreNodesWritten, coreNodeTotal);
        }

        int coreEdgesWritten = 0;
        int coreEdgeTotal = core.edges().size();
        for (GraphEdge edge : core.edges()) {
            if (!overriddenCoreMethods.contains(edge.from()) && !overriddenCoreMethods.contains(edge.to())) {
                writer.writeEdge(edge.from(), edge.to(), edge.type());
                coreEdgesWritten++;
            }
            console.progress("core edges", coreEdgesWritten, coreEdgeTotal);
        }
        console.info(String.format("Written core:     %d nodes, %d edges", coreNodesWritten, coreEdgesWritten));

        int custNodeTotal = customer.nodes().size();
        int custNodesWritten = 0;
        for (GraphNode node : customer.nodes().values()) {
            writer.writeNode(node);
            console.progress("customer nodes", ++custNodesWritten, custNodeTotal);
        }

        int custEdgeTotal = customer.edges().size();
        int custEdgesWritten = 0;
        for (GraphEdge edge : customer.edges()) {
            writer.writeEdge(edge.from(), edge.to(), edge.type());
            console.progress("customer edges", ++custEdgesWritten, custEdgeTotal);
        }
        console.info(String.format("Written customer: %d nodes, %d edges", custNodesWritten, custEdgesWritten));
    }

    private Set<String> findOverriddenCoreMethods(InMemoryGraphSink core, InMemoryGraphSink customer) {
        Map<String, Set<String>> coreMethodSigs = new HashMap<>();
        for (GraphEdge e : core.edges()) {
            if ("HAS_METHOD".equals(e.type())) {
                coreMethodSigs.computeIfAbsent(e.from(), k -> new HashSet<>())
                              .add(signaturePart(e.to()));
            }
        }

        Map<String, String> customerToCore = new HashMap<>();
        for (GraphEdge e : customer.edges()) {
            if ("INHERITS_FROM".equals(e.type()) && core.nodes().containsKey(e.to())) {
                customerToCore.put(e.from(), e.to());
            }
        }

        Set<String> overridden = new HashSet<>();
        for (GraphEdge e : customer.edges()) {
            if (!"HAS_METHOD".equals(e.type())) continue;
            String coreClass = customerToCore.get(e.from());
            if (coreClass == null) continue;
            String sig = signaturePart(e.to());
            Set<String> coreSigs = coreMethodSigs.get(coreClass);
            if (coreSigs != null && coreSigs.contains(sig)) {
                overridden.add(coreClass + "#" + sig);
            }
        }
        return overridden;
    }

    private String signaturePart(String fqn) {
        int idx = fqn.indexOf('#');
        return idx >= 0 ? fqn.substring(idx + 1) : fqn;
    }
}
