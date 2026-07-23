package beer.parser;

import beer.parser.model.BeerProduct;
import beer.parser.parsers.BeerParser;
import beer.parser.parsers.FlaskerBeerParser;
import beer.parser.parsers.SilpoBeerParser;
import beer.parser.parsers.UntappdBeerParser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.io.Writer;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class Main {

    private static final double SIMILARITY_THRESHOLD = 0.60;

    public static void main(String[] args) {
        System.out.println("=== Запуск парсера крафтового пива ===");

        List<BeerProduct> existingCache = loadExistingBeers("top_beers.json");
        System.out.println(">>> Завантажено з кешу (top_beers.json): " + existingCache.size() + " позицій.");

        List<BeerParser> parsers = Arrays.asList(
                new UntappdBeerParser(),
                new SilpoBeerParser(),
                new FlaskerBeerParser()

        );

        List<BeerProduct> collectedBeers = new ArrayList<>(existingCache);

        for (BeerParser parser : parsers) {
            String parserName = parser.getClass().getSimpleName();
            System.out.println("\n>>> Збираємо дані через: " + parserName + "...");

            List<BeerProduct> parsedBeers = parser.parse(existingCache);

            for (BeerProduct beer : parsedBeers) {
                mergeOrAdd(collectedBeers, beer);
            }
        }

        System.out.println(">>> Зібрано унікальних позицій до фільтрації: " + collectedBeers.size());

        List<BeerProduct> topBeers = collectedBeers.stream()
                .filter(beer -> beer.getUntappdRating() != null && beer.getUntappdRating() >= 3.8)
                .collect(Collectors.toList());

        System.out.println(">>> Залишилося після фільтрації (рейтинг >= 3.8): " + topBeers.size());

        System.out.println(">>> Зберігаємо у файл top_beers.json...");
        saveJsonFile(topBeers, "top_beers.json");
    }

    private static List<BeerProduct> loadExistingBeers(String fileName) {
        Gson gson = new com.google.gson.GsonBuilder().create();
        Path path = Path.of(fileName);
        List<BeerProduct> list = new ArrayList<>();
        if (Files.exists(path)) {
            try (java.io.Reader reader = Files.newBufferedReader(path)) {
                Type listType = new com.google.gson.reflect.TypeToken<ArrayList<BeerProduct>>(){}.getType();
                List<BeerProduct> readBeers = gson.fromJson(reader, listType);
                if (readBeers != null) list.addAll(readBeers);
            } catch (IOException e) {
                System.err.println("Помилка читання кешу: " + e.getMessage());
            }
        }
        return list;
    }

    private static void saveJsonFile(List<BeerProduct> beers, String fileName) {
        Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try (Writer writer = Files.newBufferedWriter(Path.of(fileName))) {
            gson.toJson(beers, writer);
            System.out.println("=== ГОТОВО! Збережено: " + beers.size() + " позицій ===");
        } catch (IOException e) {
            System.err.println("Помилка збереження файлу: " + e.getMessage());
        }
    }

    private static void mergeOrAdd(List<BeerProduct> list, BeerProduct newBeer) {
        if (newBeer.getCleanName() == null) {
            return;
        }

        BeerProduct exactMatch = null;
        BeerProduct fuzzyMatch = null;
        double highestScore = 0.0;

        for (BeerProduct existing : list) {
            if (existing == null || existing.getCleanName() == null) {
                continue;
            }
            if (newBeer.getSilpoUrl() != null && newBeer.getSilpoUrl().equals(existing.getSilpoUrl())) {
                exactMatch = existing;
                break;
            }
            if (newBeer.getFlaskerUrl() != null && newBeer.getFlaskerUrl().equals(existing.getFlaskerUrl())) {
                exactMatch = existing;
                break;
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

            double score = calculateSimilarity(existing.getCleanName(), newBeer.getCleanName());
            if (score > highestScore) {
                highestScore = score;
                fuzzyMatch = existing;
            }
        }

        if (exactMatch != null) {
            exactMatch.mergeFrom(newBeer);
        } else if (fuzzyMatch != null && highestScore >= SIMILARITY_THRESHOLD) {
            fuzzyMatch.mergeFrom(newBeer);
        } else {
            list.add(newBeer);
        }
    }

    private static double calculateSimilarity(String name1, String name2) {
        String clean1 = removeGarbageWords(name1);
        String clean2 = removeGarbageWords(name2);

        Set<String> words1 = new HashSet<>(Arrays.asList(clean1.split("\\s+")));
        Set<String> words2 = new HashSet<>(Arrays.asList(clean2.split("\\s+")));

        if (words1.isEmpty() || words2.isEmpty()) return 0.0;

        int intersection = 0;
        for (String w : words1) {
            if (words2.contains(w)) {
                intersection++;
            }
        }

        int union = words1.size() + words2.size() - intersection;
        return (double) intersection / union;
    }

    private static String removeGarbageWords(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase()
                .replaceAll("пиво", "")
                .replaceAll("світле", "")
                .replaceAll("темне", "")
                .replaceAll("напівтемне", "")
                .replaceAll("нефільтроване", "")
                .replaceAll("фільтроване", "")
                .replaceAll("пастеризоване", "")
                .replaceAll("непастеризоване", "")
                .replaceAll("з/б", "")
                .replaceAll("розливне", "")
                .replaceAll("пляшка", "")
                .replaceAll("банка", "")
                .replaceAll("\\d+[.,]?\\d*\\s*(ml|мл|l|л|%|°)", "")
                .replaceAll("[^a-zа-яіїєґ0-9]", " ")
                .trim();
    }

    private static void updateJsonFile(List<BeerProduct> newBeers, String fileName) {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Path path = Path.of(fileName);
        List<BeerProduct> allBeers = new ArrayList<>();

        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                Type listType = new TypeToken<ArrayList<BeerProduct>>(){}.getType();
                List<BeerProduct> readBeers = gson.fromJson(reader, listType);
                if (readBeers != null) {
                    for (BeerProduct b : readBeers) {
                        if (b.getCleanName() != null) {
                            allBeers.add(b);
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("Помилка читання існуючого файлу: " + e.getMessage());
            }
        }

        int startSize = allBeers.size();
        for (BeerProduct newBeer : newBeers) {
            mergeOrAdd(allBeers, newBeer);
        }
        int addedCount = allBeers.size() - startSize;

        try (Writer writer = Files.newBufferedWriter(path)) {
            gson.toJson(allBeers, writer);
            System.out.println("=== ГОТОВО! Нових позицій додано: " + addedCount + ". Загалом у базі: " + allBeers.size() + " ===");
        } catch (IOException e) {
            System.err.println("Помилка збереження файлу: " + e.getMessage());
        }
    }
}