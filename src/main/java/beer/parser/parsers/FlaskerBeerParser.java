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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import beer.parser.utils.JsonUtils;

public class FlaskerBeerParser implements BeerParser {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final List<String> KNOWN_COUNTRIES = Arrays.asList(
            "україна", "бельгія", "німеччина", "сша", "чехія", "британія",
            "польща", "нідерланди", "ірландія", "іспанія", "італія", "франція", "шотландія"
    );

    @Override
    public List<BeerProduct> parse() {
        List<BeerProduct> rawBeers = new ArrayList<>();
        int perPage = 100;
        int page = 1;
        boolean hasMore = true;

        System.out.println("   [Flasker] Збір бази через API...");

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

                    String rawName = JsonUtils.getStringOrNull(item, "name");
                    if (rawName != null) {
                        rawName = unescapeHtml(rawName);

                        Matcher volMatcher = Pattern.compile("(?i)([0-9.,]+)\\s*(мл|ml|л|l)\\b").matcher(rawName);
                        if (volMatcher.find()) {
                            try {
                                double v = Double.parseDouble(volMatcher.group(1).replace(",", "."));
                                String unit = volMatcher.group(2).toLowerCase();
                                if (unit.contains("м") || unit.contains("m")) {
                                    v = v / 1000.0;
                                }
                                beer.setVolume(v);
                                rawName = rawName.replace(volMatcher.group(0), "");
                            } catch (NumberFormatException ignored) {}
                        }

                        Matcher abvMatcher = Pattern.compile("(?i)([0-9.,]+)\\s*(%|°)").matcher(rawName);
                        if (abvMatcher.find()) {
                            try {
                                if (abvMatcher.group(2).equals("%")) {
                                    beer.setAbv(Double.parseDouble(abvMatcher.group(1).replace(",", ".")));
                                }
                                rawName = rawName.replace(abvMatcher.group(0), "");
                            } catch (NumberFormatException ignored) {}
                        }

                        rawName = rawName.replaceAll("(?i)\\s*-\\.?$", "");
                        rawName = rawName.replaceAll("(?i)\\[\\d{4}\\]", "");
                        rawName = rawName.replaceAll("\\s+", " ").trim();

                        beer.setName(rawName);
                        beer.setCleanName(rawName.toLowerCase());
                    }

                    beer.setFlaskerUrl(JsonUtils.getStringOrNull(item, "permalink"));

                    if (item.has("prices") && !item.get("prices").isJsonNull()) {
                        JsonObject prices = item.getAsJsonObject("prices");
                        Double rawPrice = JsonUtils.getDoubleOrNull(prices, "price");
                        if (rawPrice != null) {
                            beer.setFlaskerPrice(rawPrice);
                        }
                    }

                    if (item.has("images") && item.get("images").isJsonArray()) {
                        JsonArray images = item.getAsJsonArray("images");
                        if (!images.isEmpty()) {
                            JsonObject firstImage = images.get(0).getAsJsonObject();
                            beer.setImgUrl(JsonUtils.getStringOrNull(firstImage, "src"));
                        }
                    }

                    if (item.has("brands") && item.get("brands").isJsonArray()) {
                        JsonArray brands = item.getAsJsonArray("brands");
                        if (!brands.isEmpty()) {
                            beer.setBrand(unescapeHtml(JsonUtils.getStringOrNull(brands.get(0).getAsJsonObject(), "name")));
                        }
                    }

                    if (item.has("tags") && item.get("tags").isJsonArray()) {
                        JsonArray tags = item.getAsJsonArray("tags");
                        for (JsonElement tagEl : tags) {
                            String tagName = JsonUtils.getStringOrNull(tagEl.getAsJsonObject(), "name");
                            if (tagName != null && KNOWN_COUNTRIES.contains(tagName.toLowerCase())) {
                                beer.setCountry(tagName);
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

        System.out.println("   [Flasker] Зібрано " + rawBeers.size() + " позицій. Запуск пошуку IBU та Untappd...");

        ExecutorService executor = Executors.newFixedThreadPool(15);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        try {
            for (BeerProduct beer : rawBeers) {
                String url = beer.getFlaskerUrl();
                if (url != null && !url.isEmpty()) {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        fetchDetailsFromHtml(beer, url);
                    }, executor);
                    futures.add(future);
                }
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
        }

        Set<BeerProduct> uniqueBeers = new LinkedHashSet<>(rawBeers);
        return new ArrayList<>(uniqueBeers);
    }

    private void fetchDetailsFromHtml(BeerProduct beer, String url) {
        int maxRetries = 3;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                String html = response.body();

                Matcher ibuMatcher = Pattern.compile("(?i)IBU\\s*(\\d+)").matcher(html);
                if (ibuMatcher.find()) {
                    beer.setIbu(Integer.parseInt(ibuMatcher.group(1)));
                }

                Matcher untappdMatcher = Pattern.compile("(?i)Untappd:[^<]*<[^>]+>\\s*([0-9.,]+)\\s*<").matcher(html);
                if (!untappdMatcher.find()) {
                    untappdMatcher = Pattern.compile("(?i)Untappd:[\\s\\S]*?([0-9.,]+)\\s*/\\s*5").matcher(html);
                }

                if (untappdMatcher.find()) {
                    beer.setUntappdRating(Double.parseDouble(untappdMatcher.group(1).replace(",", ".")));
                }

                Matcher styleMatcher = Pattern.compile("(?i)Стиль:\\s*([^<]+)").matcher(html);
                if (styleMatcher.find()) {
                    beer.setStyle(unescapeHtml(styleMatcher.group(1).trim()));
                }

                String volStr = beer.getVolume() != null ? beer.getVolume() + "л" : "-";
                String abvStr = beer.getAbv() != null ? beer.getAbv() + "%" : "-";
                String ibuStr = beer.getIbu() != null ? String.valueOf(beer.getIbu()) : "-";
                String untappdStr = beer.getUntappdRating() != null ? String.valueOf(beer.getUntappdRating()) : "-";

                System.out.println("   [Flasker] Оброблено: " + beer.getName() +
                        " (Об'єм: " + volStr + ", ABV: " + abvStr + "%, IBU: " + ibuStr + ", Untappd: " + untappdStr + ")");
                return;

            } catch (java.net.http.HttpTimeoutException e) {
                if (attempt < maxRetries) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            } catch (Exception e) {
                break;
            }
        }
    }

    private String unescapeHtml(String text) {
        if (text == null) return null;
        return text.replace("&#8217;", "'")
                .replace("&#8216;", "'")
                .replace("&#8211;", "-")
                .replace("&#8212;", "—")
                .replace("&#038;", "&")
                .replace("&amp;", "&")
                .replace("&quot;", "\"");
    }
}