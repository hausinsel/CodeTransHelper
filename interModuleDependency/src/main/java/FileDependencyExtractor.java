import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrahiert einen Datei-zu-Datei-Abhängigkeitsgraphen aus einer von Joern
 * exportierten CPG-DOT-Datei (cpg.runScript("dump-cpg-as-dot.sc") oder
 * `joern-export --repr=all --format=dot`).
 * <p>
 * Vorgehen:
 * 1) Zwei-Pass-Parser liest Knoten und Kanten aus dem DOT.
 * 2) Für jeden Knoten wird die zugehörige Quelldatei aufgelöst, entweder
 * ueber die FILENAME-Property (z.B. an METHOD-Knoten) oder ueber das
 * Folgen einer SOURCE_FILE-Kante.
 * 3) Auf File-Ebene werden CALL-Kanten aggregiert: ein Aufruf von
 * A nach B bedeutet "Datei von A haengt von Datei von B ab".
 * 4) Optional werden IMPORTS (#include) als Header-Abhaengigkeiten
 * hinzugefuegt.
 * 5) Pseudo-Files (<includes>, <unknown>, <empty>) und External-Methoden
 * (IS_EXTERNAL="true", z.B. printf, <operator>.assignment) werden
 * herausgefiltert.
 * 6) Ausgabe als DOT plus topologisch sortierte Migrationsreihenfolge.
 * Zyklen werden erkannt und gemeldet (in C++/Kylix realistisch).
 * <p>
 * Aufruf:
 * javac FileDependencyExtractor.java
 * java FileDependencyExtractor &lt;export.dot&gt; [output.dot]
 */
public class FileDependencyExtractor {

    // ---------- Datenmodell ----------

    /**
     * Ein Knoten im CPG. Wir speichern nur, was wir brauchen.
     */
    static final class Node {
        final long id;
        final String label;                       // z.B. "METHOD", "FILE", "CALL"
        final Map<String, String> props;          // FILENAME, NAME, IS_EXTERNAL, ...

        Node(long id, String label, Map<String, String> props) {
            this.id = id;
            this.label = label;
            this.props = props;
        }
    }

    /**
     * Eine Kante im CPG.
     */
    static final class Edge {
        final long src;
        final long tgt;
        final String label;                       // z.B. "SOURCE_FILE", "CALL"

        Edge(long src, long tgt, String label) {
            this.src = src;
            this.tgt = tgt;
            this.label = label;
        }
    }

    // ---------- Regex zum Parsen ----------
    // Knoten:  "12345" [label="LABEL" KEY="VALUE" ...];
    // Kante:   "12345" -> "67890" [label="LABEL" ...];
    // DOT erlaubt Backslash-Escapes in Strings ( \" ).

    private static final Pattern NODE_LINE = Pattern.compile(
            "^\\s*\"(\\d+)\"\\s*\\[(.*)]\\s*;\\s*$", Pattern.DOTALL);
    private static final Pattern EDGE_LINE = Pattern.compile(
            "^\\s*\"(\\d+)\"\\s*->\\s*\"(\\d+)\"\\s*\\[(.*)]\\s*;\\s*$", Pattern.DOTALL);
    private static final Pattern STMT_START = Pattern.compile("^\\s*\"\\d+\".*");
    /**
     * Findet KEY="VALUE" innerhalb der eckigen Klammern; toleriert \" und \\.
     */
    private static final Pattern ATTR = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*)=\"((?:\\\\.|[^\"\\\\])*)\"");

    // Nur diese Kanten-Typen sind fuer File-Dependencies relevant.
    // Alles andere (CFG, REACHING_DEF, DOMINATES, CDG, EVAL_TYPE, ...) waere
    // bei einer 2.7 GB CPG nur Heap-Last.
    private static final Set<String> RELEVANT_EDGE_LABELS = Set.of(
            "AST", "SOURCE_FILE", "CALL", "IMPORTS");

    // ---------- Caches ----------
    private final Map<Long, Node> nodes = new HashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final Map<Long, String> nodeToFile = new HashMap<>();   // Knoten-ID -> Dateiname
    // AST: child -> parent (CPG-AST ist ein Baum, daher genuegt ein Parent).
    // Wird in resolveFiles() befuellt und in fileOf() fuer O(depth)-Lookups genutzt.
    private final Map<Long, Long> astChildToParent = new HashMap<>();

    // ---
    int getNodesCount(){
        return nodes.size();
    }

    int getEdgesCount(){
        return edges.size();
    }
    // ---

    // Pseudo-Files, die wir aus dem Endergebnis filtern.
    private static final Set<String> PSEUDO_FILES = Set.of(
            "<includes>", "<unknown>", "<empty>", "<global>");

    // ---------- Pass 1+2: Parsen ----------

    public void parse(Path dotFile) throws IOException {
        // Groesserer Reader-Buffer fuer ~GB-Dateien.
        try (BufferedReader br = new BufferedReader(new FileReader(dotFile.toFile()), 1 << 20)) {
            StringBuilder buf = new StringBuilder();
            String line;
            long lines = 0;
            while ((line = br.readLine()) != null) {
                lines++;
                if ((lines & 0xFFFFF) == 0) {                  // alle ~1M Zeilen
                    System.err.printf("  parsed %d lines, nodes=%d edges=%d%n",
                            lines, nodes.size(), edges.size());
                }
                // Solange noch nichts gepuffert ist, ignorieren wir alles,
                // was nicht wie ein Statement-Anfang aussieht (digraph {, }, leer).
                if (buf.length() == 0 && !STMT_START.matcher(line).matches()) {
                    continue;
                }
                if (buf.length() > 0) {
                    buf.append('\n');
                }
                buf.append(line);
                if (statementComplete(buf)) {
                    handleStatement(buf.toString());
                    buf.setLength(0);
                }
            }
        }
    }

    /**
     * True, wenn der Puffer eine vollstaendige Anweisung enthaelt (endet mit "];" außerhalb Strings).
     */
    private static boolean statementComplete(CharSequence s) {
        boolean inString = false;
        boolean escape = false;
        int lastClose = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString && c == ']') {
                lastClose = i;
            }
            if (!inString && c == ';' && lastClose >= 0 && lastClose < i) {
                // alles nach lastClose bis i sollte Whitespace sein → fertiges Statement
                for (int k = lastClose + 1; k < i; k++) {
                    if (!Character.isWhitespace(s.charAt(k))) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    private void handleStatement(String stmt) {
        Matcher mNode = NODE_LINE.matcher(stmt);
        if (mNode.matches()) {
            long id = Long.parseLong(mNode.group(1));
            Map<String, String> props = parseAttrs(mNode.group(2));
            String label = props.remove("label");
            if (label != null) {
                nodes.put(id, new Node(id, label, props));
            }
            return;
        }
        Matcher mEdge = EDGE_LINE.matcher(stmt);
        if (mEdge.matches()) {
            long src = Long.parseLong(mEdge.group(1));
            long tgt = Long.parseLong(mEdge.group(2));
            Map<String, String> props = parseAttrs(mEdge.group(3));
            String label = props.getOrDefault("label", "");
            // Frueher Filter: nur die wenigen Edge-Typen behalten, die wir spaeter
            // wirklich auswerten. Spart bei grossen CPGs sehr viel Heap.
            if (RELEVANT_EDGE_LABELS.contains(label)) {
                edges.add(new Edge(src, tgt, label));
            }
        }
    }

    private static Map<String, String> parseAttrs(String inside) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = ATTR.matcher(inside);
        while (m.find()) {
            // Backslash-Escapes auflösen: \" -> ",  \\ -> \,  \n -> Newline
            String val = m.group(2)
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\\\", "\\");
            out.put(m.group(1), val);
        }
        return out;
    }

    // ---------- Auflösung: Knoten -> Datei ----------

    /**
     * Baut die Map "Knoten-ID -> Dateiname" auf.
     * Zwei Quellen:
     * - direkte FILENAME-Property (an METHOD, TYPE_DECL, NAMESPACE_BLOCK, FILE)
     * - SOURCE_FILE-Kante zu einem FILE-Knoten
     */
    public void resolveFiles() {
        // 1) FILE-Knoten registrieren sich selbst.
        for (Node n : nodes.values()) {
            if ("FILE".equals(n.label)) {
                String name = n.props.getOrDefault("NAME", "<unnamed>");
                nodeToFile.put(n.id, name);
            }
        }
        // 2) Direkte FILENAME-Property uebernehmen.
        for (Node n : nodes.values()) {
            String fn = n.props.get("FILENAME");
            if (fn != null && !fn.isEmpty()) {
                nodeToFile.putIfAbsent(n.id, fn);
            }
        }
        // 3) SOURCE_FILE-Kanten folgen UND parallel AST-Index aufbauen.
        //    Single pass ueber edges fuer beides.
        for (Edge e : edges) {
            if ("SOURCE_FILE".equals(e.label)) {
                Node fileNode = nodes.get(e.tgt);
                if (fileNode != null && "FILE".equals(fileNode.label)) {
                    String name = fileNode.props.getOrDefault("NAME", "<unnamed>");
                    nodeToFile.putIfAbsent(e.src, name);
                }
            } else if ("AST".equals(e.label)) {
                // CPG-AST ist ein Baum: jedes Kind hat genau einen Parent.
                astChildToParent.putIfAbsent(e.tgt, e.src);
            }
        }
        // 4) Fuer Knoten ohne direkte Datei findet fileOf() den naechsten
        //    AST-Vorfahren in O(depth) via astChildToParent + Memoization.
    }

    /**
     * Findet die Datei eines beliebigen Knotens. Wenn er selbst keine
     * direkte Datei hat, folgen wir der AST-Parent-Kette aufwaerts.
     * <p>
     * Komplexitaet: O(depth) pro Aufruf, amortisiert O(1) durch Memoization
     * der gesamten besuchten Kette in nodeToFile.
     */
    private String fileOf(long id) {
        String f = nodeToFile.get(id);
        if (f != null) {
            return f;
        }
        // Kette von Knoten ohne bekannte Datei einsammeln, bis wir einen
        // Vorfahren mit Datei finden (oder die Wurzel erreichen).
        List<Long> chain = new ArrayList<>(8);
        long cur = id;
        for (int hops = 0; hops < 100_000; hops++) {
            chain.add(cur);
            Long parent = astChildToParent.get(cur);
            if (parent == null) {
                return null;                 // Kein Vorfahre mit Datei.
            }
            String pf = nodeToFile.get(parent);
            if (pf != null) {
                // Memoize: alle Zwischenknoten zeigen jetzt auch auf diese Datei.
                for (Long n : chain) {
                    nodeToFile.put(n, pf);
                }
                return pf;
            }
            cur = parent;
        }
        return null;
    }

    // ---------- Aggregation: File-Dependencies ----------

    /**
     * (fromFile -> Menge der Dateien, von denen fromFile abhaengt).
     */
    private final Map<String, Set<String>> fileDeps = new TreeMap<>();
    private final Map<String, Map<String, Integer>> fileDepWeights = new TreeMap<>();

    public void aggregateCallDependencies() {
        for (Edge e : edges) {
            if (!"CALL".equals(e.label)) {
                continue;
            }

            Node callSite = nodes.get(e.src);
            Node callee = nodes.get(e.tgt);
            if (callSite == null || callee == null) {
                continue;
            }

            // Aufrufe von "<operator>...", printf etc. uninteressant
            if ("true".equals(callee.props.get("IS_EXTERNAL"))) {
                continue;
            }
            String calleeName = callee.props.getOrDefault("NAME", "");
            if (calleeName.startsWith("<operator>")) {
                continue;
            }

            String srcFile = fileOf(callSite.id);
            String tgtFile = fileOf(callee.id);
            addDep(srcFile, tgtFile, 1);
        }
    }

    /**
     * Optional: #include-Beziehungen.
     * IMPORT-Knoten liegen in einer Datei (deren METHOD <global>), die DEPENDENCY
     * traegt den include-Namen. Wir matchen den DEPENDENCY-Namen gegen vorhandene
     * FILE-Namen, um z.B. "mathlib.h" -&gt; "mathlib.h" aufzuloesen.
     */
    public void aggregateIncludeDependencies() {
        // Sammle alle bekannten Dateinamen (basename-only fuer Matching)
        Map<String, String> basenameToFile = new HashMap<>();
        for (String f : nodeToFile.values()) {
            if (PSEUDO_FILES.contains(f)) {
                continue;
            }
            String bn = Path.of(f).getFileName().toString();
            basenameToFile.putIfAbsent(bn, f);
        }

        // IMPORTS-Kante: IMPORT -> DEPENDENCY
        for (Edge e : edges) {
            if (!"IMPORTS".equals(e.label)) {
                continue;
            }
            Node imp = nodes.get(e.src);
            Node dep = nodes.get(e.tgt);
            if (imp == null || dep == null) {
                continue;
            }
            String included = dep.props.getOrDefault("NAME", "");
            String resolvedTgt = basenameToFile.get(included);
            if (resolvedTgt == null) {
                continue;            // System-Header wie stdio.h überspringen
            }

            String srcFile = fileOf(imp.id);
            addDep(srcFile, resolvedTgt, 1);
        }
    }

    // ---------- Root-Aufloesung + transitive Huelle ----------

    /**
     * Findet alle Dateien, die zu einem benutzerseitig angegebenen "Wurzel"-Namen
     * gehoeren. Match-Reihenfolge (erste nichtleere Stufe gewinnt):
     * <ol>
     *   <li>Voller Pfadname einer FILE (z.B. {@code "src/db/TConnectionPool.cpp"})</li>
     *   <li>Basename einer FILE (z.B. {@code "TConnectionPool.cpp"})</li>
     *   <li>METHOD/TYPE_DECL/NAMESPACE_BLOCK mit
     *       {@code NAME == rootName} oder {@code FULL_NAME == rootName}
     *       (z.B. {@code "main"}, {@code "TConnectionPool.Commit"})
     *       — Datei wird via {@link #fileOf(long)} aufgeloest.</li>
     * </ol>
     * Gibt eine ggf. leere Menge zurueck. Mehrere Treffer auf gleicher Stufe
     * sind erlaubt (z.B. mehrere Methoden gleichen Namens in verschiedenen
     * Dateien) — alle deren Dateien werden zurueckgegeben.
     */
    public Set<String> resolveRootFiles(String rootName) {
        Set<String> hits = new LinkedHashSet<>();

        // Stufe 1+2: gegen Dateinamen matchen (auf den unique values von nodeToFile).
        Set<String> allFiles = new HashSet<>(nodeToFile.values());
        for (String f : allFiles) {
            if (PSEUDO_FILES.contains(f)) {
                continue;
            }
            if (f.equals(rootName)) {
                hits.add(f);
                continue;
            }
            String bn = Path.of(f).getFileName().toString();
            if (bn.equals(rootName)) {
                hits.add(f);
            }
        }
        if (!hits.isEmpty()) {
            return hits;
        }

        // Stufe 3: NAME/FULL_NAME auf Definitions-Knoten matchen.
        Set<String> rootLabels = Set.of("METHOD", "TYPE_DECL", "NAMESPACE_BLOCK");
        for (Node n : nodes.values()) {
            if (!rootLabels.contains(n.label)) {
                continue;
            }
            String nm = n.props.get("NAME");
            String fn = n.props.get("FULL_NAME");
            if (rootName.equals(nm) || rootName.equals(fn)) {
                String f = fileOf(n.id);
                if (f != null && !PSEUDO_FILES.contains(f)) {
                    hits.add(f);
                }
            }
        }
        return hits;
    }

    /**
     * Reduziert {@link #fileDeps}/{@link #fileDepWeights} auf die Dateien, die
     * von {@code roots} aus per transitiver Huelle ueber "haengt-ab"-Kanten
     * erreichbar sind. Roots selbst sind immer im Ergebnis enthalten — auch
     * wenn sie keine ausgehenden Abhaengigkeiten haben (dann erscheinen sie
     * als isolierter Knoten im DOT-Output).
     *
     * @return Menge der erreichbaren Dateien (Roots inklusive)
     */
    public Set<String> restrictToReachable(Set<String> roots) {
        Set<String> reachable = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            String f = stack.pop();
            if (!reachable.add(f)) {
                continue;
            }
            Set<String> deps = fileDeps.get(f);
            if (deps != null) {
                for (String d : deps) {
                    if (!reachable.contains(d)) {
                        stack.push(d);
                    }
                }
            }
        }

        // fileDeps: nur Keys + Values im reachable-Set behalten.
        fileDeps.keySet().retainAll(reachable);
        for (Set<String> s : fileDeps.values()) {
            s.retainAll(reachable);
        }
        fileDepWeights.keySet().retainAll(reachable);
        for (Map<String, Integer> m : fileDepWeights.values()) {
            m.keySet().retainAll(reachable);
        }

        // Roots immer als Knoten beibehalten, auch wenn sie nichts referenzieren.
        for (String r : roots) {
            fileDeps.putIfAbsent(r, new TreeSet<>());
        }
        return reachable;
    }

    private void addDep(String from, String to, int weight) {
        if (from == null || to == null) {
            return;
        }
        if (from.equals(to)) {
            return;                      // Selbstkanten ignorieren
        }
        if (PSEUDO_FILES.contains(from) || PSEUDO_FILES.contains(to)) {
            return;
        }

        fileDeps.computeIfAbsent(from, k -> new TreeSet<>()).add(to);
        fileDepWeights
                .computeIfAbsent(from, k -> new TreeMap<>())
                .merge(to, weight, Integer::sum);
    }

    // ---------- Ausgabe: DOT + Topologische Sortierung ----------

    public void writeDot(Path out) throws IOException {
        try (PrintWriter pw = new PrintWriter(out.toFile())) {
            pw.println("digraph FileDependencies {");
            pw.println("  rankdir=LR;");
            pw.println("  node [shape=box, style=rounded];");
            // Alle Dateien als Knoten
            Set<String> allFiles = new TreeSet<>();
            allFiles.addAll(fileDeps.keySet());
            fileDeps.values().forEach(allFiles::addAll);
            for (String f : allFiles) {
                pw.printf("  \"%s\";%n", f);
            }
            // Kanten mit Gewicht als Label
            for (var entry : fileDepWeights.entrySet()) {
                String from = entry.getKey();
                for (var w : entry.getValue().entrySet()) {
                    pw.printf("  \"%s\" -> \"%s\" [label=\"%d\"];%n",
                            from, w.getKey(), w.getValue());
                }
            }
            pw.println("}");
        }
    }

    /**
     * Topologische Sortierung (Kahn). Liefert Migrationsreihenfolge:
     * Dateien ohne Abhaengigkeiten zuerst; eine Datei wird erst migriert,
     * wenn alle ihre Abhaengigkeitsziele migriert sind.
     * Falls Zyklen existieren, werden sie separat ausgegeben.
     */
    public List<String> topologicalOrder() {
        // Knotenset
        Set<String> allFiles = new TreeSet<>();
        allFiles.addAll(fileDeps.keySet());
        fileDeps.values().forEach(allFiles::addAll);

        // In-Degree zaehlen — fuer "Migrationsreihenfolge" drehen wir die
        // Kantenrichtung: A -> B (A haengt von B ab) heisst "B vor A".
        // Also zaehlen wir, wie viele Abhaengigkeiten A noch offen hat.
        Map<String, Integer> openDeps = new HashMap<>();
        for (String f : allFiles) {
            openDeps.put(f, 0);
        }
        for (var e : fileDeps.entrySet()) {
            openDeps.merge(e.getKey(), e.getValue().size(), Integer::sum);
        }

        Deque<String> ready = new ArrayDeque<>();
        for (var e : openDeps.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }

        // Reverse-Adjazenz: wenn A -> B, dann erscheint A in revAdj[B]
        Map<String, List<String>> revAdj = new HashMap<>();
        for (var e : fileDeps.entrySet()) {
            for (String tgt : e.getValue()) {
                revAdj.computeIfAbsent(tgt, k -> new ArrayList<>()).add(e.getKey());
            }
        }

        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String f = ready.poll();
            order.add(f);
            for (String dependent : revAdj.getOrDefault(f, List.of())) {
                int rem = openDeps.merge(dependent, -1, Integer::sum);
                if (rem == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (order.size() < allFiles.size()) {
            Set<String> remaining = new TreeSet<>(allFiles);
            remaining.removeAll(order);
            System.err.println("WARNUNG: Zyklische Abhaengigkeiten gefunden, "
                    + "folgende Dateien sind Teil von Zyklen: " + remaining);
            order.addAll(remaining); // alphabetisch hintenan; manuell aufloesen!
        }
        return order;
    }

    // ---------- main ----------

    /*
    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args[0].equals("--help") || args[0].equals("-h")) {
            System.out.println("Usage: java FileDependencyExtractor <export.dot> [output.dot]");
            System.out.println();
            System.out.println("Liest eine von Joern erzeugte CPG-DOT-Datei und erzeugt einen");
            System.out.println("File-zu-File-Abhaengigkeitsgraphen sowie eine topologisch");
            System.out.println("sortierte Migrationsreihenfolge auf stdout.");
            return;
        }
        Path in = Path.of(args[0]);
        Path out = Path.of(args.length >= 2 ? args[1] : "file-dependencies.dot");

        FileDependencyExtractor x = new FileDependencyExtractor();
        x.parse(in);
        System.err.printf("Parsed %d nodes, %d edges%n", x.nodes.size(), x.edges.size());

        x.resolveFiles();
        x.aggregateCallDependencies();
        x.aggregateIncludeDependencies();
        x.writeDot(out);
        System.err.println("DOT geschrieben nach: " + out);

        System.out.println("Migrationsreihenfolge (Blaetter zuerst):");
        int i = 1;
        for (String f : x.topologicalOrder()) {
            System.out.printf("  %3d. %s%n", i++, f);
        }
    }
    */
}