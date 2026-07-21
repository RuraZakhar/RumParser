package beer.parser;

import beer.parser.model.BeerProduct;
import beer.parser.parsers.BeerParser;
import beer.parser.parsers.FlaskerBeerParser;
import beer.parser.parsers.SilpoBeerParser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class Main {
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
        for (BeerProduct existing : list) {
            if (existing.equals(newBeer)) {
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
                return;
            }
        }
        list.add(newBeer);
    }

    private static void updateJsonFile(List<BeerProduct> newBeers, String fileName) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Path path = Path.of(fileName);
        List<BeerProduct> existingBeers = new ArrayList<>();

        if (Files.exists(path)) {
            try {
                String jsonContent = Files.readString(path);
                Type listType = new TypeToken<ArrayList<BeerProduct>>(){}.getType();
                List<BeerProduct> readBeers = gson.fromJson(jsonContent, listType);
                if (readBeers != null) {
                    existingBeers.addAll(readBeers);
                }
            } catch (IOException e) {
                System.err.println("Помилка читання існуючого файлу: " + e.getMessage());
            }
        }

        int addedCount = 0;
        for (BeerProduct newBeer : newBeers) {
            boolean found = false;
            for (BeerProduct existing : existingBeers) {
                if (existing.equals(newBeer)) {
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
                    found = true;
                    break;
                }
            }
            if (!found) {
                existingBeers.add(newBeer);
                addedCount++;
            }
        }

        try {
            Files.writeString(path, gson.toJson(existingBeers));
            System.out.println("=== ГОТОВО! Нових позицій додано: " + addedCount + ". Загалом у базі: " + existingBeers.size() + " ===");
        } catch (IOException e) {
            System.err.println("Помилка збереження файлу: " + e.getMessage());
        }
    }
}