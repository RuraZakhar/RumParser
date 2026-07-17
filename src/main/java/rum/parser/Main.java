package rum.parser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import rum.parser.model.RumProduct;
import rum.parser.parsers.RumHowlerParser;
import rum.parser.parsers.RumParser;
import rum.parser.parsers.RumRatingsParser;
import rum.parser.parsers.SilpoParser;
import rum.parser.util.JsonDataExporter;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class Main {

    private static final String FILTERED_OUTPUT_FILE = "top_rum_products.json";
    private static final double MIN_RATING = 7.0;

    public static void main(String[] args) {
        Set<RumProduct> rumSet = new LinkedHashSet<>();
        Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
        File file = new File(FILTERED_OUTPUT_FILE);

        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                Type type = new TypeToken<Set<RumProduct>>() {}.getType();
                Set<RumProduct> existingRums = gson.fromJson(reader, type);
                if (existingRums != null) {
                    rumSet.addAll(existingRums);
                    System.out.println("Loaded " + rumSet.size() + " existing top rums from file.");
                }
            } catch (Exception e) {
                System.out.println("Error reading existing file: " + e.getMessage());
            }
        }

        System.out.println("=== RUNNING RUM PARSER ===");

        List<RumParser> parsers = new ArrayList<>();
        parsers.add(new RumHowlerParser());
        parsers.add(new RumRatingsParser());
        parsers.add(new SilpoParser());

        for (RumParser parser : parsers) {
            parser.parse(rumSet);
        }

        System.out.println("\nTotal unique rums in memory: " + rumSet.size());
        rumSet.forEach(RumProduct::enrichDerivedFields);

        Set<RumProduct> ratedRumList = rumSet.stream()
                .filter(rum -> rum.getRatings().stream()
                        .anyMatch(r -> r.getRating() != null && r.getRating() >= MIN_RATING))
                .sorted(Comparator.comparingDouble(Main::highestRating).reversed())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        System.out.println("Top products (rating >= " + MIN_RATING + "): " + ratedRumList.size());

        JsonDataExporter exporter = new JsonDataExporter();
        exporter.exportToJson(ratedRumList, FILTERED_OUTPUT_FILE);

        System.out.println("=== PARSING PROCESS COMPLETED SUCCESSFULLY ===");
    }

    private static double highestRating(RumProduct rum) {
        return rum.getRatings().stream()
                .map(RumProduct.Rating::getRating)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0);
    }
}