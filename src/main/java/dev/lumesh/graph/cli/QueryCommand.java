package dev.lumesh.graph.cli;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Command(
    name = "query",
    mixinStandardHelpOptions = true,
    description = "Run a Cypher query against the knowledge graph."
)
public class QueryCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Cypher query string.")
    private String cypher;

    @Option(names = {"--uri"}, defaultValue = "bolt://localhost:7687", description = "Neo4j Bolt URI.")
    private String neo4jUri;

    @Option(names = {"-u", "--user"}, defaultValue = "neo4j", description = "Neo4j user.")
    private String neo4jUser;

    @Option(names = {"-p", "--password"}, defaultValue = "password", description = "Neo4j password.")
    private String neo4jPassword;

    @Override
    public Integer call() {
        try (Driver driver = GraphDatabase.driver(neo4jUri, AuthTokens.basic(neo4jUser, neo4jPassword));
             Session session = driver.session()) {

            Result result = session.run(cypher);
            result.forEachRemaining(record -> System.out.println(record.asMap()));
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
