package wine.parser;

import wine.parser.model.WineProduct;
import wine.parser.parsers.MaudauParser;
import wine.parser.parsers.WineParser;
import wine.parser.parsers.ZakazParser;
import wine.parser.parsers.OkwineParser;  // <-- селектори НЕ перевірені на реальному HTML, вимкнено
import wine.parser.parsers.SilpoParser;   // <-- slug/поля НЕ перевірені на реальному API, вимкнено
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class Main {

    private static final double SIMILARITY_THRESHOLD = 0.60;

    public static void main(String[] args) {
        System.out.println("=== Запуск парсера вина ===");

        List<WineProduct> existingCache = loadExistingWines("top_wines.json");
        System.out.println(">>> Завантажено з кешу (top_wines.json): " + existingCache.size() + " позицій.");

        List<WineParser> parsers = Arrays.asList(
                //new MaudauParser(),
                //new ZakazParser()
                //new OkwineParser()  // підключити після перевірки реальних CSS-селекторів
                new SilpoParser()    // підключити після перевірки реального API-запиту (slug, поля)
        );

        List<WineProduct> collectedWines = new ArrayList<>(existingCache);

        for (WineParser parser : parsers) {
            String parserName = parser.getClass().getSimpleName();
            System.out.println("\n>>> Збираємо дані через: " + parserName + "...");

            List<WineProduct> parsedWines = parser.parse(existingCache);

            for (WineProduct wine : parsedWines) {
                mergeOrAdd(collectedWines, wine);
            }
        }

        System.out.println(">>> Зібрано унікальних позицій: " + collectedWines.size());

        System.out.println(">>> Зберігаємо у файл top_wines.json...");
        saveJsonFile(collectedWines, "top_wines.json");
    }

    private static List<WineProduct> loadExistingWines(String fileName) {
        Gson gson = new GsonBuilder().create();
        Path path = Path.of(fileName);
        List<WineProduct> list = new ArrayList<>();
        if (Files.exists(path)) {
            try (java.io.Reader reader = Files.newBufferedReader(path)) {
                Type listType = new TypeToken<ArrayList<WineProduct>>(){}.getType();
                List<WineProduct> read = gson.fromJson(reader, listType);
                if (read != null) list.addAll(read);
            } catch (IOException e) {
                System.err.println("Помилка читання кешу: " + e.getMessage());
            }
        }
        return list;
    }

    private static void saveJsonFile(List<WineProduct> wines, String fileName) {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try (Writer writer = Files.newBufferedWriter(Path.of(fileName))) {
            gson.toJson(wines, writer);
            System.out.println("=== ГОТОВО! Збережено: " + wines.size() + " позицій ===");
        } catch (IOException e) {
            System.err.println("Помилка збереження файлу: " + e.getMessage());
        }
    }

    private static void mergeOrAdd(List<WineProduct> list, WineProduct newWine) {
        if (newWine.getCleanName() == null) return;

        WineProduct exactMatch = findExactMatch(list, newWine);
        if (exactMatch != null) {
            exactMatch.mergeFrom(newWine);
            return;
        }

        WineProduct fuzzyMatch = null;
        double highestScore = 0.0;

        for (WineProduct existing : list) {
            if (existing == null || existing.getCleanName() == null) continue;
            if (sameSource(existing, newWine)) continue; // не матчимо товари одного й того ж джерела між собою

            if (existing.getVolume() != null && newWine.getVolume() != null
                    && !existing.getVolume().equals(newWine.getVolume())) {
                continue;
            }

            double score = calculateSimilarity(existing.getCleanName(), newWine.getCleanName());
            if (score > highestScore) {
                highestScore = score;
                fuzzyMatch = existing;
            }
        }

        if (fuzzyMatch != null && highestScore >= SIMILARITY_THRESHOLD) {
            fuzzyMatch.mergeFrom(newWine);
        } else {
            list.add(newWine);
        }
    }

    /**
     * Точний збіг -- спочатку EAN (найнадійніший, спільний фізичний ідентифікатор товару),
     * потім посилання конкретного джерела.
     */
    private static WineProduct findExactMatch(List<WineProduct> list, WineProduct newWine) {
        for (WineProduct existing : list) {
            if (existing == null) continue;

            if (newWine.getEan() != null && newWine.getEan().equals(existing.getEan())) {
                return existing;
            }
            if (newWine.getMaudauUrl() != null && newWine.getMaudauUrl().equals(existing.getMaudauUrl())) {
                return existing;
            }
            if (newWine.getSilpoUrl() != null && newWine.getSilpoUrl().equals(existing.getSilpoUrl())) {
                return existing;
            }
            if (newWine.getOkwineUrl() != null && newWine.getOkwineUrl().equals(existing.getOkwineUrl())) {
                return existing;
            }
            if (newWine.getProductUrl() != null && newWine.getProductUrl().equals(existing.getProductUrl())) {
                return existing;
            }
        }
        return null;
    }

    /**
     * true, якщо обидва об'єкти вже мають ціну з ОДНОГО й того ж джерела --
     * тоді це два різні товари цього джерела, а не той самий товар з різних сайтів.
     */
    private static boolean sameSource(WineProduct a, WineProduct b) {
        boolean bothMaudau = a.getMaudauPrice() != null && b.getMaudauPrice() != null;
        boolean bothZakaz = a.getPrice() != null && b.getPrice() != null;
        boolean bothSilpo = a.getSilpoPrice() != null && b.getSilpoPrice() != null;
        boolean bothOkwine = a.getOkwinePrice() != null && b.getOkwinePrice() != null;
        return bothMaudau || bothZakaz || bothSilpo || bothOkwine;
    }

    private static double calculateSimilarity(String name1, String name2) {
        String clean1 = removeGarbageWords(name1);
        String clean2 = removeGarbageWords(name2);

        Set<String> words1 = new HashSet<>(Arrays.asList(clean1.split("\\s+")));
        Set<String> words2 = new HashSet<>(Arrays.asList(clean2.split("\\s+")));

        if (words1.isEmpty() || words2.isEmpty()) return 0.0;

        int intersection = 0;
        for (String w : words1) {
            if (words2.contains(w)) intersection++;
        }

        int union = words1.size() + words2.size() - intersection;
        return (double) intersection / union;
    }

    private static String removeGarbageWords(String name) {
        if (name == null) return "";
        return name.toLowerCase()
                .replaceAll("вино", "")
                .replaceAll("червоне", "")
                .replaceAll("біле", "")
                .replaceAll("рожеве", "")
                .replaceAll("сухе", "")
                .replaceAll("напівсухе", "")
                .replaceAll("напівсолодке", "")
                .replaceAll("солодке", "")
                .replaceAll("ігристе", "")
                .replaceAll("\\d+[.,]?\\d*\\s*(ml|мл|l|л|%|°)", "")
                .replaceAll("[^a-zа-яіїєґ0-9]", " ")
                .trim();
    }
}