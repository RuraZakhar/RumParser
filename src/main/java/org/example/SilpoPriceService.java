package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class SilpoPriceService {

    private static final String BASE_PRODUCT_URL = "https://silpo.ua/product/";

    public void matchPrices(Set<RumProduct> rums) {
        System.out.println("\n[3/3] Starting Bulk Silpo Price Matching...");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .build();

        List<SilpoProductDTO> silpoCatalog = fetchAllSilpoRums(client);
        System.out.println("Loaded " + silpoCatalog.size() + " products from Silpo catalog into memory.");

        if (silpoCatalog.isEmpty()) {
            System.out.println("Silpo catalog is empty. Skipping matching.");
            return;
        }

        int matchedCount = 0;

        for (RumProduct rum : rums) {
            if (rum.getName() == null || rum.getName().isEmpty()) continue;

            SilpoProductDTO bestMatch = null;
            double bestScore = 0.0;

            for (SilpoProductDTO silpoProd : silpoCatalog) {
                double score = calculateSimilarity(rum.getName(), silpoProd.title);

                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = silpoProd;
                }
            }

            if (bestMatch != null && bestScore > 0.82) {
                JsonObject matchData = new JsonObject();
                boolean inStock = bestMatch.price > 0.0;
                matchData.addProperty("silpo_title", bestMatch.title);
                matchData.addProperty("similarity_score", bestScore);
                matchData.addProperty("price", bestMatch.price);
                matchData.addProperty("in_stock", inStock);
                matchData.addProperty("url", bestMatch.url);

                rum.setSilpoMatch(matchData);
                matchedCount++;
                String priceDisplay = inStock ? String.format("%.2f грн", bestMatch.price) : "Немає в наявності";
                System.out.printf("MATCHED! (%s) | База: '%s' <---> Сільпо: '%s' (Ціна: %s)%n",
                        String.format("%.0f%%", bestScore * 100), rum.getName(), bestMatch.title, priceDisplay);
            }
        }

        System.out.println("\nFinished Silpo matching. Successfully matched " + matchedCount + " rums entirely from memory!");
    }

    private List<SilpoProductDTO> fetchAllSilpoRums(HttpClient client) {
        List<SilpoProductDTO> catalog = new ArrayList<>();
        int limit = 100;
        int offset = 0;
        boolean hasMore = true;

        System.out.println("Починаю послідовний збір Сільпо через API...");

        while (hasMore) {
            String apiUrl = "https://sf-ecom-api.silpo.ua/v1/uk/branches/00000000-0000-0000-0000-000000000000/products?limit=" + limit + "&offset=" + offset + "&deliveryType=DeliveryHome&category=rom-4468&includeChildCategories=true&sortBy=popularity&sortDirection=desc&inStock=false";

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Referer", "https://silpo.ua/")
                        .GET()
                        .build();

                System.out.println("➡️ Завантажую товари з " + offset + " по " + (offset + limit) + "...");
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonObject rootObj = JsonParser.parseString(response.body()).getAsJsonObject();
                    JsonArray items = rootObj.getAsJsonArray("items");

                    int fetchedSize = items.size();
                    System.out.println("   Отримано: " + fetchedSize + " шт.");

                    for (JsonElement element : items) {
                        JsonObject item = element.getAsJsonObject();

                        String title = getStringOrNull(item, "title");
                        Double price = getDoubleOrNull(item, "price");
                        String slug = getStringOrNull(item, "slug");
                        String url = (slug != null && !slug.isEmpty()) ? BASE_PRODUCT_URL + slug : "";

                        if (title != null && !title.isEmpty()) {
                            catalog.add(new SilpoProductDTO(title, price != null ? price : 0.0, url));
                        }
                    }

                    if (fetchedSize < limit) {
                        hasMore = false;
                    } else {
                        offset += limit;
                    }
                } else {
                    System.err.println("Помилка API Сільпо! Код: " + response.statusCode());
                    break;
                }
            } catch (Exception e) {
                System.err.println("Помилка з'єднання: " + e.getMessage());
                break;
            }
        }

        System.out.println("🏁 Загалом зібрано " + catalog.size() + " товарів з усіх сторінок Сільпо.");
        return catalog;
    }

    private static String getStringOrNull(JsonObject obj, String field) {
        if (obj.has(field) && !obj.get(field).isJsonNull()) {
            return obj.get(field).getAsString();
        }
        return null;
    }

    private static Double getDoubleOrNull(JsonObject obj, String field) {
        if (obj.has(field) && !obj.get(field).isJsonNull()) {
            return obj.get(field).getAsDouble();
        }
        return null;
    }

    private double calculateSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;

        String cleanS1 = s1.toLowerCase().replaceAll("[^\\p{L}\\p{N} ]", "").trim();
        String cleanS2 = s2.toLowerCase()
                .replaceAll("\\b\\d+[.,]?\\d*\\s*(л|мл|l|ml)\\b", "")
                .replaceAll("\\b(ром|напій|на|основі|в|коробці|подарунковий|набір|rum)\\b", "")
                .replaceAll("[^\\p{L}\\p{N} ]", "")
                .trim();

        if (cleanS1.isEmpty() || cleanS2.isEmpty()) return 0.0;
        if (cleanS1.equals(cleanS2)) return 1.0;

        String[] wordsFromDb = cleanS1.split("\\s+");
        String[] wordsFromSilpo = cleanS2.split("\\s+");

        int matchCount = 0;
        int validWordsCount = 0;

        for (String word : wordsFromDb) {
            if (word.length() <= 2 && !word.matches("\\d+")) continue;
            validWordsCount++;

            for (String silpoWord : wordsFromSilpo) {
                if (silpoWord.equals(word) || (silpoWord.length() > 4 && silpoWord.startsWith(word))) {
                    matchCount++;
                    break;
                }
            }
        }

        double wordMatchScore = validWordsCount > 0 ? (double) matchCount / validWordsCount : 0.0;

        int maxLength = Math.max(cleanS1.length(), cleanS2.length());
        int distance = computeLevenshteinDistance(cleanS1, cleanS2);
        double levenshteinScore = 1.0 - ((double) distance / maxLength);

        return Math.max(wordMatchScore, levenshteinScore);
    }

    private int computeLevenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) {
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) dp[i][j] = j;
                else if (j == 0) dp[i][j] = i;
                else {
                    dp[i][j] = Math.min(
                            Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                            dp[i - 1][j - 1] + (s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1)
                    );
                }
            }
        }
        return dp[s1.length()][s2.length()];
    }

    private static class SilpoProductDTO {
        String title;
        double price;
        String url;

        SilpoProductDTO(String title, double price, String url) {
            this.title = title;
            this.price = price;
            this.url = url;
        }
    }
}