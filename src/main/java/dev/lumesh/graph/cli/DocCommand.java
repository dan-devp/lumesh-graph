package dev.lumesh.graph.cli;

import dev.lumesh.graph.model.GraphNode;
import dev.lumesh.graph.neo4j.GraphWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@Command(
    name = "doc",
    mixinStandardHelpOptions = true,
    description = "Import Markdown / ADR documents into the knowledge graph and optionally link them to a class."
)
public class DocCommand implements Callable<Integer> {

    @Option(names = {"-s", "--source"}, required = true, description = "Directory containing Markdown files.")
    private Path sourcePath;

    @Option(names = {"--link-to"}, description = "FQN of a Class node to link all documents to (DOCUMENTS edge).")
    private String linkToFqn;

    @Option(names = {"--kind"}, defaultValue = "Doc", description = "Kind label for these documents (e.g. ADR, Wiki, Readme).")
    private String kind;

    @Option(names = {"--uri"}, defaultValue = "bolt://localhost:7687", description = "Neo4j Bolt URI.")
    private String neo4jUri;

    @Option(names = {"-u", "--user"}, defaultValue = "neo4j", description = "Neo4j user.")
    private String neo4jUser;

    @Option(names = {"-p", "--password"}, defaultValue = "password", description = "Neo4j password.")
    private String neo4jPassword;

    @Override
    public Integer call() {
        try (GraphWriter writer = new GraphWriter(neo4jUri, neo4jUser, neo4jPassword)) {
            writer.verifyConnectivity();

            try (Stream<Path> files = Files.walk(sourcePath)) {
                List<Path> mdFiles = files
                    .filter(p -> p.toString().endsWith(".md"))
                    .toList();

                System.out.printf("Importing %d Markdown files from %s%n", mdFiles.size(), sourcePath);

                for (Path md : mdFiles) {
                    String title = md.getFileName().toString().replace(".md", "");
                    String content = Files.readString(md);
                    String fqn = "doc:" + md.toAbsolutePath().normalize();

                    GraphNode docNode = GraphNode.of("Document", fqn)
                        .with("title", title)
                        .with("kind", kind)
                        .with("path", md.toString())
                        .with("content", content);
                    writer.writeNode(docNode);

                    if (linkToFqn != null) {
                        writer.writeEdge(fqn, linkToFqn, "DOCUMENTS");
                    }

                    System.out.println("  Imported: " + title);
                }
            }

            System.out.println("Done.");
            return 0;
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
