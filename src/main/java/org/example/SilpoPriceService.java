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

    private static final String FIRECRAWL_API_KEY = "fc-6643459e0664403694135d372d973aee";
    private static final String FIRECRAWL_SCRAPE_URL = "https://api.firecrawl.dev/v1/scrape";
    private static final String SILPO_RUM_CATEGORY_URL = "https://silpo.ua/category/rom-4468";

    public void matchPrices(Set<RumProduct> rums) {
        System.out.println("\n[3/3] Starting Bulk Silpo Price Matching...");

        if (FIRECRAWL_API_KEY == null || FIRECRAWL_API_KEY.isEmpty()) {
            System.err.println("ERROR: FIRECRAWL_API_KEY is not set! Skipping price matching.");
            return;
        }

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
        int maxPages = 9;

        System.out.println("Починаю послідовний збір Сільпо...");

        for (int page = 1; page <= maxPages; page++) {
            System.out.println("➡️ Завантажую сторінку " + page + " з " + maxPages + "...");

            String currentUrl = SILPO_RUM_CATEGORY_URL + (page > 1 ? "?page=" + page : "");

            List<SilpoProductDTO> pageItems = fetchSinglePageWithRetry(client, currentUrl);

            if (!pageItems.isEmpty()) {
                catalog.addAll(pageItems);
                System.out.println("Сторінка " + page + " готова (" + pageItems.size() + " товарів).");
            } else {
                System.out.println("Сторінка " + page + " повернула 0 товарів.");
            }

            if (page < maxPages) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        System.out.println("🏁 Загалом зібрано " + catalog.size() + " товарів з усіх сторінок Сільпо.");
        return catalog;
    }

    private List<SilpoProductDTO> fetchSinglePageWithRetry(HttpClient client, String url) {
        List<SilpoProductDTO> items = new ArrayList<>();
        int maxRetries = 3;

        try {
            JsonObject jsonBody = new JsonObject();
            jsonBody.addProperty("url", url);
            jsonBody.addProperty("waitFor", 5000);

            JsonArray formatsArray = new JsonArray();
            formatsArray.add("extract");
            jsonBody.add("formats", formatsArray);

            JsonObject extractOptions = new JsonObject();
            extractOptions.addProperty("prompt", "Extract ALL products visible on this category page. For each item, extract its exact title, numerical price in UAH, and relative or absolute product URL.");

            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");

            JsonObject properties = new JsonObject();
            JsonObject productsList = new JsonObject();
            productsList.addProperty("type", "array");

            JsonObject schemaItems = new JsonObject();
            schemaItems.addProperty("type", "object");

            JsonObject itemProperties = new JsonObject();
            JsonObject titleProp = new JsonObject(); titleProp.addProperty("type", "string");
            JsonObject priceProp = new JsonObject(); priceProp.addProperty("type", "number");
            JsonObject urlProp = new JsonObject(); urlProp.addProperty("type", "string");

            itemProperties.add("title", titleProp);
            itemProperties.add("price", priceProp);
            itemProperties.add("url", urlProp);
            schemaItems.add("properties", itemProperties);

            productsList.add("items", schemaItems);
            properties.add("products", productsList);
            schema.add("properties", properties);

            extractOptions.add("schema", schema);
            jsonBody.add("extract", extractOptions);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(FIRECRAWL_SCRAPE_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + FIRECRAWL_API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody.toString()))
                    .build();

            for (int i = 0; i < maxRetries; i++) {
                try {
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() == 200) {
                        JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (responseJson.has("data") && responseJson.getAsJsonObject("data").has("extract")) {
                            JsonObject extractedData = responseJson.getAsJsonObject("data").getAsJsonObject("extract");
                            if (extractedData.has("products")) {
                                JsonArray productsArray = extractedData.getAsJsonArray("products");
                                for (JsonElement el : productsArray) {
                                    JsonObject pJson = el.getAsJsonObject();
                                    String title = pJson.has("title") && !pJson.get("title").isJsonNull() ? pJson.get("title").getAsString() : "";
                                    double price = pJson.has("price") && !pJson.get("price").isJsonNull() ? pJson.get("price").getAsDouble() : 0.0;
                                    String urlStr = pJson.has("url") && !pJson.get("url").isJsonNull() ? pJson.get("url").getAsString() : "";

                                    if (!title.isEmpty()) {
                                        if (!urlStr.startsWith("http") && !urlStr.isEmpty()) urlStr = "https://silpo.ua" + urlStr;
                                        items.add(new SilpoProductDTO(title, price, urlStr));
                                    }
                                }
                            }
                        }
                        return items;
                    }

                    System.out.println("⚠Спроба " + (i + 1) + " невдала для " + url + ", код: " + response.statusCode());
                    Thread.sleep(3000);
                } catch (Exception e) {
                    System.err.println("Помилка з'єднання: " + e.getMessage());
                    Thread.sleep(3000);
                }
            }
        } catch (Exception e) {
            System.err.println("Помилка формування запиту: " + e.getMessage());
        }

        return items;
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