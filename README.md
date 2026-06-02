# lumesh-graph

Knowledge Graph für Java-Codebases. Parst Java-Quellcode, schreibt strukturierten Property-Graph nach Neo4j. Agenten können darin navigieren, fachliche Dokumentation wird direkt eingebunden.

![Java](https://img.shields.io/badge/Java-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)


## Ziel

```
Java Source + Javadoc + ADR/Markdown
        ↓  JavaParser (AST, LLM-frei)
  Property Graph Schema
        ↓  Neo4j Java Driver
     Neo4j DB
        ↓  Cypher Queries
     Shell / Tool
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
| `Class`    | `name`, `fqn`, `kind` (class/interface/enum), `visibility`, `isAbstract`, `isDeprecated`, `annotations[]`, `startLine`, `endLine`, `javadoc`, `file` |
| `Method`   | `name`, `fqn`, `signature`, `returnType`, `visibility`, `isStatic`, `isAbstract`, `isFinal`, `isDeprecated`, `isConstructor`, `annotations[]`, `javadoc`, `startLine`, `endLine`, `loc` |
| `Field`    | `name`, `fqn`, `type`, `visibility`, `isStatic`, `isFinal`, `isDeprecated`, `annotations[]`, `startLine`, `endLine`, `javadoc` |
| `Document`   | `title`, `kind` (ADR/Wiki/Readme), `path`, `content` |
| `SourceFile` | `fqn`, `path`, `hash` (SHA-256) — Incremental-State, intern verwaltet |

### Kanten

| Typ             | Von → Nach           | Bedeutung               |
|-----------------|----------------------|-------------------------|
| `CONTAINS`      | Package → Class      | Package enthält Klasse  |
| `HAS_METHOD`    | Class → Method       | Klasse hat Methode      |
| `HAS_FIELD`     | Class → Field        | Klasse hat Feld         |
| `CALLS`         | Method → Method      | Methodenaufruf          |
| `OVERRIDES`     | Method → Method      | Überschreibt Methode (`@Override`) |
| `THROWS`        | Method → Class       | Deklarierte Exception   |
| `INSTANTIATES`  | Method → Class       | `new ClassName()` im Methodenrumpf |
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
│   ├── ConsoleOutput.java           ← Fortschrittsausgabe
│   ├── cli/
│   │   ├── AnalyzeCommand.java      ← 'analyze': Quellcode → Graph
│   │   ├── DocCommand.java          ← 'doc': Markdown/ADR → Graph
│   │   └── QueryCommand.java        ← 'query': Cypher-Abfrage
│   ├── model/
│   │   ├── GraphNode.java           ← Node-Datenklasse
│   │   └── GraphEdge.java           ← Edge-Datenklasse
│   ├── parser/
│   │   ├── GraphSink.java           ← Schreib-Interface
│   │   ├── InMemoryGraphSink.java   ← In-Memory-Puffer (für Merge)
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
| `--clear` | — | — | Gesamten Graph vor Import leeren (inkl. Incremental-State) |
| `--exclude-tests` | — | — | Testklassen überspringen (`*Test.java`, `test/`-Verzeichnisse) |

Folgeläufe ohne `--clear` sind inkrementell: nur Dateien mit geändertem SHA-256-Hash werden neu geparst, unveränderte Dateien übersprungen.

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

// Alle Spring-Services (und Stereotypen)
MATCH (c:Class)
WHERE ANY(a IN c.annotations WHERE a IN ['Service', 'Component', 'Repository', 'RestController', 'Controller'])
RETURN c.fqn, c.annotations ORDER BY c.name

// Alle public Entry-Points (nicht statisch)
MATCH (c:Class)-[:HAS_METHOD]->(m:Method)
WHERE m.visibility = 'public' AND NOT m.isStatic
RETURN c.name, m.signature ORDER BY c.name

// Welche Methoden überschreiben eine Basis-Methode?
MATCH (m:Method)-[:OVERRIDES]->(base:Method)
RETURN m.fqn, base.fqn

// Welche Methoden werfen eine bestimmte Exception?
MATCH (m:Method)-[:THROWS]->(e:Class)
WHERE e.name = 'IOException'
RETURN m.fqn

// REST-Endpunkte finden (Annotation-Wert enthält Pfad)
MATCH (c:Class)-[:HAS_METHOD]->(m:Method)
WHERE ANY(a IN m.annotations WHERE a STARTS WITH 'GetMapping' OR a STARTS WITH 'PostMapping' OR a STARTS WITH 'RequestMapping')
RETURN c.name, m.name, m.annotations

// Deprecated-Code finden
MATCH (n) WHERE n.isDeprecated = true
RETURN labels(n)[0] AS typ, n.fqn ORDER BY typ

// Alle Konstruktoren einer Klasse
MATCH (c:Class {name: 'MyService'})-[:HAS_METHOD]->(m:Method)
WHERE m.isConstructor = true
RETURN m.signature, m.visibility

// Was instanziiert eine Klasse direkt?
MATCH (c:Class {name: 'MyService'})-[:HAS_METHOD]->(m:Method)-[:INSTANTIATES]->(created:Class)
RETURN m.name, created.name

// Quellcode-Position einer Methode
MATCH (c:Class {name: 'MyService'})-[:HAS_METHOD]->(m:Method {name: 'doSomething'})
RETURN c.file, m.startLine, m.endLine

// Inner Classes einer Klasse
MATCH (outer:Class)<-[:CONTAINS]-(pkg:Package)
MATCH (inner:Class) WHERE inner.fqn STARTS WITH outer.fqn + '.'
RETURN outer.name, collect(inner.name)
```

---

## Technologie-Stack

| Komponente | Bibliothek | Version |
|------------|-----------|---------|
| AST-Parser | JavaParser | 3.28.1 |
| Graph DB | Neo4j Community | 5.x |
| Java Driver | neo4j-java-driver | 6.1.0 |
| CLI | picocli | 4.7.7 |
| Build | Gradle + Shadow | 9.x |
| Java | OpenJDK | 25 |