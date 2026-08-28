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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SilpoBeerParser implements BeerParser {

    private static final String BASE_PRODUCT_URL = "https://silpo.ua/product/";
    private static final String API_PRODUCT_DETAILS_URL = "https://sf-ecom-api.silpo.ua/v1/uk/branches/00000000-0000-0000-0000-000000000000/products/";
    private static final String BASE_IMAGE_URL = "https://s7g10.scene7.com/is/image/silpo/";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0";
    private static final long DETAILS_TTL_MS = 30L * 24 * 60 * 60 * 1000;
    private static final String[] CATEGORIES = {
            "kraftove-pyvo-4506",
            "importne-pyvo-4505"
    };
    private static final Pattern VOLUME_PATTERN = Pattern.compile("(?i)([0-9.,]+)\\s*(мл|ml|л|l)");

    private final HttpRetry httpRetry = new HttpRetry(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), 3);

    @Override
    public List<BeerProduct> parse(List<BeerProduct> existingCache) {
        List<BeerProduct> rawBeers = new ArrayList<>();
        List<BeerProduct> beersNeedsDetails = new ArrayList<>();
        List<String> slugsForDetails = new ArrayList<>();

        java.util.Map<String, BeerProduct> existingIndex = new java.util.HashMap<>();
        for (BeerProduct b : existingCache) {
            if (b.getSilpoUrl() != null) {
                existingIndex.put(b.getSilpoUrl(), b);
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

                    String responseBody = httpRetry.fetch(url, builder -> builder
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", "https://silpo.ua/"));

                    JsonObject rootObj = JsonParser.parseString(responseBody).getAsJsonObject();
                    JsonArray items = rootObj.getAsJsonArray("items");

                    if (items == null) {
                        System.out.println("   [Silpo] Response has no 'items' field, skipping page.");
                        break;
                    }

                    int fetchedSize = items.size();

                    if (items.isEmpty()) { hasMore = false; continue; }

                    for (JsonElement element : items) {
                        JsonObject item = element.getAsJsonObject();
                        BeerProduct beer = new BeerProduct();

                        beer.setName(JsonUtils.getStringOrNull(item, "title"));
                        beer.setBrand(JsonUtils.getStringOrNull(item, "brandTitle"));
                        if (beer.getName() != null) beer.setCleanName(beer.getName().toLowerCase());

                        Double guestRating = JsonUtils.getDoubleOrNull(item, "guestProductRating");
                        if (guestRating != null) beer.setSilpoRating(guestRating);

                        Double untappd = JsonUtils.getDoubleOrNull(item, "untappdRating");
                        if (untappd != null) beer.setUntappdRating(untappd);

                        beer.setSilpoPrice(JsonUtils.getDoubleOrNull(item, "price"));

                        String icon = JsonUtils.getStringOrNull(item, "icon");
                        if (icon != null) beer.setImgUrl(BASE_IMAGE_URL + icon);

                        String slug = JsonUtils.getStringOrNull(item, "slug");
                        if (slug != null && !slug.isEmpty()) {
                            beer.setSilpoUrl(BASE_PRODUCT_URL + slug);

                            BeerProduct cachedBeer = existingIndex.get(beer.getSilpoUrl());
                            boolean detailsAreFresh = cachedBeer != null
                                    && cachedBeer.getAbv() != null
                                    && cachedBeer.getLastScrapedAt() != null
                                    && (System.currentTimeMillis() - cachedBeer.getLastScrapedAt()) < DETAILS_TTL_MS;

                            if (detailsAreFresh) {
                                beer.setAbv(cachedBeer.getAbv());
                                beer.setCountry(cachedBeer.getCountry());
                                beer.setPackaging(cachedBeer.getPackaging());
                                beer.setVolume(cachedBeer.getVolume());
                                beer.setLastScrapedAt(cachedBeer.getLastScrapedAt());
                                System.out.println("   [Silpo] Found in cache (price refreshed): " + beer.getName());
                            } else {
                                beersNeedsDetails.add(beer);
                                slugsForDetails.add(slug);
                            }
                        }
                        rawBeers.add(beer);
                    }
                    if (fetchedSize < limit) hasMore = false;
                    else offset += limit;
                }
            } catch (Exception e) { e.printStackTrace(); }
        }

        if (!beersNeedsDetails.isEmpty()) {
            System.out.println("   [Silpo] New items without cache: " + beersNeedsDetails.size() + ". Starting deep parsing...");
            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(15);
            List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < beersNeedsDetails.size(); i++) {
                    BeerProduct beer = beersNeedsDetails.get(i);
                    String slug = slugsForDetails.get(i);
                    futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> fetchDetailsFromApi(beer, slug), executor));
                }
                java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();
            } finally {
                executor.shutdown();
            }
        } else {
            System.out.println("   [Silpo] All items found in cache! Deep parsing not needed.");
        }

        return new ArrayList<>(new LinkedHashSet<>(rawBeers));
    }

    private void fetchDetailsFromApi(BeerProduct beer, String slug) {
        String url = API_PRODUCT_DETAILS_URL + slug;

        String responseBody;
        try {
            responseBody = httpRetry.fetch(url, builder -> builder
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://silpo.ua/"));
        } catch (Exception e) {
            System.out.println("   [Silpo] Giving up on details for: " + slug + " (" + e.getMessage() + ")");
            return;
        }

        try {
            JsonObject rootObj = JsonParser.parseString(responseBody).getAsJsonObject();

            String displayRatio = JsonUtils.getStringOrNull(rootObj, "displayRatio");
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
                    if ("generalInfo".equals(JsonUtils.getStringOrNull(group, "key"))) {
                        JsonArray attributes = group.getAsJsonArray("attributes");
                        if (attributes != null) {
                            for (JsonElement attrEl : attributes) {
                                JsonObject attrObj = attrEl.getAsJsonObject();
                                JsonObject attrItem = attrObj.getAsJsonObject("attribute");
                                JsonObject valueItem = attrObj.getAsJsonObject("value");

                                if (attrItem != null && valueItem != null) {
                                    String attrId = JsonUtils.getStringOrNull(attrItem, "id");

                                    if ("alcoholcontent".equals(attrId)) {
                                        if (valueItem.has("title") && !valueItem.get("title").isJsonNull()) {
                                            try {
                                                beer.setAbv(valueItem.get("title").getAsDouble());
                                            } catch (Exception ignored) {}
                                        }
                                    } else if ("country".equals(attrId)) {
                                        beer.setCountry(JsonUtils.getStringOrNull(valueItem, "title"));
                                    } else if ("typupakovky".equals(attrId)) {
                                        beer.setPackaging(JsonUtils.getStringOrNull(valueItem, "title"));
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
            String volStr = beer.getVolume() != null ? String.valueOf(beer.getVolume()) : "-";

            System.out.println("   [Silpo] Processed: " + beer.getName() +
                    " (volume: " + volStr + "l, ABV: " + abvStr + ", country: " + countryStr + ", packaging: " + packStr + ")");

            beer.setLastScrapedAt(System.currentTimeMillis());
        } catch (Exception e) {
            System.out.println("   [Silpo] Failed to parse details for: " + slug + " (" + e.getMessage() + ")");
        }
    }

    private void extractVolumeFromString(BeerProduct beer, String text) {
        Matcher volMatcher = VOLUME_PATTERN.matcher(text);
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
}
