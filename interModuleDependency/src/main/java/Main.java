import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    static String extractIdFromProcess(BufferedReader br, String processName) throws IOException {
        String line;
        Pattern idPattern = Pattern.compile("[0-9]+");
        Matcher matcher;
        int startIndex = 0,
                endIndex = 0;

        while ((line = br.readLine()) != null) {
            if (line.contains(processName)/* ToDo: && line.contains(weitere Spezifikationen)*/) {
                System.out.println(line);
                matcher =  idPattern.matcher(line);
                if (matcher.find()) {
                    startIndex = matcher.start();
                    endIndex = matcher.end();

                    return line.substring(startIndex, endIndex);
                }
            }
        }
        throw new NoSuchElementException(processName + " not found");
    }

    public static void main(String[] args) throws FileNotFoundException {

        // check args for 1) process-name to build dependence graph on and 2) path to joern-graph-file
        if (args[0].contains("help")) {
            System.out.println("implement help-page"); // ToDo: implement help-page
            return;
        } else if (args.length != 2) {
            System.err.println("Usage: java Main <root-process-name> <absolute-path-to-joern-graph-file>\nFor help, "
                    + "type java Main help");
            System.exit(1);
        }

        String pathToFile = args[1];
        String processName = args[0];

        try (BufferedReader br = new BufferedReader(new FileReader(pathToFile))) {
            //br.mark(Integer.MAX_VALUE); // ToDo: i need another way to go back to the start position, bcs this
            // files could get very large
            String processId = extractIdFromProcess(br, processName);
            System.out.println(processId);
        } catch (NoSuchElementException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            throw new FileNotFoundException("File not found! Given path \"" + pathToFile + "\"");
        }
    }
}

/*
    String line;
    while ((line = br.readLine()) != null) {
        System.out.println(line);
    }
*/