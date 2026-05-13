import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class Main {

    public static final String ANSI_RESET  = "[0m";
    public static final String ANSI_GREEN  = "[32m";
    public static final String ANSI_YELLOW = "[33m";
    public static final String ANSI_RED    = "[31m";

    private static void printHelp() {
        System.out.println(
                "Usage:\n" +
                "  java Main <export.dot> [--root <name>] [--root <name> ...] [--out <file.dot>]\n" +
                "\n" +
                "Argumente:\n" +
                "  <export.dot>     Pfad zur CPG-DOT-Datei aus Joern.\n" +
                "  --root <name>    Wurzelpunkt fuer die Dependency-Closure. Optional; mehrfach angebbar.\n" +
                "                   <name> wird gematcht gegen (in dieser Reihenfolge):\n" +
                "                     1) exakter Pfad einer FILE im CPG\n" +
                "                          z.B. coid00t/main.cpp\n" +
                "                     2) Pfad-Suffix (wenn <name> einen / oder \\ enthaelt)\n" +
                "                          z.B. --root coid00t/main.cpp matcht\n" +
                "                          src/coid00t/main.cpp ebenso wie C:\\repo\\coid00t\\main.cpp\n" +
                "                     3) Basename (Achtung: matcht ALLE Dateien mit dem Namen!\n" +
                "                          z.B. main.cpp matcht jede main.cpp im Repo — nutze\n" +
                "                          Stufe 2, um auf einen Prozess einzugrenzen.)\n" +
                "                     4) NAME/FULL_NAME einer METHOD/TYPE_DECL/NAMESPACE_BLOCK,\n" +
                "                          z.B. main oder TConnectionPool.Commit\n" +
                "                   Ohne --root wird der vollstaendige File-Dep-Graph ausgegeben.\n" +
                "  --out <file.dot> Ausgabepfad (Default: file-dependencies.dot).\n");
    }

    private static String requireValue(String[] args, int i, String flag) {
        if (i + 1 >= args.length) {
            System.err.println("Fehlender Wert fuer " + flag);
            System.exit(2);
        }
        return args[i + 1];
    }

    public static void main(String[] args) throws IOException {
        String inputPath = null;
        String outputPath = "file-dependencies.dot";
        List<String> roots = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "help":
                case "-h":
                case "--help":
                    printHelp();
                    return;
                case "--root":
                    roots.add(requireValue(args, i, "--root"));
                    i++;
                    break;
                case "--out":
                    outputPath = requireValue(args, i, "--out");
                    i++;
                    break;
                default:
                    if (inputPath == null) {
                        inputPath = a;
                    } else {
                        System.err.println("Unerwartetes Argument: " + a);
                        printHelp();
                        System.exit(2);
                    }
            }
        }

        if (inputPath == null) {
            System.err.println("Fehlt: Pfad zur Joern-Graph-Datei.");
            printHelp();
            System.exit(2);
        }

        Path pathToFile = Path.of(inputPath);
        Path pathOutputFile = Path.of(outputPath);

        FileDependencyExtractor fde = new FileDependencyExtractor();

        System.out.println("Parse " + pathToFile + " ...");
        fde.parse(pathToFile);
        System.out.printf(ANSI_GREEN + "Parsed %d nodes, %d edges%n" + ANSI_RESET,
                fde.getNodesCount(), fde.getEdgesCount());

        fde.resolveFiles();
        fde.aggregateCallDependencies();
        fde.aggregateIncludeDependencies();

        if (!roots.isEmpty()) {
            Set<String> rootFiles = new LinkedHashSet<>();
            for (String r : roots) {
                Set<String> resolved = fde.resolveRootFiles(r);
                if (resolved.isEmpty()) {
                    System.err.println(ANSI_YELLOW
                            + "WARNUNG: kein Match fuer --root \"" + r + "\""
                            + ANSI_RESET);
                } else {
                    System.out.printf(ANSI_GREEN + "Root \"%s\" -> %s%n" + ANSI_RESET,
                            r, resolved);
                    rootFiles.addAll(resolved);
                }
            }
            if (rootFiles.isEmpty()) {
                System.err.println(ANSI_RED
                        + "Kein Root konnte aufgeloest werden. Abbruch."
                        + ANSI_RESET);
                System.exit(3);
            }
            Set<String> reachable = fde.restrictToReachable(rootFiles);
            System.out.printf(ANSI_GREEN + "Closure: %d Datei(en) ab %d Root(s)%n" + ANSI_RESET,
                    reachable.size(), rootFiles.size());
        }

        fde.writeDot(pathOutputFile);
        System.out.printf(ANSI_GREEN + "DOT geschrieben nach: %s%n" + ANSI_RESET, pathOutputFile);

        System.out.println("Migrationsreihenfolge (Blaetter zuerst):");
        int i = 1;
        for (String f : fde.topologicalOrder()) {
            System.out.printf("  %3d. %s%n", i++, f);
        }
    }
}
