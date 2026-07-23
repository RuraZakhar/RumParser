package beer.parser.parsers;

import beer.parser.model.BeerProduct;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SilpoBeerParser implements BeerParser {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String BASE_PRODUCT_URL = "https://silpo.ua/product/";
    private static final String API_PRODUCT_DETAILS_URL = "https://sf-ecom-api.silpo.ua/v1/uk/branches/00000000-0000-0000-0000-000000000000/products/";
    private static final String BASE_IMAGE_URL = "https://s7g10.scene7.com/is/image/silpo/";
    private static final String[] CATEGORIES = {
            "kraftove-pyvo-4506",
            "importne-pyvo-4505"
    };

    @Override
    public List<BeerProduct> parse(List<BeerProduct> existingCache) {
        List<BeerProduct> rawBeers = new ArrayList<>();
        List<BeerProduct> beersNeedsDetails = new ArrayList<>();
        List<String> slugsForDetails = new ArrayList<>();

        java.util.Map<String, BeerProduct> cacheMap = new java.util.HashMap<>();
        for (BeerProduct b : existingCache) {
            if (b.getSilpoUrl() != null) {
                cacheMap.put(b.getSilpoUrl(), b);
            }
        }

        for (String categorySlug : CATEGORIES) {
            int limit = 100;
            int offset = 0;
            boolean hasMore = true;

            try {
                while (hasMore) {
                    String url = "https://sf-ecom-api.silpo.ua/v1/uk/branches/00000000-0000-0000-0000-000000000000/products?" +
                            "limit=" + limit + "&offset=" + offset + "&deliveryType=DeliveryHome&category=" + categorySlug;

                    HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() == 200) {
                        JsonObject rootObj = JsonParser.parseString(response.body()).getAsJsonObject();
                        JsonArray items = rootObj.getAsJsonArray("items");
                        int fetchedSize = items.size();

                        if (items.isEmpty()) { hasMore = false; continue; }

                        for (JsonElement element : items) {
                            JsonObject item = element.getAsJsonObject();
                            BeerProduct beer = new BeerProduct();

                            beer.setName(getStringOrNull(item, "title"));
                            beer.setBrand(getStringOrNull(item, "brandTitle"));
                            if (beer.getName() != null) beer.setCleanName(beer.getName().toLowerCase());

                            Double guestRating = getDoubleOrNull(item, "guestProductRating");
                            if (guestRating != null) beer.setSilpoRating(guestRating);

                            Double untappd = getDoubleOrNull(item, "untappdRating");
                            if (untappd != null) beer.setUntappdRating(untappd);

                            beer.setSilpoPrice(getDoubleOrNull(item, "price"));

                            String icon = getStringOrNull(item, "icon");
                            if (icon != null) beer.setImgUrl(BASE_IMAGE_URL + icon);

                            String slug = getStringOrNull(item, "slug");
                            if (slug != null && !slug.isEmpty()) {
                                beer.setSilpoUrl(BASE_PRODUCT_URL + slug);

                                BeerProduct cachedBeer = cacheMap.get(beer.getSilpoUrl());
                                if (cachedBeer != null && cachedBeer.getAbv() != null) {
                                    beer.setAbv(cachedBeer.getAbv());
                                    beer.setCountry(cachedBeer.getCountry());
                                    beer.setPackaging(cachedBeer.getPackaging());
                                    beer.setVolume(cachedBeer.getVolume());
                                    System.out.println("   [Silpo] Знайдено в кеші (оновлено ціну): " + beer.getName());
                                } else {
                                    beersNeedsDetails.add(beer);
                                    slugsForDetails.add(slug);
                                }
                            }
                            rawBeers.add(beer);
                        }
                        if (fetchedSize < limit) hasMore = false;
                        else offset += limit;
                    } else break;
                }
            } catch (Exception e) { e.printStackTrace(); }
        }

        if (!beersNeedsDetails.isEmpty()) {
            System.out.println("   [Silpo] Нових позицій без кешу: " + beersNeedsDetails.size() + ". Запуск глибокого парсингу...");
            ExecutorService executor = Executors.newFixedThreadPool(15);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < beersNeedsDetails.size(); i++) {
                    BeerProduct beer = beersNeedsDetails.get(i);
                    String slug = slugsForDetails.get(i);
                    futures.add(CompletableFuture.runAsync(() -> fetchDetailsFromApi(beer, slug), executor));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } finally {
                executor.shutdown();
            }
        } else {
            System.out.println("   [Silpo] Усі позиції знайдені в кеші! Глибокий парсинг не потрібен.");
        }

        return new ArrayList<>(new LinkedHashSet<>(rawBeers));
    }

    private void fetchDetailsFromApi(BeerProduct beer, String slug) {
        String url = API_PRODUCT_DETAILS_URL + slug;
        int maxRetries = 3;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonObject rootObj = JsonParser.parseString(response.body()).getAsJsonObject();

                    String displayRatio = getStringOrNull(rootObj, "displayRatio");
                    if (displayRatio != null) {
                        extractVolumeFromString(beer, displayRatio);
                    }

                    if (beer.getVolume() == null && beer.getName() != null) {
                        extractVolumeFromString(beer, beer.getName());
                    }

                    JsonArray attributeGroups = rootObj.getAsJsonArray("attributeGroups");
                    if (attributeGroups != null) {
                        for (JsonElement groupEl : attributeGroups) {
                            JsonObject group = groupEl.getAsJsonObject();
                            if ("generalInfo".equals(getStringOrNull(group, "key"))) {
                                JsonArray attributes = group.getAsJsonArray("attributes");
                                if (attributes != null) {
                                    for (JsonElement attrEl : attributes) {
                                        JsonObject attrObj = attrEl.getAsJsonObject();
                                        JsonObject attrItem = attrObj.getAsJsonObject("attribute");
                                        JsonObject valueItem = attrObj.getAsJsonObject("value");

                                        if (attrItem != null && valueItem != null) {
                                            String attrId = getStringOrNull(attrItem, "id");

                                            if ("alcoholcontent".equals(attrId)) {
                                                if (valueItem.has("title") && !valueItem.get("title").isJsonNull()) {
                                                    try {
                                                        beer.setAbv(valueItem.get("title").getAsDouble());
                                                    } catch (Exception ignored) {}
                                                }
                                            } else if ("country".equals(attrId)) {
                                                beer.setCountry(getStringOrNull(valueItem, "title"));
                                            } else if ("typupakovky".equals(attrId)) {
                                                beer.setPackaging(getStringOrNull(valueItem, "title"));
                                            }
                                        }
                                    }
                                }
                                break;
                            }
                        }
                    }

                    String abvStr = beer.getAbv() != null ? beer.getAbv() + "%" : "-";
                    String countryStr = beer.getCountry() != null ? beer.getCountry() : "-";
                    String packStr = beer.getPackaging() != null ? beer.getPackaging() : "-";
                    String volStr = beer.getVolume() != null ? beer.getVolume() + "л" : "-";

                    System.out.println("   [Silpo] Оброблено: " + beer.getName() +
                            " (Об'єм: " + volStr + ", ABV: " + abvStr + ", Країна: " + countryStr + ", Упаковка: " + packStr + ")");

                    return;
                }
            } catch (java.net.http.HttpTimeoutException e) {
                if (attempt < maxRetries) {
                    System.out.println("   [Silpo] Таймаут для: " + slug + " (Спроба " + attempt + " з " + maxRetries + "). Пробуємо ще раз...");
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {}
                } else {
                    System.out.println("   [Silpo] Всі " + maxRetries + " спроби вичерпано (таймаут) для: " + slug);
                }
            } catch (Exception e) {
                System.out.println("   [Silpo] Помилка з'єднання: " + e.getMessage() + " для: " + slug);
                break;
            }
        }
    }

    private void extractVolumeFromString(BeerProduct beer, String text) {
        Matcher volMatcher = Pattern.compile("(?i)([0-9.,]+)\\s*(мл|ml|л|l)").matcher(text);
        if (volMatcher.find()) {
            try {
                double v = Double.parseDouble(volMatcher.group(1).replace(",", "."));
                if (volMatcher.group(2).toLowerCase().contains("м")) {
                    v = v / 1000.0;
                }
                beer.setVolume(v);
            } catch (NumberFormatException ignored) {}
        }
    }

    private String getStringOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private Double getDoubleOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsDouble();
        }
        return null;
    }
}