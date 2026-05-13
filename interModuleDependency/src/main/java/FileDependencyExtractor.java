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
        // Basename -> ALLE Pfade mit diesem Basename (Multi-Map, keine
        // first-wins-Aufloesung mehr). In Repos mit pro-Prozess "main.h" gibt
        // es Dutzende Kollisionen; die alte Variante hat zufaellig EINE davon
        // pro Basename gewaehlt und damit ganze Closures verseucht.
        Map<String, List<String>> basenameToFiles = new HashMap<>();
        Set<String> allFilesNorm = new LinkedHashSet<>();   // normalisierte Pfade
        Map<String, String> normToOriginal = new HashMap<>();
        for (String f : nodeToFile.values()) {
            if (PSEUDO_FILES.contains(f)) {
                continue;
            }
            String fn = f.replace('\\', '/');
            if (normToOriginal.putIfAbsent(fn, f) != null) {
                continue;
            }
            allFilesNorm.add(fn);
            basenameToFiles.computeIfAbsent(basenameOf(fn), k -> new ArrayList<>()).add(fn);
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
            if (included.isEmpty()) {
                continue;
            }
            String srcFile = fileOf(imp.id);
            if (srcFile == null) {
                continue;
            }

            String resolvedNorm = resolveIncludePath(srcFile, included,
                    basenameToFiles, allFilesNorm);
            if (resolvedNorm == null) {
                continue;       // System-Header wie stdio.h: nichts im Repo.
            }
            String resolvedTgt = normToOriginal.getOrDefault(resolvedNorm, resolvedNorm);
            addDep(srcFile, resolvedTgt, 1);
        }
    }

    /**
     * Aufloesung eines {@code #include}-Targets. C/C++-konform wird das
     * Verzeichnis der inkludierenden Datei als Suchbasis genutzt; bei
     * mehreren Kandidaten gewinnt der mit dem laengsten gemeinsamen
     * Pfad-Praefix zur Quelle. Path-Separatoren sind transparent ('/' vs '\').
     *
     * @return normalisierter ('/' getrennter) Treffer-Pfad oder {@code null}.
     */
    private static String resolveIncludePath(String srcFile, String included,
                                             Map<String, List<String>> basenameToFiles,
                                             Set<String> allFilesNorm) {
        String inc = included.replace('\\', '/');
        List<String> candidates;
        if (inc.contains("/")) {
            // Pfadfragment im Include selbst -> Suffix-Match.
            String suffix = inc.startsWith("/") ? inc : "/" + inc;
            candidates = new ArrayList<>();
            for (String f : allFilesNorm) {
                if (f.endsWith(suffix) || f.equals(inc)) {
                    candidates.add(f);
                }
            }
        } else {
            candidates = basenameToFiles.getOrDefault(inc, List.of());
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        // Mehrdeutig: nimm den Kandidaten, dessen Verzeichnis am laengsten
        // mit dem Verzeichnis der Quelle uebereinstimmt.
        String[] srcParts = dirOf(srcFile.replace('\\', '/')).split("/");
        String best = null;
        int bestScore = -1;
        for (String c : candidates) {
            String[] cParts = dirOf(c).split("/");
            int n = Math.min(srcParts.length, cParts.length);
            int score = 0;
            for (int i = 0; i < n; i++) {
                if (!srcParts[i].equals(cParts[i])) {
                    break;
                }
                score++;
            }
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    private static String basenameOf(String pathNorm) {
        int slash = pathNorm.lastIndexOf('/');
        return slash < 0 ? pathNorm : pathNorm.substring(slash + 1);
    }

    private static String dirOf(String pathNorm) {
        int slash = pathNorm.lastIndexOf('/');
        return slash < 0 ? "" : pathNorm.substring(0, slash);
    }

    // ---------- Root-Aufloesung + transitive Huelle ----------

    /**
     * Findet alle Dateien, die zu einem benutzerseitig angegebenen "Wurzel"-Namen
     * gehoeren. Match-Reihenfolge (erste nichtleere Stufe gewinnt):
     * <ol>
     *   <li><b>Exakter Pfad</b> einer FILE im CPG
     *       (z.B. {@code "coid00t/main.cpp"} wenn Joern genau diesen Pfad
     *       gespeichert hat).</li>
     *   <li><b>Pfad-Suffix</b>: wenn {@code rootName} mindestens einen
     *       Separator ({@code /} oder {@code \}) enthaelt, matcht jeder
     *       FILE-Pfad, der mit {@code /<rootName>} endet
     *       (z.B. {@code "coid00t/main.cpp"} matcht
     *       {@code "C:\repo\coid00t\main.cpp"} ebenso wie
     *       {@code "src/coid00t/main.cpp"}). Separator-agnostisch.</li>
     *   <li><b>Basename</b>: reiner Dateiname
     *       (z.B. {@code "main.cpp"}) — Achtung, matcht ALLE main.cpp
     *       im Repo; verwende eine der Pfad-Stufen darueber, um zu
     *       disambiguieren.</li>
     *   <li>METHOD/TYPE_DECL/NAMESPACE_BLOCK mit
     *       {@code NAME == rootName} oder {@code FULL_NAME == rootName}
     *       (z.B. {@code "main"}, {@code "TConnectionPool.Commit"})
     *       — Datei wird via {@link #fileOf(long)} aufgeloest.</li>
     * </ol>
     * Mehrere Treffer auf gleicher Stufe sind erlaubt; alle deren Dateien
     * werden zurueckgegeben. Leere Menge bedeutet kein Match.
     */
    public Set<String> resolveRootFiles(String rootName) {
        Set<String> hits = new LinkedHashSet<>();

        // Pfade einheitlich auf '/' normalisieren, damit Windows- und
        // Unix-Stil-Separatoren transparent funktionieren.
        String wanted = rootName.replace('\\', '/');
        boolean hasSeparator = wanted.contains("/");
        String suffix = wanted.startsWith("/") ? wanted : "/" + wanted;

        Set<String> allFiles = new HashSet<>(nodeToFile.values());

        // Stufe 1: exakter Pfad-Match (normalisiert).
        for (String f : allFiles) {
            if (PSEUDO_FILES.contains(f)) {
                continue;
            }
            if (f.replace('\\', '/').equals(wanted)) {
                hits.add(f);
            }
        }
        if (!hits.isEmpty()) {
            return hits;
        }

        // Stufe 2: Pfad-Suffix-Match (nur wenn der User ueberhaupt einen
        // Pfad-Fragment angegeben hat — sonst waere das aequivalent zur
        // Basename-Stufe).
        if (hasSeparator) {
            for (String f : allFiles) {
                if (PSEUDO_FILES.contains(f)) {
                    continue;
                }
                String fn = f.replace('\\', '/');
                if (fn.endsWith(suffix)) {
                    hits.add(f);
                }
            }
            if (!hits.isEmpty()) {
                return hits;
            }
        }

        // Stufe 3: Basename.
        for (String f : allFiles) {
            if (PSEUDO_FILES.contains(f)) {
                continue;
            }
            String bn = Path.of(f.replace('\\', '/')).getFileName().toString();
            if (bn.equals(wanted)) {
                hits.add(f);
            }
        }
        if (!hits.isEmpty()) {
            return hits;
        }

        // Stufe 4: NAME/FULL_NAME auf Definitions-Knoten matchen.
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

    private static final String[] HEADER_EXTS = {".h", ".hpp", ".hxx"};
    private static final String[] SOURCE_EXTS = {".cpp", ".cxx", ".cc", ".c"};

    /**
     * Reduziert {@link #fileDeps}/{@link #fileDepWeights} auf die Dateien, die
     * von {@code roots} aus per transitiver Huelle ueber "haengt-ab"-Kanten
     * erreichbar sind. Roots selbst sind immer im Ergebnis enthalten.
     * <p>
     * Zusaetzlich wird waehrend der BFS ein <b>Header/Source-Auto-Pairing</b>
     * durchgefuehrt: fuer jedes besuchte {@code X.h} (bzw. {@code .hpp/.hxx})
     * wird die korrespondierende {@code X.cpp} (bzw. {@code .cxx/.cc/.c}) am
     * gleichen Pfad zur Closure hinzugefuegt, falls sie im CPG existiert —
     * und symmetrisch fuer {@code .cpp -> .h}. Damit wandert die Linker-Pair-
     * Beziehung "Implementation gehoert zu Deklaration" in die Closure, die
     * Joern selbst nicht explizit als Edge anbietet. Die eigenen Abhaengigkeiten
     * des frisch hinzugefuegten Files werden anschliessend ebenfalls in der
     * BFS expandiert.
     *
     * @return Menge der erreichbaren Dateien (Roots inklusive)
     */
    public Set<String> restrictToReachable(Set<String> roots) {
        // Lookup: normalisierter Pfad -> Original-Pfad-im-CPG.
        Map<String, String> normToOriginal = new HashMap<>();
        for (String f : nodeToFile.values()) {
            if (PSEUDO_FILES.contains(f)) {
                continue;
            }
            normToOriginal.putIfAbsent(f.replace('\\', '/'), f);
        }

        Set<String> reachable = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            String f = stack.pop();
            if (!reachable.add(f)) {
                continue;
            }
            // 1) Echte Abhaengigkeiten aus dem CPG.
            Set<String> deps = fileDeps.get(f);
            if (deps != null) {
                for (String d : deps) {
                    if (!reachable.contains(d)) {
                        stack.push(d);
                    }
                }
            }
            // 2) Auto-Pair: passende .h/.cpp-Geschwister mitnehmen.
            for (String paired : pairCandidates(f, normToOriginal)) {
                if (!reachable.contains(paired)) {
                    stack.push(paired);
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

        // Alle erreichbaren Dateien als Knoten beibehalten, auch wenn sie
        // keine ausgehenden Abhaengigkeiten haben.
        for (String f : reachable) {
            fileDeps.putIfAbsent(f, new TreeSet<>());
        }
        return reachable;
    }

    /**
     * Sucht zu einem Pfad seine Header/Source-Geschwister (gleicher Stem, andere
     * Extension) und liefert die im CPG tatsaechlich vorhandenen Treffer
     * (mit ihren Original-Pfaden).
     */
    private static List<String> pairCandidates(String filePath,
                                               Map<String, String> normToOriginal) {
        String norm = filePath.replace('\\', '/');
        int dot = norm.lastIndexOf('.');
        if (dot < 0) {
            return List.of();
        }
        String stem = norm.substring(0, dot);
        String ext = norm.substring(dot).toLowerCase();
        String[] targetExts;
        if (Arrays.asList(HEADER_EXTS).contains(ext)) {
            targetExts = SOURCE_EXTS;
        } else if (Arrays.asList(SOURCE_EXTS).contains(ext)) {
            targetExts = HEADER_EXTS;
        } else {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String te : targetExts) {
            String candidate = stem + te;
            String hit = normToOriginal.get(candidate);
            if (hit != null) {
                result.add(hit);
            }
        }
        return result;
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
     * <p>
     * Erweiterung gegenueber dem Standard-Kahn: nach Ausgabe eines Headers
     * wird geprueft, ob seine gepaarte {@code .cpp} bereits "ready" ist
     * (alle deren Deps schon emittiert). Wenn ja, wird sie an den Anfang
     * der Ready-Queue gezogen und direkt anschliessend ausgegeben — damit
     * stehen .h und .cpp im Migrationsplan moeglichst nebeneinander. Falls
     * die .cpp noch weitere offene Deps hat, bleibt sie regulaer eingereiht
     * (Topologie bricht das Pairing).
     * <p>
     * Zyklen werden separat hinten angefuegt und auf stderr gemeldet.
     */
    public List<String> topologicalOrder() {
        Set<String> allFiles = new TreeSet<>();
        allFiles.addAll(fileDeps.keySet());
        fileDeps.values().forEach(allFiles::addAll);

        // Pairing-Index: jeder Header -> seine zugehoerige Source-Datei
        // (sofern beide in der Closure sind).
        Map<String, String> headerToSource = new HashMap<>();
        Map<String, Map<String, String>> stemToExts = new HashMap<>();
        for (String f : allFiles) {
            String norm = f.replace('\\', '/');
            int dot = norm.lastIndexOf('.');
            int slash = norm.lastIndexOf('/');
            if (dot < 0 || dot < slash) {
                continue;
            }
            String stem = norm.substring(0, dot);
            String ext = norm.substring(dot).toLowerCase();
            stemToExts.computeIfAbsent(stem, k -> new HashMap<>()).put(ext, f);
        }
        for (var e : stemToExts.entrySet()) {
            Map<String, String> exts = e.getValue();
            String header = null, source = null;
            for (String h : HEADER_EXTS) {
                if (exts.containsKey(h)) { header = exts.get(h); break; }
            }
            for (String s : SOURCE_EXTS) {
                if (exts.containsKey(s)) { source = exts.get(s); break; }
            }
            if (header != null && source != null) {
                headerToSource.put(header, source);
            }
        }

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
            String f = ready.pollFirst();
            order.add(f);
            for (String dependent : revAdj.getOrDefault(f, List.of())) {
                int rem = openDeps.merge(dependent, -1, Integer::sum);
                if (rem == 0) {
                    ready.addLast(dependent);
                }
            }
            // Pairing-Pull: ist f ein Header mit gepaarter, schon-readyer .cpp?
            // Dann sie als naechstes ausgeben — sie war womoeglich gerade durch
            // diesen Schritt zur Ready geworden oder wartete bereits.
            String paired = headerToSource.get(f);
            if (paired != null && ready.remove(paired)) {
                ready.addFirst(paired);
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