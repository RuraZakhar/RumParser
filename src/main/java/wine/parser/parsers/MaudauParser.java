package wine.parser.parsers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import wine.parser.model.WineProduct;
import wine.parser.utils.JsonUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MaudauParser implements WineParser {

    private static final String BASE_URL = "https://backend.prod.maudau.click/v1/user/products";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    // Категорії Maudau, які вважаємо "вином". Додаси сюди ihrysti-vyna, vermuty тощо, коли треба.
    private static final String[] CATEGORY_SLUGS = { "vyno" };

    private static final long MIN_REQUEST_INTERVAL_MS = 500;
    private static final int MAX_RETRIES = 5;
    private static final int MAX_PAGES_SAFETY_LIMIT = 200;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final Object rateLimitLock = new Object();
    private volatile long lastRequestTime = 0;

    @Override
    public List<WineProduct> parse(List<WineProduct> existingCache) {
        System.out.println("[Maudau] Запуск збору...");

        // Дані по цьому джерелу вже повні одним запитом — окремого deep-scrape етапу, як у Silpo/Flasker, не потрібно.
        Map<Long, WineProduct> cacheMap = new HashMap<>();
        if (existingCache != null) {
            for (WineProduct w : existingCache) {
                if (w.getMaudauId() != null) {
                    cacheMap.put(w.getMaudauId(), w);
                }
            }
        }

        List<WineProduct> rawWines = new ArrayList<>();

        for (String categorySlug : CATEGORY_SLUGS) {
            System.out.println("   [Maudau] Категорія: " + categorySlug);
            int page = 1;

            while (page <= MAX_PAGES_SAFETY_LIMIT) {
                String url = BASE_URL + "?main_category_slug=" + categorySlug + "&page=" + page;
                String json = fetchJsonWithRetry(url);
                if (json == null) break;

                JsonArray items;
                try {
                    JsonElement root = JsonParser.parseString(json);
                    items = root.isJsonArray() ? root.getAsJsonArray()
                            : root.getAsJsonObject().getAsJsonArray("data");
                } catch (Exception e) {
                    System.err.println("   [Maudau] Помилка парсингу JSON на сторінці " + page + ": " + e.getMessage());
                    break;
                }

                if (items == null || items.isEmpty()) {
                    System.out.println("   [Maudau] Сторінка " + page + " порожня — зупиняємось.");
                    break;
                }

                System.out.println("   [Maudau] Сторінка " + page + ": " + items.size() + " позицій");

                for (JsonElement el : items) {
                    JsonObject item = el.getAsJsonObject();
                    WineProduct wine = mapToWineProduct(item);
                    wine.setLastScrapedAt(System.currentTimeMillis());
                    rawWines.add(wine);

                    String ratingStr = wine.getRating() != null ? String.valueOf(wine.getRating()) : "-";
                    System.out.println("      ✅ " + wine.getName() + " (★ " + ratingStr
                            + " | " + wine.getMaudauPrice() + " грн)");
                }

                page++;
            }
        }

        System.out.println("[Maudau] Збір завершено. Отримано " + rawWines.size() + " позицій.");
        return new ArrayList<>(new LinkedHashSet<>(rawWines));
    }

    private WineProduct mapToWineProduct(JsonObject item) {
        WineProduct wine = new WineProduct();

        String title = JsonUtils.getStringOrNull(item, "title");
        wine.setName(title);
        if (title != null) wine.setCleanName(title.toLowerCase());

        if (item.has("id") && !item.get("id").isJsonNull()) {
            wine.setMaudauId(item.get("id").getAsLong());
        }

        String slug = JsonUtils.getStringOrNull(item, "slug");
        if (slug != null) {
            wine.setMaudauUrl("https://maudau.com.ua/product/" + slug);
        }

        if (item.has("brand") && item.get("brand").isJsonObject()) {
            wine.setBrand(JsonUtils.getStringOrNull(item.getAsJsonObject("brand"), "title"));
        }
        if (item.has("country_of_origin") && item.get("country_of_origin").isJsonObject()) {
            wine.setCountry(JsonUtils.getStringOrNull(item.getAsJsonObject("country_of_origin"), "title"));
        }
        if (item.has("main_photo_sized_urls") && item.get("main_photo_sized_urls").isJsonObject()) {
            wine.setImgUrl(JsonUtils.getStringOrNull(item.getAsJsonObject("main_photo_sized_urls"), "xl"));
        }

        if (item.has("offer") && item.get("offer").isJsonObject()) {
            JsonObject offer = item.getAsJsonObject("offer");
            Double priceKopecks = JsonUtils.getDoubleOrNull(offer, "price");
            if (priceKopecks != null) {
                wine.setMaudauPrice(priceKopecks / 100.0);
            }
        }

        if (item.has("rating") && !item.get("rating").isJsonNull()) {
            try {
                wine.setRating(Double.parseDouble(item.get("rating").getAsString()));
            } catch (NumberFormatException ignored) {}
        }

        if (item.has("reviews_count") && !item.get("reviews_count").isJsonNull()) {
            wine.setReviewsCount(item.get("reviews_count").getAsInt());
        }

        // ABV і об'єм в API окремими полями не приходять — витягуємо з назви товару.
        if (title != null) {
            extractAbvAndVolume(wine, title);
        }

        return wine;
    }

    private void extractAbvAndVolume(WineProduct wine, String text) {
        Matcher abvMatcher = Pattern.compile("(?i)([0-9.,]+)\\s*%").matcher(text);
        if (abvMatcher.find()) {
            try {
                wine.setAbv(Double.parseDouble(abvMatcher.group(1).replace(",", ".")));
            } catch (NumberFormatException ignored) {}
        }

        Matcher volMatcher = Pattern.compile("(?i)([0-9.,]+)\\s*(мл|ml|л|l)\\b").matcher(text);
        if (volMatcher.find()) {
            try {
                double v = Double.parseDouble(volMatcher.group(1).replace(",", "."));
                if (volMatcher.group(2).toLowerCase().startsWith("м")) v = v / 1000.0;
                wine.setVolume(v);
            } catch (NumberFormatException ignored) {}
        }
    }

    private void throttle() {
        synchronized (rateLimitLock) {
            long wait = MIN_REQUEST_INTERVAL_MS - (System.currentTimeMillis() - lastRequestTime);
            if (wait > 0) {
                try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            lastRequestTime = System.currentTimeMillis();
        }
    }

    private String fetchJsonWithRetry(String url) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            throttle();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) return response.body();
                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    long backoff = (long) Math.pow(2, attempt - 1) * 1000L;
                    System.out.println("   [Maudau] HTTP " + response.statusCode() + " -- retry " + attempt + "/" + MAX_RETRIES);
                    Thread.sleep(backoff);
                    continue;
                }
                System.err.println("   [Maudau] HTTP " + response.statusCode() + " для " + url);
                return null;
            } catch (Exception e) {
                System.err.println("   [Maudau] Помилка запиту: " + e.getMessage());
                try { Thread.sleep(1000L * attempt); } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }
}