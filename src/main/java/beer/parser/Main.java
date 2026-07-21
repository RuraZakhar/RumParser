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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Main {
    public static void main(String[] args) {
        System.out.println("=== Запуск парсера крафтового пива ===");

        List<BeerParser> parsers = Arrays.asList(
                new SilpoBeerParser(),
                new FlaskerBeerParser()
        );

        Map<String, BeerProduct> collectedBeers = new HashMap<>();

        for (BeerParser parser : parsers) {
            String parserName = parser.getClass().getSimpleName();
            System.out.println(">>> Збираємо дані через: " + parserName + "...");

            List<BeerProduct> parsedBeers = parser.parse();
            for (BeerProduct beer : parsedBeers) {
                mergeOrAdd(collectedBeers, beer);
            }
        }

        System.out.println(">>> Зібрано унікальних позицій до фільтрації: " + collectedBeers.size());

        List<BeerProduct> topBeers = collectedBeers.values().stream()
                .filter(beer -> beer.getUntappdRating() != null && beer.getUntappdRating() >= 3.8)
                .collect(Collectors.toList());

        System.out.println(">>> Залишилося після фільтрації (рейтинг >= 3.8): " + topBeers.size());

        System.out.println(">>> Зберігаємо у файл top_beers.json...");
        updateJsonFile(topBeers, "top_beers.json");
    }

    private static void mergeOrAdd(Map<String, BeerProduct> map, BeerProduct newBeer) {
        if (newBeer.getCleanName() == null) {
            return; // Invalid product
        }
        BeerProduct existing = map.get(newBeer.getCleanName());
        if (existing != null) {
            if (newBeer.getSilpoPrice() != null) {
                existing.setSilpoPrice(newBeer.getSilpoPrice());
                existing.setSilpoUrl(newBeer.getSilpoUrl());
            }
            if (newBeer.getSilpoRating() != null) {
                existing.setSilpoRating(newBeer.getSilpoRating());
            }
            if (newBeer.getFlaskerPrice() != null) {
                existing.setFlaskerPrice(newBeer.getFlaskerPrice());
                existing.setFlaskerUrl(newBeer.getFlaskerUrl());
            }
        } else {
            map.put(newBeer.getCleanName(), newBeer);
        }
    }

    private static void updateJsonFile(List<BeerProduct> newBeers, String fileName) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Path path = Path.of(fileName);
        Map<String, BeerProduct> allBeers = new HashMap<>();

        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                Type listType = new TypeToken<ArrayList<BeerProduct>>(){}.getType();
                List<BeerProduct> readBeers = gson.fromJson(reader, listType);
                if (readBeers != null) {
                    for (BeerProduct b : readBeers) {
                        if (b.getCleanName() != null) {
                            allBeers.put(b.getCleanName(), b);
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
            gson.toJson(new ArrayList<>(allBeers.values()), writer);
            System.out.println("=== ГОТОВО! Нових позицій додано: " + addedCount + ". Загалом у базі: " + allBeers.size() + " ===");
        } catch (IOException e) {
            System.err.println("Помилка збереження файлу: " + e.getMessage());
        }
    }
}