package wine.parser.parsers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import wine.parser.model.WineProduct;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

public class ZakazParser implements WineParser {

    private static final String API_BASE_URL = "https://stores-api.zakaz.ua/stores/482778001/categories/wine-zaraz/products/";
    private static final String PROVIDER = "Zakaz-Zaraz";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final long DETAILS_TTL_MS = 3L * 24 * 60 * 60 * 1000; // 3 дні

    @Override
    public List<WineProduct> parse(List<WineProduct> existingCache) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        // Індексуємо кеш за EAN для швидкого пошуку
        Map<String, WineProduct> cacheByEan = new HashMap<>();
        for (WineProduct w : existingCache) {
            if (w.getEan() != null) {
                cacheByEan.put(w.getEan(), w);
            }
        }

        System.out.println("[Zakaz-Zaraz API] Початок збору товарів через REST API...");

        Map<String, WineProduct> resultMap = new LinkedHashMap<>();
        int page = 1;
        boolean keepGoing = true;
        int skippedFreshCount = 0;

        while (keepGoing) {
            String url = API_BASE_URL + "?page=" + page;
            System.out.println("  Отримання сторінки " + page + "...");

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .header("Accept-Language", "uk") // Назви та описи українською
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    System.err.println("  Помилка HTTP " + response.statusCode() + " на сторінці " + page);
                    break;
                }

                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonArray results = root.getAsJsonArray("results");

                if (results == null || results.isEmpty()) {
                    System.out.println("  Сторінка " + page + " порожня. Збір завершено.");
                    keepGoing = false;
                    break;
                }

                for (JsonElement element : results) {
                    JsonObject item = element.getAsJsonObject();
                    String ean = getStringOrNull(item, "ean");

                    if (ean == null || ean.isEmpty()) {
                        continue;
                    }

                    WineProduct cached = cacheByEan.get(ean);
                    boolean fresh = cached != null
                            && cached.getName() != null
                            && cached.getLastScrapedAt() != null
                            && (System.currentTimeMillis() - cached.getLastScrapedAt()) < DETAILS_TTL_MS;

                    if (fresh) {
                        skippedFreshCount++;
                        resultMap.put(ean, cached);
                        continue;
                    }

                    // Створюємо або оновлюємо об'єкт
                    WineProduct wine = (cached != null) ? cached : new WineProduct();
                    mapJsonToWineProduct(item, wine);
                    resultMap.put(ean, wine);
                }

                page++;
                Thread.sleep(200); // Невеличка пауза між сторінками

            } catch (Exception e) {
                System.err.println("  Помилка обробки сторінки " + page + ": " + e.getMessage());
                break;
            }
        }

        System.out.println("[Zakaz-Zaraz API] Пропущено свіжих із кешу: " + skippedFreshCount);
        System.out.println("[Zakaz-Zaraz API] Завершено. Всього зібрано товарів: " + resultMap.size());

        return new ArrayList<>(resultMap.values());
    }

    private void mapJsonToWineProduct(JsonObject item, WineProduct wine) {
        String url = getStringOrNull(item, "web_url");

        wine.setEan(getStringOrNull(item, "ean"));
        wine.setSku(getStringOrNull(item, "sku"));
        wine.setName(getStringOrNull(item, "title"));
        wine.setProductUrl(url);
        wine.addSourceUrl(PROVIDER, url);
        wine.setCountry(getStringOrNull(item, "country"));
        wine.setInStock(getBooleanOrNull(item, "in_stock"));
        wine.setIsAlcohol(getBooleanOrNull(item, "is_alcohol"));

        // Ціна з копійок переводиться в гривні
        Double rawPrice = getDoubleOrNull(item, "price");
        if (rawPrice != null) {
            wine.setPrice(rawPrice / 100.0);
        }

        Double volume = getDoubleOrNull(item, "volume");
        if (volume != null) {
            wine.setVolume(volume);
        }

        // Виробник / Бренд
        if (item.has("producer") && !item.get("producer").isJsonNull()) {
            JsonObject producer = item.getAsJsonObject("producer");
            wine.setBrand(getStringOrNull(producer, "trademark"));
        }

        // Фотографії
        if (item.has("img") && !item.get("img").isJsonNull()) {
            JsonObject img = item.getAsJsonObject("img");
            String imgUrl = getStringOrNull(img, "s350x350");
            if (imgUrl == null) imgUrl = getStringOrNull(img, "s1350x1350");
            wine.setImgUrl(imgUrl);
        }

        // Інгредієнти / Опис
        if (item.has("ingredients") && item.get("ingredients").isJsonArray()) {
            JsonArray ingredients = item.getAsJsonArray("ingredients");
            StringBuilder sb = new StringBuilder();
            for (JsonElement el : ingredients) {
                if (!el.isJsonNull()) {
                    sb.append(el.getAsString().replace("<br>", " ").trim()).append(" ");
                }
            }
            if (sb.length() > 0) {
                wine.setDescription(sb.toString().trim());
            }
        }

        wine.setLastScrapedAt(System.currentTimeMillis());
    }

    private String getStringOrNull(JsonObject obj, String key) {
        return (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsString() : null;
    }

    private Double getDoubleOrNull(JsonObject obj, String key) {
        return (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsDouble() : null;
    }

    private Boolean getBooleanOrNull(JsonObject obj, String key) {
        return (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsBoolean() : null;
    }
}