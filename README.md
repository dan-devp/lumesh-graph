# lumesh-graph

Knowledge Graph für Java-Codebases. Parst Java-Quellcode, schreibt strukturierten Property-Graph nach Neo4j. Agenten können darin navigieren, fachliche Dokumentation wird direkt eingebunden.

## Ziel

```
Java Source + Javadoc + ADR/Markdown
        ↓  JavaParser (AST, LLM-frei)
  Property Graph Schema
        ↓  Neo4j Java Driver
     Neo4j DB
        ↓  Cypher Queries
  Agent / MCP Layer
```

- **LLM-frei** beim Graph-Aufbau (statische Analyse)
- Code + Dokumentation im selben Graphen
- Alles über Shell aufrufbar

---

## Graph-Schema

### Knoten

| Label      | Properties                                   |
|------------|----------------------------------------------|
| `Package`  | `name`, `fqn`                                |
| `Class`    | `name`, `fqn`, `kind` (class/interface/enum), `javadoc`, `file` |
| `Method`   | `name`, `signature`, `returnType`, `javadoc`, `loc` |
| `Field`    | `name`, `type`, `javadoc`                    |
| `Document` | `title`, `kind` (ADR/Wiki/Readme), `path`, `content` |

### Kanten

| Typ             | Von → Nach           | Bedeutung               |
|-----------------|----------------------|-------------------------|
| `CONTAINS`      | Package → Class      | Package enthält Klasse  |
| `HAS_METHOD`    | Class → Method       | Klasse hat Methode      |
| `HAS_FIELD`     | Class → Field        | Klasse hat Feld         |
| `CALLS`         | Method → Method      | Methodenaufruf          |
| `INHERITS_FROM` | Class → Class        | Vererbung               |
| `IMPLEMENTS`    | Class → Class        | Interface-Implementierung|
| `DEPENDS_ON`    | Class → Class        | Feldtyp-Abhängigkeit    |
| `DOCUMENTS`     | Document → Class     | Doku beschreibt Code    |

---

## Architektur (Module)

```
lumesh-graph/
├── src/main/java/dev/lumesh/graph/
│   ├── Main.java                    ← CLI-Einstieg (picocli)
│   ├── cli/
│   │   ├── AnalyzeCommand.java      ← 'analyze': Quellcode → Graph
│   │   ├── DocCommand.java          ← 'doc': Markdown/ADR → Graph
│   │   └── QueryCommand.java        ← 'query': Cypher-Abfrage
│   ├── parser/
│   │   ├── JavaSourceParser.java    ← JavaParser AST-Extraktion
│   │   └── GraphMerger.java         ← Core/Customer-Merge
│   └── neo4j/
│       └── GraphWriter.java         ← Neo4j-Schreiber
└── build.gradle
```

---

## Voraussetzungen

- Java 25
- Neo4j 5.x läuft lokal (oder Docker)
- Gradle Wrapper enthalten (`./gradlew`)

### Neo4j starten (Docker)

```bash
docker run -d --name lumesh-neo4j -p 7474:7474 -p 7687:7687 -e NEO4J_AUTH=neo4j/password neo4j:5
```

---

## Build

```bash
./gradlew build
```

Fat-JAR (alle Abhängigkeiten gebündelt):

```bash
./gradlew shadowJar
# → build/libs/lumesh-graph-all.jar
```

---

## Shell-Nutzung

### `analyze` — Quellcode → Graph

```
analyze --source <pfad> [--core <pfad>] [--uri <uri>] [-u <user>] [-p <pw>] [--clear] [--exclude-tests]
```

| Option | Pflicht | Default | Beschreibung |
|---|---|---|---|
| `--source <pfad>` | ✅ | — | Root-Verzeichnis des Java-Projekts (oder Customer-Zweig) |
| `--core <pfad>` | — | — | Core-Quellcode; Customer-Klassen überschreiben Core |
| `--uri <uri>` | — | `bolt://localhost:7687` | Neo4j Bolt URI |
| `-u, --user <user>` | — | `neo4j` | Neo4j Benutzer |
| `-p, --password <pw>` | — | `password` | Neo4j Passwort |
| `--clear` | — | — | Gesamten Graph vor Import leeren |
| `--exclude-tests` | — | — | Testklassen überspringen (`*Test.java`, `test/`-Verzeichnisse) |

Alle optionalen Flags sind frei kombinierbar. Beispiele:

```bash
# Minimal
./gradlew run --args="analyze --source /pfad/zum/java-projekt"

# Frisch starten, ohne Tests
./gradlew run --args="analyze --source /pfad/zum/java-projekt --clear --exclude-tests"

# Core + Customer, frisch, ohne Tests
./gradlew run --args="analyze --source /pfad/zum/customer --core /pfad/zum/core --clear --exclude-tests"

# Remote Neo4j
./gradlew run --args="analyze --source /pfad/zum/java-projekt --uri bolt://db-host:7687 -u neo4j -p geheim"
```

---

### `doc` — Markdown/ADR → Graph

```
doc --source <pfad> [--link-to <fqn>] [--kind <label>] [--uri <uri>] [-u <user>] [-p <pw>]
```

| Option | Pflicht | Default | Beschreibung |
|---|---|---|---|
| `--source <pfad>` | ✅ | — | Verzeichnis mit Markdown-Dateien |
| `--link-to <fqn>` | — | — | FQN einer Klasse → erzeugt `DOCUMENTS`-Kante |
| `--kind <label>` | — | `Doc` | Dokumenttyp (z. B. `ADR`, `Wiki`, `Readme`) |
| `--uri <uri>` | — | `bolt://localhost:7687` | Neo4j Bolt URI |
| `-u, --user <user>` | — | `neo4j` | Neo4j Benutzer |
| `-p, --password <pw>` | — | `password` | Neo4j Passwort |

```bash
# ADRs importieren und mit Klasse verknüpfen
./gradlew run --args="doc --source /pfad/zum/docs --kind ADR --link-to dev.example.MyClass"
```

---

### `query` — Cypher-Query ausführen

```
query <cypher> [--uri <uri>] [-u <user>] [-p <pw>]
```

| Option | Pflicht | Default | Beschreibung |
|---|---|---|---|
| `<cypher>` | ✅ | — | Cypher-Query (positionales Argument, kein Flag) |
| `--uri <uri>` | — | `bolt://localhost:7687` | Neo4j Bolt URI |
| `-u, --user <user>` | — | `neo4j` | Neo4j Benutzer |
| `-p, --password <pw>` | — | `password` | Neo4j Passwort |

```bash
./gradlew run --args="query \"MATCH (c:Class) RETURN c.name\""
```

---

### Fat-JAR (nach `./gradlew shadowJar`)

```bash
java -jar build/libs/lumesh-graph-all.jar analyze --source /pfad/zum/java-projekt --clear --exclude-tests
java -jar build/libs/lumesh-graph-all.jar query "MATCH (c:Class)-[:CALLS]->(m:Method) RETURN c.name, m.name"
```

### Hilfe

```bash
./gradlew run --args="--help"
./gradlew run --args="analyze --help"
./gradlew run --args="doc --help"
./gradlew run --args="query --help"
```

---

## Nützliche Cypher-Queries

```cypher
// Alle Klassen
MATCH (c:Class) RETURN c.name, c.fqn ORDER BY c.name

// Vererbungshierarchie
MATCH path = (c:Class)-[:INHERITS_FROM*]->(parent:Class)
RETURN path

// Welche Klassen implementieren ein Interface?
MATCH (c:Class)-[:IMPLEMENTS]->(i:Class {name: 'MyInterface'})
RETURN c.name

// Methodenaufruf-Kette von einer Klasse
MATCH (c:Class {name: 'MyService'})-[:HAS_METHOD]->(m:Method)-[:CALLS]->(called:Method)
RETURN m.name, called.name

// Dokumentation zu einer Klasse
MATCH (d:Document)-[:DOCUMENTS]->(c:Class {name: 'MyClass'})
RETURN d.title, d.content
```

---

## Roadmap

| Phase | Inhalt | Status |
|-------|--------|--------|
| **A** | Gradle-Setup, JavaParser → Neo4j, CLI | ✅ |
| **B** | Javadoc-Extraktion, Markdown/ADR-Import | 🔲 |
| **C** | Fraunhofer CPG: CFG + DFG für tiefere Analyse | 🔲 |
| **D** | MCP-Server: Agent-Tools auf Graph-Basis | 🔲 |
| **E** | Inkrementelles Update (nur geänderte Dateien) | 🔲 |

---

## Technologie-Stack

| Komponente | Bibliothek | Version |
|------------|-----------|---------|
| AST-Parser | JavaParser | 3.26.x |
| Graph DB | Neo4j Community | 5.x |
| Java Driver | neo4j-java-driver | 5.x |
| CLI | picocli | 4.7.x |
| Build | Gradle + Shadow | 8.x |
| Java | OpenJDK | 17+ |