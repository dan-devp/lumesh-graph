package dev.lumesh.graph.parser;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import dev.lumesh.graph.model.GraphNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public class JavaSourceParser {

    private final GraphSink writer;
    private final boolean excludeTests;

    public JavaSourceParser(GraphSink writer, boolean excludeTests) {
        this.writer = writer;
        this.excludeTests = excludeTests;
    }

    public static void configureSymbolSolver(Path... roots) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver());
        for (Path root : roots) {
            typeSolver.add(new JavaParserTypeSolver(root));
        }
        StaticJavaParser.getParserConfiguration()
            .setSymbolResolver(new JavaSymbolSolver(typeSolver))
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    public void parseDirectory(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> javaFiles = files
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !excludeTests || !isTestFile(p))
                .toList();

            int total = javaFiles.size();
            System.out.printf("Found %d Java files in %s%n", total, root);

            for (int i = 0; i < total; i++) {
                Path file = javaFiles.get(i);
                System.out.printf("[%d/%d] %s%n", i + 1, total, file.getFileName());
                try {
                    parseFile(file);
                } catch (Exception e) {
                    System.err.printf("  -> Skip: %s%n", e.getMessage());
                }
            }
        }
    }

    private void parseFile(Path file) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(file);

        String packageName = cu.getPackageDeclaration()
            .map(NodeWithName::getNameAsString)
            .orElse("(default)");

        GraphNode pkgNode = GraphNode.of("Package", packageName)
            .with("name", packageName);
        writer.writeNode(pkgNode);

        cu.findAll(TypeDeclaration.class).forEach(type -> {
            if (!(type instanceof ClassOrInterfaceDeclaration || type instanceof EnumDeclaration)) return;

            String simpleName = type.getNameAsString();
            String fqn = packageName + "." + simpleName;
            String kind = resolveKind(type);
            String javadoc = ((TypeDeclaration<?>) type).getJavadocComment().map(JavadocComment::getContent).orElse(null);

            GraphNode classNode = GraphNode.of("Class", fqn)
                .with("name", simpleName)
                .with("kind", kind)
                .with("javadoc", javadoc)
                .with("file", file.toString());
            writer.writeNode(classNode);
            writer.writeEdge(packageName, fqn, "CONTAINS");

            if (type instanceof ClassOrInterfaceDeclaration coid) {
                // Vererbung
                coid.getExtendedTypes().forEach(ext -> {
                    String parentFqn = resolveTypeFqn(ext.getNameAsString(), packageName);
                    writer.writeEdge(fqn, parentFqn, "INHERITS_FROM");
                });
                // Interfaces
                coid.getImplementedTypes().forEach(impl -> {
                    String ifaceFqn = resolveTypeFqn(impl.getNameAsString(), packageName);
                    writer.writeEdge(fqn, ifaceFqn, "IMPLEMENTS");
                });
            }

            // Felder
            type.findAll(FieldDeclaration.class).forEach(field -> {
                field.getVariables().forEach(var -> {
                    String fieldFqn = fqn + "#" + var.getNameAsString();
                    String fieldJavadoc = field.getJavadocComment().map(c -> c.getContent()).orElse(null);
                    GraphNode fieldNode = GraphNode.of("Field", fieldFqn)
                        .with("name", var.getNameAsString())
                        .with("type", field.getElementType().asString())
                        .with("javadoc", fieldJavadoc);
                    writer.writeNode(fieldNode);
                    writer.writeEdge(fqn, fieldFqn, "HAS_FIELD");

                    // Abhängigkeit zum Feldtyp
                    String typeFqn = resolveTypeFqn(field.getElementType().asString(), packageName);
                    writer.writeEdge(fqn, typeFqn, "DEPENDS_ON");
                });
            });

            // Methoden
            type.findAll(MethodDeclaration.class).forEach(method -> {
                String sig = buildSignature(method);
                String methodFqn = fqn + "#" + sig;
                String methodJavadoc = method.getJavadocComment().map(c -> c.getContent()).orElse(null);

                GraphNode methodNode = GraphNode.of("Method", methodFqn)
                    .with("name", method.getNameAsString())
                    .with("signature", sig)
                    .with("returnType", method.getTypeAsString())
                    .with("javadoc", methodJavadoc)
                    .with("loc", method.getEnd().map(p -> p.line).orElse(0)
                        - method.getBegin().map(p -> p.line).orElse(0));
                writer.writeNode(methodNode);
                writer.writeEdge(fqn, methodFqn, "HAS_METHOD");

                // Methodenaufrufe
                method.findAll(MethodCallExpr.class).forEach(call -> {
                    try {
                        String calledFqn = call.resolve().getQualifiedSignature();
                        writer.writeEdge(methodFqn, calledFqn, "CALLS");
                    } catch (Exception ignored) {
                        // Symbol nicht auflösbar: externer Typ oder fehlendes Classpath
                    }
                });
            });
        });
    }

    private String resolveKind(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration coid) {
            return coid.isInterface() ? "interface" : "class";
        }
        if (type instanceof EnumDeclaration) return "enum";
        return "unknown";
    }

    private String buildSignature(MethodDeclaration method) {
        String params = method.getParameters().stream()
            .map(p -> p.getTypeAsString())
            .reduce((a, b) -> a + "," + b)
            .orElse("");
        return method.getNameAsString() + "(" + params + ")";
    }

    private boolean isTestFile(Path file) {
        String path = file.toString().replace('\\', '/');
        String name = file.getFileName().toString();
        return path.contains("/test/") || name.endsWith("Test.java") || name.endsWith("Tests.java") || name.endsWith("IT.java");
    }

    /** Best-effort: Volltypname wenn im selben Package, sonst Kurzname als Fallback. */
    private String resolveTypeFqn(String typeName, String currentPackage) {
        if (typeName.contains(".")) return typeName;
        return currentPackage + "." + typeName;
    }
}
