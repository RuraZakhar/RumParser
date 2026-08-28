package beer.parser;

import beer.parser.model.BeerProduct;
import beer.parser.parsers.BeerParser;
import beer.parser.parsers.FlaskerBeerParser;
import beer.parser.parsers.SilpoBeerParser;
import beer.parser.parsers.UntappdBeerParser;
import beer.parser.parsers.UntappdFileLoader;
import beer.parser.util.BeerNameMatcher;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import common.parser.util.JsonExporter;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class Main {

    private static final double SIMILARITY_THRESHOLD = 0.60;

    public static void main(String[] args) {
        System.out.println("Starting craft beer parser...");

        List<BeerProduct> existingCache = loadExistingBeers("top_beers.json");
        System.out.println("Loaded from cache (top_beers.json): " + existingCache.size() + " items.");

        List<BeerParser> parsers = Arrays.asList(
                //new UntappdBeerParser(),
                new UntappdFileLoader(),
                new SilpoBeerParser(),
                new FlaskerBeerParser()
        );

        List<BeerProduct> collectedBeers = new ArrayList<>(existingCache);

        for (BeerParser parser : parsers) {
            String parserName = parser.getClass().getSimpleName();
            System.out.println("\nCollecting data via: " + parserName + "...");

            List<BeerProduct> parsedBeers = parser.parse(existingCache);

            for (BeerProduct beer : parsedBeers) {
                mergeIntoCollection(collectedBeers, beer);
            }
        }

        System.out.println("Collected unique items before filtering: " + collectedBeers.size());

        List<BeerProduct> topBeers = collectedBeers.stream()
                .filter(beer -> beer.getUntappdRating() != null && beer.getUntappdRating() >= 3.8)
                .collect(Collectors.toList());

        System.out.println("Remaining after filtering (rating >= 3.8): " + topBeers.size());

        JsonExporter exporter = new JsonExporter();
        exporter.exportToJson(topBeers, "top_beers.json");
    }

    private static List<BeerProduct> loadExistingBeers(String fileName) {
        Gson gson = new GsonBuilder().create();
        Path path = Path.of(fileName);
        List<BeerProduct> list = new ArrayList<>();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                Type listType = new TypeToken<ArrayList<BeerProduct>>(){}.getType();
                List<BeerProduct> readBeers = gson.fromJson(reader, listType);
                if (readBeers != null) list.addAll(readBeers);
            } catch (IOException e) {
                System.err.println("Error reading cache: " + e.getMessage());
            }
        }
        return list;
    }

    // Error containment (parser-conventions.md §2) at the parser level lives in each
    // parser's own parse() -- see e.g. UntappdBeerParser wrapping each brewery's future.

    private static void mergeIntoCollection(List<BeerProduct> list, BeerProduct newBeer) {
        if (newBeer.getCleanName() == null) {
            return;
        }

        // Dedup contract (parser-conventions.md §5): exact match first, then fuzzy match.
        BeerProduct exactMatch = findExactMatch(list, newBeer);
        if (exactMatch != null) {
            exactMatch.mergeFrom(newBeer);
            return;
        }

        List<BeerProduct> fuzzyCandidates = new ArrayList<>();
        for (BeerProduct existing : list) {
            if (existing == null || existing.getCleanName() == null) {
                continue;
            }

            boolean bothFromSilpo = existing.getSilpoPrice() != null && newBeer.getSilpoPrice() != null;
            boolean bothFromFlasker = existing.getFlaskerPrice() != null && newBeer.getFlaskerPrice() != null;
            if (bothFromSilpo || bothFromFlasker) {
                continue;
            }

            if (existing.getVolume() != null && newBeer.getVolume() != null
                    && !existing.getVolume().equals(newBeer.getVolume())) {
                continue;
            }

            fuzzyCandidates.add(existing);
        }

        BeerProduct fuzzyMatch = BeerNameMatcher.findBestFuzzyMatch(newBeer, fuzzyCandidates, SIMILARITY_THRESHOLD);
        if (fuzzyMatch != null) {
            fuzzyMatch.mergeFrom(newBeer);
        } else {
            list.add(newBeer);
        }
    }

    private static BeerProduct findExactMatch(List<BeerProduct> list, BeerProduct newBeer) {
        for (BeerProduct existing : list) {
            if (existing == null || existing.getCleanName() == null) {
                continue;
            }
            if (newBeer.getSilpoUrl() != null && newBeer.getSilpoUrl().equals(existing.getSilpoUrl())) {
                return existing;
            }
            if (newBeer.getFlaskerUrl() != null && newBeer.getFlaskerUrl().equals(existing.getFlaskerUrl())) {
                return existing;
            }
        }
        return null;
    }
}
