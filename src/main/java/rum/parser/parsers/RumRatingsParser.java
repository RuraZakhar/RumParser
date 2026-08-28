package rum.parser.parsers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import rum.parser.model.RumProduct;
import rum.parser.util.RumNameMatcher;
import common.parser.http.HttpRetry;
import common.parser.util.JsonExporter;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RumRatingsParser implements RumParser {

    private static final String LISTING_URL = "https://rumratings.com/rum";
    private static final double MIN_RATING = 7.0;
    private static final String PROVIDER = "RumRatings";

    private static final int THREAD_POOL_SIZE = 1;
    private static final long MIN_REQUEST_INTERVAL_MS = 6500;
    private static final int MAX_RETRIES = 5;
    private static final long DETAILS_TTL_MS = 30L * 24 * 60 * 60 * 1000;
    private static final long BLOCK_WAIT_MS = 11 * 60 * 1000L;
    private static final int MAX_BLOCK_RETRIES = 3;
    private static final Pattern LEADING_NUMBER = Pattern.compile("[\\d.]+");
    private static final double FUZZY_THRESHOLD = 0.90;

    // Hard backstop on top of the rating < MIN_RATING check: page 22 is the last page
    // where rums with rating >= 7.0 still show up, and since Firecrawl renders via a
    // headless browser the sort order isn't always guaranteed to come back identically,
    // so the rating check alone isn't reliable enough to guarantee pagination stops.
    private static final int MAX_LISTING_PAGES = 22;

    // Snapshot for RumRatingFileLoader (same file-loader pattern as beer.parser's
    // UntappdFileLoader) -- lets this source's refresh cadence be decoupled from
    // the others, and matters more here given Firecrawl's cost per call.
    private static final String RUM_RATING_FILE = "src/main/resources/rumrating_file.json";

    // rumratings.com sits behind Cloudflare and only serves real content after a
    // JS challenge that issues a cf_clearance cookie -- a plain HttpClient can never
    // obtain that cookie, no matter the headers. Firecrawl renders the page in a real
    // headless browser and hands back the resulting HTML, which we parse exactly as
    // before; see the refactor report for the rest of the reasoning.
    private static final String FIRECRAWL_SCRAPE_URL = "https://api.firecrawl.dev/v1/scrape";
    private static final Duration FIRECRAWL_TIMEOUT = Duration.ofSeconds(120);

    private final String firecrawlApiKey = "fc-6c30ac7e7df944d7913b81b368e39eaa";

    private final HttpRetry httpRetry = new HttpRetry(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NORMAL).build(),
            MAX_RETRIES, MIN_REQUEST_INTERVAL_MS, BLOCK_WAIT_MS, MAX_BLOCK_RETRIES,
            true, false, (statusCode, body) -> HttpRetry.looksLikeBlockedPage(body));

    @Override
    public void parse(Set<RumProduct> rumSet) {
        System.out.println("\n[2/3] Starting RumRatings Parser...");

        if (firecrawlApiKey == null || firecrawlApiKey.isBlank()) {
            System.err.println("FIRECRAWL_API_KEY is not set (see .env.example). Skipping RumRatings parser.");
            return;
        }

        Map<RumProduct, RumProduct> existingIndex = new HashMap<>();
        for (RumProduct p : rumSet) {
            existingIndex.put(p, p);
        }

        Map<String, RumProduct> topRumsToScrape = new LinkedHashMap<>();
        int page = 1;
        boolean keepGoing = true;
        int skippedAlreadyScraped = 0;

        while (keepGoing) {
            if (page > MAX_LISTING_PAGES) {
                System.out.println("Reached max listing pages (22). Stopping.");
                keepGoing = false;
                break;
            }

            System.out.println("Fetching listing page " + page + "...");

            String html;
            try {
                html = fetchHtml(LISTING_URL + "?order_by=average_rating&min_rating=20&page=" + page);
            } catch (Exception e) {
                System.err.println("Error fetching listing page " + page + ": " + e.getMessage());
                break;
            }

            Document doc = Jsoup.parse(html, "https://rumratings.com");
            Elements bottles = doc.select(".brand-index-bottle");
            if (bottles.isEmpty()) {
                System.out.println("No more rums found. Stopping.");
                break;
            }
            System.out.println("Page " + page + ": found " + bottles.size() + " bottles.");

            for (Element bottle : bottles) {
                try {
                    // The real markup renders an absolute href (https://rumratings.com/rum/...),
                    // not a relative one, so this needs a substring match, not a prefix match.
                    Element link = bottle.selectFirst("a[href*=/rum/]");
                    Element ratingEl = bottle.selectFirst(".brand-rating-icon p");
                    Element nameEl = bottle.selectFirst(".brand-title span");
                    if (link == null || ratingEl == null || nameEl == null) continue;

                    Double rating = parseDoubleSafe(ratingEl.text());
                    if (rating == null) continue;

                    if (rating < MIN_RATING) {
                        keepGoing = false;
                        break;
                    }

                    String productUrl = link.absUrl("href");
                    String name = nameEl.text().trim();

                    RumProduct probe = new RumProduct();
                    probe.setName(name);
                    RumProduct existing = existingIndex.get(probe);

                    boolean detailsAreFresh = existing != null
                            && existing.getBrand() != null
                            && existing.getLastScrapedAt() != null
                            && (System.currentTimeMillis() - existing.getLastScrapedAt()) < DETAILS_TTL_MS;

                    if (detailsAreFresh) {
                        upsertRating(existing, PROVIDER, rating);
                        existing.addSourceUrl("RumRatings", productUrl);
                        if (existing.getProductUrl() == null) {
                            existing.setProductUrl(productUrl);
                        }
                        skippedAlreadyScraped++;
                        continue;
                    }

                    RumProduct basicRum = (existing != null) ? existing : new RumProduct();
                    basicRum.setName(name);
                    basicRum.setProductUrl(productUrl);
                    basicRum.addSourceUrl("RumRatings", productUrl);
                    upsertRating(basicRum, PROVIDER, rating);

                    topRumsToScrape.put(productUrl, basicRum);
                } catch (Exception e) {
                    System.err.println("Error parsing a listing entry, skipping it: " + e.getMessage());
                }
            }

            page++;
        }

        System.out.println("Skipped (already scraped before): " + skippedAlreadyScraped);

        if (topRumsToScrape.isEmpty()) {
            System.out.println("No new rums to deep-scrape.");
            return;
        }

        System.out.println("\nStep 1 completed. Found " + topRumsToScrape.size() + " rums to deep-scrape.");
        System.out.println("Moving to step 2: extracting deep details...\n");

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        AtomicInteger count = new AtomicInteger(0);

        for (RumProduct basicRum : topRumsToScrape.values()) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    int current = count.incrementAndGet();
                    System.out.println("[" + current + "/" + topRumsToScrape.size() + "] Deep scraping: " + basicRum.getName());

                    String detailHtml = fetchHtml(basicRum.getProductUrl());
                    Document detailDoc = Jsoup.parse(detailHtml, "https://rumratings.com");
                    enrichRumFromDetailPage(basicRum, detailDoc);

                    synchronized (rumSet) {
                        mergeIntoCollection(rumSet, basicRum);
                    }
                } catch (Exception e) {
                    System.err.println("Error extracting details for " + basicRum.getProductUrl() + ": " + e.getMessage());
                }
            }, executor);

            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        executor.shutdown();

        System.out.println("Finished RumRatings. Total newly deep-scraped: " + count.get());

        // Both early-return paths above (missing API key, no new rums to deep-scrape)
        // skip this line entirely, so a failed/empty run never overwrites a good
        // existing snapshot. topRumsToScrape.values() is exactly what this run
        // deep-scraped -- items merely refreshed via the detailsAreFresh/upsertRating
        // path above never entered this map, so they're correctly excluded here too.
        new JsonExporter().exportToJson(new ArrayList<>(topRumsToScrape.values()), RUM_RATING_FILE);
    }

    private void upsertRating(RumProduct product, String provider, double value) {
        product.getRatings().removeIf(r -> provider.equals(r.getProvider()));
        product.getRatings().add(new RumProduct.Rating(provider, value));
    }

    // Routed through Firecrawl instead of a direct GET (rumratings.com is behind
    // Cloudflare -- see the field-level comment on FIRECRAWL_SCRAPE_URL). Still goes
    // through the same httpRetry instance, so its throttle/retry/backoff apply to
    // Firecrawl calls exactly as they did to direct ones -- both the listing-page
    // loop and every per-item deep-scrape call end up here, so all Firecrawl usage
    // for this parser is paced by the one throttle.
    private String fetchHtml(String targetUrl) throws Exception {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("url", targetUrl);
        // Firecrawl's "main content" extraction would strip <head> (losing the
        // description <meta> tag) and risks stripping the listing/detail markup our
        // selectors depend on -- we want the untouched full page, not a trimmed one.
        requestBody.addProperty("onlyMainContent", false);
        // Gives the Cloudflare JS challenge time to resolve before Firecrawl captures HTML.
        requestBody.addProperty("waitFor", 3000);
        JsonArray formats = new JsonArray();
        formats.add("html");
        requestBody.add("formats", formats);

        String firecrawlResponseBody = httpRetry.fetch(FIRECRAWL_SCRAPE_URL, builder -> builder
                .timeout(FIRECRAWL_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + firecrawlApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString())));

        return extractHtml(firecrawlResponseBody, targetUrl);
    }

    private String extractHtml(String firecrawlResponseBody, String targetUrl) {
        JsonObject responseJson = JsonParser.parseString(firecrawlResponseBody).getAsJsonObject();

        boolean success = responseJson.has("success") && responseJson.get("success").getAsBoolean();
        if (!success) {
            throw new RuntimeException("Firecrawl request unsuccessful for " + targetUrl
                    + ": " + truncate(firecrawlResponseBody, 300));
        }

        JsonObject data = responseJson.has("data") && responseJson.get("data").isJsonObject()
                ? responseJson.getAsJsonObject("data") : null;
        if (data == null || !data.has("html") || data.get("html").isJsonNull()) {
            throw new RuntimeException("Firecrawl response has no html for " + targetUrl
                    + ": " + truncate(firecrawlResponseBody, 300));
        }

        return data.get("html").getAsString();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "null";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    private void enrichRumFromDetailPage(RumProduct rum, Document doc) {
        Map<String, String> details = extractLabelValuePairs(doc);

        rum.setBrand(details.get("Company"));
        rum.setType(details.get("Type"));
        rum.setRegion(details.get("Country"));
        rum.setAbv(parseDoubleSafe(details.get("ABV")));
        rum.setAge(parseDoubleSafe(details.get("Years Aged")));
        rum.setYearDistilled(parseIntSafe(details.get("Yr Distilled")));
        rum.setRawMaterial(details.get("Raw Material"));
        rum.setProcess(details.get("Process"));
        rum.setDistillationMethod(details.get("Distillation"));
        rum.setCategory("rum");

        String womenLed = details.get("Women Led");
        if (womenLed != null) {
            rum.setWomenLed(womenLed.trim().equalsIgnoreCase("Yes"));
        }

        Element descEl = doc.selectFirst("meta[name=description]");
        if (descEl != null) {
            rum.setDescription(descEl.attr("content").trim());
        }

        Element imgEl = doc.selectFirst("img[alt~=(?i)\\s+rum$]");
        if (imgEl != null) {
            rum.setImgUrl(imgEl.absUrl("src"));
        }

        rum.enrichDerivedFields();
        rum.setLastScrapedAt(System.currentTimeMillis());
    }

    private Map<String, String> extractLabelValuePairs(Document doc) {
        Map<String, String> result = new LinkedHashMap<>();

        Element heading = doc.selectFirst("h3:matchesOwn(^\\s*Rum Details\\s*$)");
        if (heading == null) return result;

        Element container = heading.nextElementSibling();
        if (container == null) return result;

        Elements rows = container.select("> div.flex.mb-2");
        for (Element row : rows) {
            Elements children = row.children();
            if (children.size() < 2) continue;

            Element labelContainer = children.first();
            Element valueEl = children.last();

            Element labelSpan = labelContainer.selectFirst("span.font-bold");
            if (labelSpan == null) continue;

            String label = labelSpan.text().replace(":", "").trim();
            String value = valueEl.text().trim();

            if (!value.isEmpty()) {
                result.put(label, value);
            }
        }

        return result;
    }

    // Package-private + static (no instance state used) so RumRatingFileLoader can
    // reuse this exact exact-match-then-fuzzy-match path instead of duplicating it.
    static boolean mergeIntoCollection(Set<RumProduct> rumSet, RumProduct incomingRum) {
        for (RumProduct existingRum : rumSet) {
            if (existingRum.equals(incomingRum)) {
                existingRum.mergeFrom(incomingRum);
                return false;
            }
        }

        RumProduct fuzzyMatch = RumNameMatcher.findBestFuzzyMatch(incomingRum, rumSet, FUZZY_THRESHOLD);
        if (fuzzyMatch != null) {
            fuzzyMatch.mergeFrom(incomingRum);
            return false;
        }

        rumSet.add(incomingRum);
        return true;
    }

    private Double parseDoubleSafe(String s) {
        if (s == null) return null;
        Matcher m = LEADING_NUMBER.matcher(s);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group());
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private Integer parseIntSafe(String s) {
        Double d = parseDoubleSafe(s);
        return d == null ? null : d.intValue();
    }
}
