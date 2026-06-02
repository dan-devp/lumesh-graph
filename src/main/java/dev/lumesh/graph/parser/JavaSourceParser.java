package dev.lumesh.graph.parser;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import dev.lumesh.graph.model.GraphNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

            int skipped = 0;
            for (int i = 0; i < total; i++) {
                Path file = javaFiles.get(i);
                try {
                    String filePath = file.toAbsolutePath().toString();
                    String hash = computeHash(file);
                    if (writer.isFileUnchanged(filePath, hash)) {
                        skipped++;
                        System.out.printf("[%d/%d] %s - SKIPPED%n", i + 1, total, file.getFileName());
                        continue;
                    }
                    System.out.printf("[%d/%d] %s%n", i + 1, total, file.getFileName());
                    writer.purgeFile(filePath);
                    parseFile(file);
                    writer.recordFile(filePath, hash);
                } catch (Exception e) {
                    System.err.printf("  -> Skip: %s%n", e.getMessage());
                }
            }
            if (skipped > 0) {
                System.out.printf("Skipped %d unchanged file(s).%n", skipped);
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

            TypeDeclaration<?> typedDecl = (TypeDeclaration<?>) type;
            String simpleName = typedDecl.getNameAsString();
            String fqn = buildFqn(typedDecl, packageName);
            String kind = resolveKind(type);
            String javadoc = typedDecl.getJavadocComment().map(JavadocComment::getContent).orElse(null);
            String visibility = typedDecl.getAccessSpecifier().asString();
            boolean isAbstract = (type instanceof ClassOrInterfaceDeclaration c) && c.isAbstract();
            boolean isDeprecated = typedDecl.getAnnotationByName("Deprecated").isPresent();
            List<String> typeAnnotations = typedDecl.getAnnotations().stream()
                .map(a -> a.toString().substring(1))
                .toList();

            GraphNode classNode = GraphNode.of("Class", fqn)
                .with("name", simpleName)
                .with("kind", kind)
                .with("visibility", visibility)
                .with("isAbstract", isAbstract)
                .with("isDeprecated", isDeprecated)
                .with("annotations", typeAnnotations.isEmpty() ? null : typeAnnotations)
                .with("startLine", typedDecl.getBegin().map(p -> p.line).orElse(0))
                .with("endLine", typedDecl.getEnd().map(p -> p.line).orElse(0))
                .with("javadoc", javadoc)
                .with("file", file.toString());
            writer.writeNode(classNode);
            writer.writeEdge(packageName, fqn, "CONTAINS");

            if (type instanceof ClassOrInterfaceDeclaration coid) {
                coid.getExtendedTypes().forEach(ext -> {
                    String parentFqn = resolveTypeFqn(ext.getNameAsString(), packageName);
                    writer.writeEdge(fqn, parentFqn, "INHERITS_FROM");
                });
                coid.getImplementedTypes().forEach(impl -> {
                    String ifaceFqn = resolveTypeFqn(impl.getNameAsString(), packageName);
                    writer.writeEdge(fqn, ifaceFqn, "IMPLEMENTS");
                });
            }

            // Felder (nur direkte, keine verschachtelten Klassen)
            typedDecl.getFields().forEach(field -> {
                String fieldVisibility = field.getAccessSpecifier().asString();
                boolean fieldStatic = field.isStatic();
                boolean fieldFinal = field.isFinal();
                boolean fieldDeprecated = field.getAnnotationByName("Deprecated").isPresent();
                List<String> fieldAnnotations = field.getAnnotations().stream()
                    .map(a -> a.toString().substring(1))
                    .toList();

                field.getVariables().forEach(var -> {
                    String fieldFqn = fqn + "#" + var.getNameAsString();
                    String fieldJavadoc = field.getJavadocComment().map(c -> c.getContent()).orElse(null);
                    GraphNode fieldNode = GraphNode.of("Field", fieldFqn)
                        .with("name", var.getNameAsString())
                        .with("type", field.getElementType().asString())
                        .with("visibility", fieldVisibility)
                        .with("isStatic", fieldStatic)
                        .with("isFinal", fieldFinal)
                        .with("isDeprecated", fieldDeprecated)
                        .with("annotations", fieldAnnotations.isEmpty() ? null : fieldAnnotations)
                        .with("startLine", field.getBegin().map(p -> p.line).orElse(0))
                        .with("endLine", field.getEnd().map(p -> p.line).orElse(0))
                        .with("javadoc", fieldJavadoc);
                    writer.writeNode(fieldNode);
                    writer.writeEdge(fqn, fieldFqn, "HAS_FIELD");

                    String typeFqn = resolveTypeFqn(field.getElementType().asString(), packageName);
                    writer.writeEdge(fqn, typeFqn, "DEPENDS_ON");
                });
            });

            // Methoden (nur direkte, keine verschachtelten Klassen)
            typedDecl.getMethods().forEach(method -> {
                String sig = buildSignature(method);
                String methodFqn = fqn + "#" + sig;
                String methodJavadoc = method.getJavadocComment().map(c -> c.getContent()).orElse(null);
                String methodVisibility = method.getAccessSpecifier().asString();
                boolean methodStatic = method.isStatic();
                boolean methodAbstract = method.isAbstract();
                boolean methodFinal = method.isFinal();
                boolean methodDeprecated = method.getAnnotationByName("Deprecated").isPresent();
                List<String> methodAnnotations = method.getAnnotations().stream()
                    .map(a -> a.toString().substring(1))
                    .toList();

                GraphNode methodNode = GraphNode.of("Method", methodFqn)
                    .with("name", method.getNameAsString())
                    .with("signature", sig)
                    .with("returnType", method.getTypeAsString())
                    .with("visibility", methodVisibility)
                    .with("isStatic", methodStatic)
                    .with("isAbstract", methodAbstract)
                    .with("isFinal", methodFinal)
                    .with("isDeprecated", methodDeprecated)
                    .with("annotations", methodAnnotations.isEmpty() ? null : methodAnnotations)
                    .with("javadoc", methodJavadoc)
                    .with("startLine", method.getBegin().map(p -> p.line).orElse(0))
                    .with("endLine", method.getEnd().map(p -> p.line).orElse(0))
                    .with("loc", method.getEnd().map(p -> p.line).orElse(0)
                        - method.getBegin().map(p -> p.line).orElse(0));
                writer.writeNode(methodNode);
                writer.writeEdge(fqn, methodFqn, "HAS_METHOD");

                method.getThrownExceptions().forEach(thrownType -> {
                    String exceptionFqn = resolveTypeFqn(thrownType.asString(), packageName);
                    writer.writeEdge(methodFqn, exceptionFqn, "THROWS");
                });

                if (method.getAnnotationByName("Override").isPresent() && type instanceof ClassOrInterfaceDeclaration coidOvr) {
                    Stream.concat(coidOvr.getExtendedTypes().stream(), coidOvr.getImplementedTypes().stream())
                        .forEach(parentType -> {
                            String parentClassFqn = resolveTypeFqn(parentType.getNameAsString(), packageName);
                            writer.writeEdge(methodFqn, parentClassFqn + "#" + sig, "OVERRIDES");
                        });
                }

                method.findAll(ObjectCreationExpr.class).forEach(expr -> {
                    String instantiatedFqn = resolveTypeFqn(expr.getTypeAsString(), packageName);
                    writer.writeEdge(methodFqn, instantiatedFqn, "INSTANTIATES");
                });

                method.findAll(MethodCallExpr.class).forEach(call -> {
                    try {
                        String calledFqn = call.resolve().getQualifiedSignature();
                        writer.writeEdge(methodFqn, calledFqn, "CALLS");
                    } catch (Exception ignored) {
                        // Symbol nicht auflösbar: externer Typ oder fehlendes Classpath
                    }
                });
            });

            // Konstruktoren
            typedDecl.getConstructors().forEach(ctor -> {
                String sig = buildConstructorSignature(ctor);
                String ctorFqn = fqn + "#" + sig;
                String ctorJavadoc = ctor.getJavadocComment().map(c -> c.getContent()).orElse(null);
                String ctorVisibility = ctor.getAccessSpecifier().asString();
                List<String> ctorAnnotations = ctor.getAnnotations().stream()
                    .map(a -> a.toString().substring(1))
                    .toList();

                GraphNode ctorNode = GraphNode.of("Method", ctorFqn)
                    .with("name", ctor.getNameAsString())
                    .with("signature", sig)
                    .with("returnType", "void")
                    .with("visibility", ctorVisibility)
                    .with("isConstructor", true)
                    .with("isStatic", false)
                    .with("isAbstract", false)
                    .with("isFinal", false)
                    .with("isDeprecated", ctor.getAnnotationByName("Deprecated").isPresent())
                    .with("annotations", ctorAnnotations.isEmpty() ? null : ctorAnnotations)
                    .with("javadoc", ctorJavadoc)
                    .with("startLine", ctor.getBegin().map(p -> p.line).orElse(0))
                    .with("endLine", ctor.getEnd().map(p -> p.line).orElse(0))
                    .with("loc", ctor.getEnd().map(p -> p.line).orElse(0)
                        - ctor.getBegin().map(p -> p.line).orElse(0));
                writer.writeNode(ctorNode);
                writer.writeEdge(fqn, ctorFqn, "HAS_METHOD");

                ctor.getThrownExceptions().forEach(thrownType -> {
                    String exceptionFqn = resolveTypeFqn(thrownType.asString(), packageName);
                    writer.writeEdge(ctorFqn, exceptionFqn, "THROWS");
                });

                ctor.findAll(ObjectCreationExpr.class).forEach(expr -> {
                    String instantiatedFqn = resolveTypeFqn(expr.getTypeAsString(), packageName);
                    writer.writeEdge(ctorFqn, instantiatedFqn, "INSTANTIATES");
                });

                ctor.findAll(MethodCallExpr.class).forEach(call -> {
                    try {
                        String calledFqn = call.resolve().getQualifiedSignature();
                        writer.writeEdge(ctorFqn, calledFqn, "CALLS");
                    } catch (Exception ignored) {
                        // Symbol nicht auflösbar
                    }
                });
            });
        });
    }

    /** Rekursiv korrekte FQN für inner classes: pkg.Outer.Inner */
    private String buildFqn(TypeDeclaration<?> type, String packageName) {
        return type.findAncestor(TypeDeclaration.class)
            .map(parent -> buildFqn((TypeDeclaration<?>) parent, packageName) + "." + type.getNameAsString())
            .orElse(packageName + "." + type.getNameAsString());
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

    private String buildConstructorSignature(ConstructorDeclaration ctor) {
        String params = ctor.getParameters().stream()
            .map(p -> p.getTypeAsString())
            .reduce((a, b) -> a + "," + b)
            .orElse("");
        return ctor.getNameAsString() + "(" + params + ")";
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

    private String computeHash(Path file) throws IOException {
        byte[] content = Files.readAllBytes(file);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
