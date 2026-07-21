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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FlaskerBeerParser implements BeerParser {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public List<BeerProduct> parse() {
        List<BeerProduct> rawBeers = new ArrayList<>();
        int perPage = 100;
        int page = 1;
        boolean hasMore = true;

        try {
            while (hasMore) {
                String url = "https://flasker.com.ua/wp-json/wc/store/v1/products?per_page=" + perPage + "&page=" + page;

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    break;
                }

                JsonArray items = JsonParser.parseString(response.body()).getAsJsonArray();

                if (items.isEmpty()) {
                    hasMore = false;
                    continue;
                }

                for (JsonElement element : items) {
                    JsonObject item = element.getAsJsonObject();
                    BeerProduct beer = new BeerProduct();

                    beer.setName(getStringOrNull(item, "name"));
                    if (beer.getName() != null) {
                        beer.setCleanName(beer.getName().toLowerCase());
                    }

                    beer.setFlaskerUrl(getStringOrNull(item, "permalink"));

                    if (item.has("prices") && !item.get("prices").isJsonNull()) {
                        JsonObject prices = item.getAsJsonObject("prices");
                        Double rawPrice = getDoubleOrNull(prices, "price");
                        if (rawPrice != null) {
                            beer.setFlaskerPrice(rawPrice);
                        }
                    }

                    if (item.has("images") && item.get("images").isJsonArray()) {
                        JsonArray images = item.getAsJsonArray("images");
                        if (!images.isEmpty()) {
                            JsonObject firstImage = images.get(0).getAsJsonObject();
                            beer.setImgUrl(getStringOrNull(firstImage, "src"));
                        }
                    }

                    if (item.has("attributes") && item.get("attributes").isJsonArray()) {
                        JsonArray attributes = item.getAsJsonArray("attributes");
                        for (JsonElement attrElement : attributes) {
                            JsonObject attr = attrElement.getAsJsonObject();
                            String attrName = getStringOrNull(attr, "name");
                            if (attrName != null && (attrName.toLowerCase().contains("броварня") || attrName.toLowerCase().contains("виробник"))) {
                                JsonArray terms = attr.getAsJsonArray("terms");
                                if (terms != null && !terms.isEmpty()) {
                                    beer.setBrand(getStringOrNull(terms.get(0).getAsJsonObject(), "name"));
                                }
                            }
                        }
                    }

                    rawBeers.add(beer);
                }
                page++;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("   [Flasker] Зібрано " + rawBeers.size() + " позицій. Запускаю потоки для пошуку рейтингів...");

        ExecutorService executor = Executors.newFixedThreadPool(15);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (BeerProduct beer : rawBeers) {
            String permalink = beer.getFlaskerUrl();
            if (permalink != null && !permalink.isEmpty()) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    Double rating = extractUntappdFromHtml(permalink);
                    if (rating != null) {
                        beer.setUntappdRating(rating);
                        System.out.println("   [Flasker] Знайдено рейтинг: " + rating + " для " + beer.getName());
                    }
                }, executor);

                futures.add(future);
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        executor.shutdown();

        List<BeerProduct> allBeers = new ArrayList<>();
        for (BeerProduct beer : rawBeers) {
            if (!allBeers.contains(beer)) {
                allBeers.add(beer);
            }
        }

        return allBeers;
    }

    private Double extractUntappdFromHtml(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String html = response.body();

            Pattern pattern = Pattern.compile("Untappd:[\\s\\S]*?<strong>\\s*([0-9.,]+)\\s*/");
            Matcher matcher = pattern.matcher(html);

            if (matcher.find()) {
                String ratingStr = matcher.group(1).replace(",", ".");
                return Double.parseDouble(ratingStr);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getStringOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private Double getDoubleOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try {
                return obj.get(key).getAsDouble();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}