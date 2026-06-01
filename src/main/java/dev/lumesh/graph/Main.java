package dev.lumesh.graph;

import dev.lumesh.graph.cli.AnalyzeCommand;
import dev.lumesh.graph.cli.DocCommand;
import dev.lumesh.graph.cli.QueryCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "lumesh-graph",
    mixinStandardHelpOptions = true,
    version = "0.1.0",
    description = "Build and query a knowledge graph from Java source code.",
    subcommands = {
        AnalyzeCommand.class,
        DocCommand.class,
        QueryCommand.class
    }
)
public class Main {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}