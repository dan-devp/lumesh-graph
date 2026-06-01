package dev.lumesh.graph.neo4j;

import dev.lumesh.graph.model.GraphNode;
import dev.lumesh.graph.parser.GraphSink;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;

public class GraphWriter implements GraphSink, AutoCloseable {

    private final Driver driver;

    public GraphWriter(String uri, String user, String password) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
    }

    public void verifyConnectivity() {
        driver.verifyConnectivity();
    }

    /** Idempotent: MERGE on fqn so re-runs don't duplicate nodes. */
    public void writeNode(GraphNode node) {
        String query = "MERGE (n:%s {fqn: $fqn}) SET n += $props".formatted(node.label());
        try (Session session = driver.session()) {
            session.run(query, Values.parameters(
                "fqn", node.properties().get("fqn"),
                "props", node.properties()
            ));
        }
    }

    public void writeEdge(String fromFqn, String toFqn, String edgeType) {
        String query = """
            MATCH (a {fqn: $from}), (b {fqn: $to})
            MERGE (a)-[:%s]->(b)
            """.formatted(edgeType);
        try (Session session = driver.session()) {
            session.run(query, Values.parameters("from", fromFqn, "to", toFqn));
        }
    }

    public void clearAll() {
        try (Session session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n");
        }
    }

    /** Create indexes for fast lookup by fqn and name. */
    public void ensureIndexes() {
        try (Session session = driver.session()) {
            for (String label : new String[]{"Package", "Class", "Method", "Field", "Document"}) {
                session.run("CREATE INDEX %s_fqn IF NOT EXISTS FOR (n:%s) ON (n.fqn)".formatted(label, label));
                session.run("CREATE INDEX %s_name IF NOT EXISTS FOR (n:%s) ON (n.name)".formatted(label, label));
            }
        }
    }

    @Override
    public void close() {
        driver.close();
    }
}
