package rum.parser.parsers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import rum.parser.model.RumProduct;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SilpoParser implements RumParser {

    private static final HttpClient client = HttpClient.newHttpClient();
    private static final String BASE_IMAGE_URL = "https://images.silpo.ua/products/1600x1600/webp/";
    private static final String BASE_PRODUCT_URL = "https://silpo.ua/product/";
    private static final String BASE_API_DETAILS_URL = "https://sf-ecom-api.silpo.ua/v1/uk/branches/00000000-0000-0000-0000-000000000000/products/";

    @Override
    public void parse(Set<RumProduct> rumSet) {
        System.out.println("\n[3/3] Starting Silpo Parser...");

        List<RumProduct> silpoRums = fetchAllSilpoRums();
        System.out.println(">>> Зібрано " + silpoRums.size() + " ромів з Сільпо. Починаю метчінг...");

        int matchedCount = 0;
        int newAddedCount = 0;

        for (RumProduct silpoRum : silpoRums) {
            RumProduct bestMatch = null;
            double bestScore = 0.0;

            for (RumProduct existingRum : rumSet) {
                double score = calculateSimilarity(existingRum.getName(), silpoRum.getName());
                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = existingRum;
                }
            }

            if (bestMatch != null && bestScore > 0.82) {
                bestMatch.setSilpoMatch(new RumProduct.SilpoMatch(
                        silpoRum.getName(),
                        bestScore,
                        silpoRum.getPrice(),
                        silpoRum.getPrice() != null && silpoRum.getPrice() > 0,
                        silpoRum.getProductUrl()
                ));

                if (!silpoRum.getRatings().isEmpty()) {
                    bestMatch.getRatings().addAll(silpoRum.getRatings());
                }

                if (bestMatch.getRegion() == null) bestMatch.setRegion(silpoRum.getRegion());
                if (bestMatch.getAbv() == null) bestMatch.setAbv(silpoRum.getAbv());
                if (bestMatch.getAge() == null) bestMatch.setAge(silpoRum.getAge());

                matchedCount++;
            } else {
                silpoRum.setSilpoMatch(new RumProduct.SilpoMatch(
                        silpoRum.getName(),
                        1.0,
                        silpoRum.getPrice(),
                        silpoRum.getPrice() != null && silpoRum.getPrice() > 0,
                        silpoRum.getProductUrl()
                ));
                rumSet.add(silpoRum);
                newAddedCount++;
            }
        }

        System.out.println(">>> Silpo Parser завершив роботу!");
        System.out.println("   Знайдено збігів: " + matchedCount);
        System.out.println("   Додано нових (тільки з Сільпо): " + newAddedCount);
    }

    private List<RumProduct> fetchAllSilpoRums() {
        List<RumProduct> silpoList = new ArrayList<>();
        int limit = 100;
        int offset = 0;
        boolean hasMore = true;

        try {
            while (hasMore) {
                String catalogUrl = "https://sf-ecom-api.silpo.ua/v1/uk/branches/00000000-0000-0000-0000-000000000000/products?limit=" + limit + "&offset=" + offset + "&deliveryType=DeliveryHome&category=rom-4468&includeChildCategories=true&sortBy=popularity&sortDirection=desc&inStock=false";
                String responseBody = sendGetRequest(catalogUrl);

                if (responseBody != null) {
                    JsonObject rootObj = JsonParser.parseString(responseBody).getAsJsonObject();
                    JsonArray items = rootObj.getAsJsonArray("items");
                    int fetchedSize = items.size();

                    for (JsonElement element : items) {
                        JsonObject item = element.getAsJsonObject();
                        RumProduct rum = new RumProduct();

                        rum.setName(getStringOrNull(item, "title"));
                        rum.setBrand(getStringOrNull(item, "brandTitle"));
                        rum.setVolumeWeight(getStringOrNull(item, "displayRatio"));
                        rum.setPrice(getDoubleOrNull(item, "price"));
                        rum.setCategory("rum");

                        Double rawRating = getDoubleOrNull(item, "guestProductRating");
                        if (rawRating != null) {
                            rum.getRatings().add(new RumProduct.Rating("Silpo", rawRating * 2.0));
                        }

                        String icon = getStringOrNull(item, "icon");
                        if (icon != null) rum.setImgUrl(BASE_IMAGE_URL + icon);

                        String slug = getStringOrNull(item, "slug");
                        if (slug != null && !slug.isEmpty()) {
                            rum.setProductUrl(BASE_PRODUCT_URL + slug);
                            fetchAndAddDetails(slug, rum); // Точковий запит за деталями
                            Thread.sleep(50); // Пауза, щоб не заблокували
                        }

                        silpoList.add(rum);
                    }

                    if (fetchedSize < limit) hasMore = false;
                    else offset += limit;
                } else {
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return silpoList;
    }

    private void fetchAndAddDetails(String slug, RumProduct rum) {
        String detailsUrl = BASE_API_DETAILS_URL + slug;
        String responseBody = sendGetRequest(detailsUrl);
        if (responseBody == null) return;

        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray groups = root.getAsJsonArray("attributeGroups");

            if (groups != null) {
                for (JsonElement groupEl : groups) {
                    JsonObject group = groupEl.getAsJsonObject();
                    if ("generalInfo".equals(getStringOrNull(group, "key"))) {
                        JsonArray attributes = group.getAsJsonArray("attributes");
                        for (JsonElement attrEl : attributes) {
                            JsonObject attr = attrEl.getAsJsonObject();
                            JsonObject attrKeyObj = attr.getAsJsonObject("attribute");
                            JsonObject valueObj = attr.getAsJsonObject("value");

                            if (attrKeyObj != null && valueObj != null) {
                                String key = getStringOrNull(attrKeyObj, "key");
                                String valueTitle = getStringOrNull(valueObj, "title");

                                if (valueTitle != null) {
                                    if ("country".equals(key)) {
                                        rum.setRegion(valueTitle);
                                    } else if ("alcoholcontent".equals(key)) {
                                        try {
                                            rum.setAbv(Double.parseDouble(valueTitle.replace("%", "").trim()));
                                        } catch (Exception ignored) {}
                                    } else if ("strokvytrymky".equals(key)) {
                                        Matcher m = Pattern.compile("\\d+").matcher(valueTitle);
                                        if (m.find()) {
                                            rum.setAge(Double.parseDouble(m.group()));
                                        }
                                    }
                                }
                            }
                        }
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private String sendGetRequest(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0")
                    .header("Referer", "https://silpo.ua/")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) return response.body();
        } catch (Exception ignored) {}
        return null;
    }

    private String getStringOrNull(JsonObject obj, String field) {
        if (obj.has(field) && !obj.get(field).isJsonNull()) return obj.get(field).getAsString();
        return null;
    }

    private Double getDoubleOrNull(JsonObject obj, String field) {
        if (obj.has(field) && !obj.get(field).isJsonNull()) return obj.get(field).getAsDouble();
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
}