package beer.parser;

import beer.parser.model.BeerProduct;
import beer.parser.parsers.BeerParser;
import beer.parser.parsers.FlaskerBeerParser;
import beer.parser.parsers.SilpoBeerParser;
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

    // Поріг схожості (наприклад, 60% однакових значущих слів)
    private static final double SIMILARITY_THRESHOLD = 0.60;

    public static void main(String[] args) {
        System.out.println("=== Запуск парсера крафтового пива ===");

        List<BeerParser> parsers = Arrays.asList(
                new SilpoBeerParser(),
                new FlaskerBeerParser()
        );

        List<BeerProduct> collectedBeers = new ArrayList<>();

        for (BeerParser parser : parsers) {
            String parserName = parser.getClass().getSimpleName();
            System.out.println(">>> Збираємо дані через: " + parserName + "...");

            List<BeerProduct> parsedBeers = parser.parse();
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
        updateJsonFile(topBeers, "top_beers.json");
    }

    private static void mergeOrAdd(List<BeerProduct> list, BeerProduct newBeer) {
        if (newBeer.getCleanName() == null) {
            return; // Пропускаємо невалідні товари
        }

        BeerProduct bestMatch = null;
        double highestScore = 0.0;

        for (BeerProduct existing : list) {
            // ЗАХИСТ ВІД ЗЛИТТЯ В МЕЖАХ ОДНОГО МАГАЗИНУ:
            // Якщо обидва товари з Сільпо АБО обидва з Фласкера — це різні SKU (пляшка/банка), не зливаємо!
            boolean bothFromSilpo = existing.getSilpoPrice() != null && newBeer.getSilpoPrice() != null;
            boolean bothFromFlasker = existing.getFlaskerPrice() != null && newBeer.getFlaskerPrice() != null;

            if (bothFromSilpo || bothFromFlasker) {
                continue;
            }

            // Рахуємо відсоток схожості очищених назв
            double score = calculateSimilarity(existing.getCleanName(), newBeer.getCleanName());
            if (score > highestScore) {
                highestScore = score;
                bestMatch = existing;
            }
        }

        // Якщо знайшли схожий товар і він проходить поріг
        if (bestMatch != null && highestScore >= SIMILARITY_THRESHOLD) {
            if (newBeer.getSilpoPrice() != null) {
                bestMatch.setSilpoPrice(newBeer.getSilpoPrice());
                bestMatch.setSilpoUrl(newBeer.getSilpoUrl());
            }
            if (newBeer.getSilpoRating() != null) {
                bestMatch.setSilpoRating(newBeer.getSilpoRating());
            }
            if (newBeer.getFlaskerPrice() != null) {
                bestMatch.setFlaskerPrice(newBeer.getFlaskerPrice());
                bestMatch.setFlaskerUrl(newBeer.getFlaskerUrl());
            }

            // Якщо у нового пива кращий або новий untappdRating - оновлюємо
            if (newBeer.getUntappdRating() != null) {
                if (bestMatch.getUntappdRating() == null || newBeer.getUntappdRating() > bestMatch.getUntappdRating()) {
                    bestMatch.setUntappdRating(newBeer.getUntappdRating());
                }
            }
        } else {
            // Якщо схожих немає — додаємо як нове
            list.add(newBeer);
        }
    }

    // --- БЛОК FUZZY MATCHING (НЕЧІТКИЙ ПОШУК) ---
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
        return (double) intersection / union; // Повертає значення від 0.0 до 1.0 (наприклад, 0.85)
    }

    private static String removeGarbageWords(String name) {
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
                // Видаляємо об'єми та градуси (наприклад: 330ml, 0.33л, 5%, 10°)
                .replaceAll("\\d+[.,]?\\d*\\s*(ml|мл|l|л|%|°)", "")
                .replaceAll("[^a-zа-яіїєґ0-9]", " ") // Залишаємо лише літери та цифри
                .trim();
    }
    // ---------------------------------------------

    private static void updateJsonFile(List<BeerProduct> newBeers, String fileName) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
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
            mergeOrAdd(allBeers, newBeer); // Тепер сюди теж застосовується нечіткий пошук!
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