package org.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;

public class JsonDataExporter {

    /**
     * Exports any collection of data into a pretty-printed JSON file.
     *
     * @param data     The collection to export (e.g., Set or List).
     * @param fileName The output file path/name.
     */
    public boolean exportToJson(Collection<?> data, String fileName) {
        System.out.println("\nExporting data to file: " + fileName + "...");

        Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping() // Prevents escaping characters like '=', '&', etc.
                .create();

        Path targetFile = Path.of(fileName).toAbsolutePath();
        Path temporaryFile = targetFile.resolveSibling(targetFile.getFileName() + ".tmp");

        try {
            try (Writer writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                gson.toJson(data, writer);
            }
            moveIntoPlace(temporaryFile, targetFile);
            System.out.println("JSON file successfully generated! Total items exported: " + data.size());
            return true;
        } catch (IOException e) {
            System.err.println("Error writing to JSON file: " + e.getMessage());
            return false;
        }
    }

    private void moveIntoPlace(Path temporaryFile, Path targetFile) throws IOException {
        try {
            Files.move(
                    temporaryFile,
                    targetFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporaryFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
