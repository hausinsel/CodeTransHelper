import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_GREEN = "\u001B[32m";

    static String extractIdFromProcess(BufferedReader br, String processName) throws IOException {
        String line;
        Pattern idPattern = Pattern.compile("[0-9]+");
        Matcher matcher;
        int startIndex = 0,
                endIndex = 0;

        while ((line = br.readLine()) != null) {
            if (line.contains(processName)/* ToDo: && line.contains(weitere Spezifikationen)*/) {
                matcher =  idPattern.matcher(line);
                if (matcher.find()) {
                    startIndex = matcher.start();
                    endIndex = matcher.end();

                    return line.substring(startIndex, endIndex);
                } else {
                    throw new NoSuchElementException("No matching pattern found");
                }
            }
        }
        throw new NoSuchElementException(processName + " not found");
    }

    public static void main(String[] args) throws IOException {

        // check args for 1) process-name to build dependence graph on
        if (args.length > 0 && (args[0].equals("help") || args[0].equals("--help") || args[0].equals("-h") )) {
            System.out.println("implement help-page"); // ToDo: implement help-page
            return;
        } else if (args.length != 1) {
            System.err.println("Usage: java Main <absolute-path-to-joern-graph-file>\nFor help, "
                    + "type java Main help");
            System.exit(1);
        }

        Path pathToFile = Path.of(args[0]);
        Path pathOutputFile = Path.of("file-dependencies.dot");

        FileDependencyExtractor fde = new FileDependencyExtractor();

        fde.parse(pathToFile);

        System.out.printf(ANSI_GREEN + "Parsed %d nodes, %d edges%n" + ANSI_RESET, fde.getNodesCount(),
                fde.getEdgesCount());

        fde.resolveFiles();
        fde.aggregateCallDependencies();
        fde.aggregateIncludeDependencies();
        fde.writeDot(pathOutputFile);

        System.out.printf(ANSI_GREEN + "DOT geschrieben nach: %s\n" + ANSI_RESET, pathOutputFile);

    }
}