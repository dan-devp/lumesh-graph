package dev.lumesh.graph.cli;

import dev.lumesh.graph.ConsoleOutput;
import dev.lumesh.graph.neo4j.GraphWriter;
import dev.lumesh.graph.parser.GraphMerger;
import dev.lumesh.graph.parser.InMemoryGraphSink;
import dev.lumesh.graph.parser.JavaSourceParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
    name = "analyze",
    mixinStandardHelpOptions = true,
    description = "Parse a Java source directory and write the knowledge graph to Neo4j."
)
public class AnalyzeCommand implements Callable<Integer> {

    @Option(names = {"-s", "--source"}, required = true, description = "Root directory of the Java source tree (customer or standalone).")
    private Path sourcePath;

    @Option(names = {"--core"}, description = "Core source directory. If set, core is parsed first; customer overrides take precedence.")
    private Path corePath;

    @Option(names = {"--uri"}, defaultValue = "bolt://localhost:7687", description = "Neo4j Bolt URI.")
    private String neo4jUri;

    @Option(names = {"-u", "--user"}, defaultValue = "neo4j", description = "Neo4j user.")
    private String neo4jUser;

    @Option(names = {"-p", "--password"}, defaultValue = "password", description = "Neo4j password.")
    private String neo4jPassword;

    @Option(names = {"--clear"}, description = "Delete all existing graph data before import.")
    private boolean clear;

    @Option(names = {"--exclude-tests"}, description = "Skip files in test directories or ending with Test.java.")
    private boolean excludeTests;

    @Override
    public Integer call() {
        try (ConsoleOutput console = new ConsoleOutput();
             GraphWriter writer = new GraphWriter(neo4jUri, neo4jUser, neo4jPassword)) {

            console.info(String.format("Connecting to Neo4j at %s ...", neo4jUri));
            writer.verifyConnectivity();
            console.success("Connected.");

            writer.ensureIndexes();

            if (clear) {
                console.info("Clearing existing graph data...");
                writer.clearAll();
            }

            if (corePath != null) {
                Path absCore = corePath.toAbsolutePath();
                Path absSrc = sourcePath.toAbsolutePath();
                console.info("Parsing core:     " + absCore);
                console.info("Parsing customer: " + absSrc);

                JavaSourceParser.configureSymbolSolver(absCore, absSrc);

                InMemoryGraphSink coreSink = new InMemoryGraphSink();
                new JavaSourceParser(coreSink, excludeTests).parseDirectory(absCore);

                InMemoryGraphSink customerSink = new InMemoryGraphSink();
                new JavaSourceParser(customerSink, excludeTests).parseDirectory(absSrc);

                console.info("Merging (customer overrides take precedence)...");
                new GraphMerger(console).merge(coreSink, customerSink, writer);
            } else {
                console.info("Parsing Java source: " + sourcePath.toAbsolutePath());
                JavaSourceParser.configureSymbolSolver(sourcePath.toAbsolutePath());
                new JavaSourceParser(writer, excludeTests).parseDirectory(sourcePath.toAbsolutePath());
            }

            console.success("Done.");
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }
}
