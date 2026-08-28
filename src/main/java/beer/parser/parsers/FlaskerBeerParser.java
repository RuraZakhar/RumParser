package beer.parser.parsers;

import beer.parser.model.BeerProduct;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import common.parser.http.HttpRetry;
import common.parser.util.JsonUtils;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FlaskerBeerParser implements BeerParser {

    private static final long DETAILS_TTL_MS = 30L * 24 * 60 * 60 * 1000;

    private static final List<String> KNOWN_COUNTRIES = Arrays.asList(
            "україна", "бельгія", "німеччина", "сша", "чехія", "британія",
            "польща", "нідерланди", "ірландія", "іспанія", "італія", "франція", "шотландія"
    );

    private static final Pattern NAME_VOLUME_PATTERN = Pattern.compile("(?i)([0-9.,]+)\\s*(мл|ml|л|l)\\b");
    private static final Pattern NAME_ABV_PATTERN = Pattern.compile("(?i)([0-9.,]+)\\s*(%|°)");
    private static final Pattern TRAILING_DASH_PATTERN = Pattern.compile("(?i)\\s*-\\.?$");
    private static final Pattern YEAR_BRACKET_PATTERN = Pattern.compile("(?i)\\[\\d{4}\\]");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern IBU_PATTERN = Pattern.compile("(?i)IBU\\s*(\\d+)");
    private static final Pattern UNTAPPD_PATTERN_A = Pattern.compile("(?i)Untappd:[^<]*<[^>]+>\\s*([0-9.,]+)\\s*<");
    private static final Pattern UNTAPPD_PATTERN_B = Pattern.compile("(?i)Untappd:[\\s\\S]*?([0-9.,]+)\\s*/\\s*5");
    private static final Pattern STYLE_PATTERN = Pattern.compile("(?i)Стиль:\\s*([^<]+)");

    private final HttpRetry httpRetry = new HttpRetry(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), 3);

    @Override
    public List<BeerProduct> parse(List<BeerProduct> existingCache) {
        List<BeerProduct> rawBeers = new ArrayList<>();
        List<BeerProduct> beersNeedsDetails = new ArrayList<>();

        java.util.Map<String, BeerProduct> existingIndex = new java.util.HashMap<>();
        if (existingCache != null) {
            for (BeerProduct b : existingCache) {
                if (b.getFlaskerUrl() != null) {
                    existingIndex.put(b.getFlaskerUrl(), b);
                }
            }
        }

        int perPage = 100;
        int page = 1;
        boolean hasMore = true;

        System.out.println("   [Flasker] Collecting catalog via API...");

        try {
            while (hasMore) {
                String url = "https://flasker.com.ua/wp-json/wc/store/v1/products?per_page=" + perPage + "&page=" + page;

                String responseBody = httpRetry.fetch(url, builder -> {});

                JsonArray items = JsonParser.parseString(responseBody).getAsJsonArray();
                if (items.isEmpty()) { hasMore = false; continue; }

                for (JsonElement element : items) {
                    JsonObject item = element.getAsJsonObject();
                    BeerProduct beer = new BeerProduct();

                    String rawName = JsonUtils.getStringOrNull(item, "name");
                    if (rawName != null) {
                        rawName = unescapeHtml(rawName);

                        Matcher volMatcher = NAME_VOLUME_PATTERN.matcher(rawName);
                        if (volMatcher.find()) {
                            try {
                                double v = Double.parseDouble(volMatcher.group(1).replace(",", "."));
                                String unit = volMatcher.group(2).toLowerCase();
                                if (unit.contains("м") || unit.contains("m")) v = v / 1000.0;
                                beer.setVolume(v);
                                rawName = rawName.replace(volMatcher.group(0), "");
                            } catch (NumberFormatException ignored) {}
                        }

                        Matcher abvMatcher = NAME_ABV_PATTERN.matcher(rawName);
                        if (abvMatcher.find()) {
                            try {
                                if (abvMatcher.group(2).equals("%")) beer.setAbv(Double.parseDouble(abvMatcher.group(1).replace(",", ".")));
                                rawName = rawName.replace(abvMatcher.group(0), "");
                            } catch (NumberFormatException ignored) {}
                        }

                        rawName = TRAILING_DASH_PATTERN.matcher(rawName).replaceAll("");
                        rawName = YEAR_BRACKET_PATTERN.matcher(rawName).replaceAll("");
                        rawName = WHITESPACE_PATTERN.matcher(rawName).replaceAll(" ").trim();
                        beer.setName(rawName);
                        beer.setCleanName(rawName.toLowerCase());
                    }

                    beer.setFlaskerUrl(JsonUtils.getStringOrNull(item, "permalink"));

                    if (item.has("prices") && !item.get("prices").isJsonNull()) {
                        Double rawPrice = JsonUtils.getDoubleOrNull(item.getAsJsonObject("prices"), "price");
                        if (rawPrice != null) beer.setFlaskerPrice(rawPrice);
                    }

                    if (item.has("images") && item.get("images").isJsonArray() && !item.getAsJsonArray("images").isEmpty()) {
                        beer.setImgUrl(JsonUtils.getStringOrNull(item.getAsJsonArray("images").get(0).getAsJsonObject(), "src"));
                    }

                    if (item.has("brands") && item.get("brands").isJsonArray() && !item.getAsJsonArray("brands").isEmpty()) {
                        beer.setBrand(unescapeHtml(JsonUtils.getStringOrNull(item.getAsJsonArray("brands").get(0).getAsJsonObject(), "name")));
                    }

                    if (item.has("tags") && item.get("tags").isJsonArray()) {
                        for (JsonElement tagEl : item.getAsJsonArray("tags")) {
                            String tagName = JsonUtils.getStringOrNull(tagEl.getAsJsonObject(), "name");
                            if (tagName != null && KNOWN_COUNTRIES.contains(tagName.toLowerCase())) beer.setCountry(tagName);
                        }
                    }

                    if (beer.getFlaskerUrl() != null) {
                        BeerProduct cachedBeer = existingIndex.get(beer.getFlaskerUrl());
                        boolean detailsAreFresh = cachedBeer != null
                                && cachedBeer.getStyle() != null
                                && cachedBeer.getLastScrapedAt() != null
                                && (System.currentTimeMillis() - cachedBeer.getLastScrapedAt()) < DETAILS_TTL_MS;

                        if (detailsAreFresh) {
                            beer.setStyle(cachedBeer.getStyle());
                            beer.setIbu(cachedBeer.getIbu());
                            beer.setUntappdRating(cachedBeer.getUntappdRating());
                            beer.setLastScrapedAt(cachedBeer.getLastScrapedAt());
                            System.out.println("   [Flasker] Found in cache (price refreshed): " + beer.getName());
                        } else {
                            beersNeedsDetails.add(beer);
                        }
                    }
                    rawBeers.add(beer);
                }
                page++;
            }
        } catch (Exception e) { e.printStackTrace(); }

        if (!beersNeedsDetails.isEmpty()) {
            System.out.println("   [Flasker] New items without cache: " + beersNeedsDetails.size() + ". Starting deep search for IBU and Untappd...");
            ExecutorService executor = Executors.newFixedThreadPool(15);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            try {
                for (BeerProduct beer : beersNeedsDetails) {
                    futures.add(CompletableFuture.runAsync(() -> fetchDetailsFromHtml(beer, beer.getFlaskerUrl()), executor));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } finally {
                executor.shutdown();
            }
        } else {
            System.out.println("   [Flasker] All items found in cache! Deep parsing not needed.");
        }

        return new ArrayList<>(new LinkedHashSet<>(rawBeers));
    }

    private void fetchDetailsFromHtml(BeerProduct beer, String url) {
        String html;
        try {
            html = httpRetry.fetch(url, builder -> builder.timeout(Duration.ofSeconds(10)));
        } catch (Exception e) {
            System.out.println("   [Flasker] Giving up on details for: " + url + " (" + e.getMessage() + ")");
            return;
        }

        try {
            Matcher ibuMatcher = IBU_PATTERN.matcher(html);
            if (ibuMatcher.find()) {
                beer.setIbu(Integer.parseInt(ibuMatcher.group(1)));
            }

            Matcher untappdMatcher = UNTAPPD_PATTERN_A.matcher(html);
            if (!untappdMatcher.find()) {
                untappdMatcher = UNTAPPD_PATTERN_B.matcher(html);
            }

            if (untappdMatcher.find()) {
                beer.setUntappdRating(Double.parseDouble(untappdMatcher.group(1).replace(",", ".")));
            }

            Matcher styleMatcher = STYLE_PATTERN.matcher(html);
            if (styleMatcher.find()) {
                beer.setStyle(unescapeHtml(styleMatcher.group(1).trim()));
            }

            String volStr = beer.getVolume() != null ? String.valueOf(beer.getVolume()) : "-";
            String abvStr = beer.getAbv() != null ? beer.getAbv() + "%" : "-";
            String ibuStr = beer.getIbu() != null ? String.valueOf(beer.getIbu()) : "-";
            String untappdStr = beer.getUntappdRating() != null ? String.valueOf(beer.getUntappdRating()) : "-";

            System.out.println("   [Flasker] Processed: " + beer.getName() +
                    " (volume: " + volStr + ", ABV: " + abvStr + "%, IBU: " + ibuStr + ", Untappd: " + untappdStr + ")");

            beer.setLastScrapedAt(System.currentTimeMillis());
        } catch (Exception e) {
            System.out.println("   [Flasker] Failed to parse details for: " + url + " (" + e.getMessage() + ")");
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
