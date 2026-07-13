package org.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;

public class JsonDataExporter {

    /**
     * Exports any collection of data into a pretty-printed JSON file.
     *
     * @param data     The collection to export (e.g., Set or List).
     * @param fileName The output file path/name.
     */
    public void exportToJson(Collection<?> data, String fileName) {
        System.out.println("\n[3/3] Exporting data to file: " + fileName + "...");

        Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping() // Prevents escaping characters like '=', '&', etc.
                .create();

        try (FileWriter writer = new FileWriter(fileName)) {
            gson.toJson(data, writer);
            System.out.println("JSON file successfully generated! Total items exported: " + data.size());
        } catch (IOException e) {
            System.err.println("Error writing to JSON file: " + e.getMessage());
        }
    }
}