package org.example;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class Main {

    public static void main(String[] args) {
        Set<RumProduct> rawRumList = new LinkedHashSet<>();

        System.out.println("=== RUNNING RUM PARSER ===");

        List<RumParser> parsers = new ArrayList<>();
        // parsers.add(new RumHowlerParser());
        parsers.add(new RumRatingsParser());

        for (RumParser parser : parsers) {
            parser.parse(rawRumList);
        }

        System.out.println("\nTotal unique rums collected before filtering: " + rawRumList.size());

        Set<RumProduct> filteredRumList = rawRumList.stream()
                .filter(rum -> rum.getRatings().stream()
                        .anyMatch(r -> r.getRating() != null && r.getRating() > 7.0))
                .limit(500)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        System.out.println("Total rums after filtering (Rating > 7, Top 500 limit): " + filteredRumList.size());

        JsonDataExporter exporter = new JsonDataExporter();
        exporter.exportToJson(filteredRumList, "rum_products.json");

        System.out.println("=== PARSING PROCESS COMPLETED SUCCESSFULLY ===");
    }
}