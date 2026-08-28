package rum.parser.parsers;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import rum.parser.model.RumProduct;
import rum.parser.util.RumNameMatcher;
import common.parser.http.HttpRetry;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RumRatingsParser implements RumParser {

    private static final String LISTING_URL = "https://rumratings.com/rum";
    private static final double MIN_RATING = 7.0;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String PROVIDER = "RumRatings";

    private static final int THREAD_POOL_SIZE = 1;
    private static final long MIN_REQUEST_INTERVAL_MS = 2000;
    private static final int MAX_RETRIES = 5;
    private static final long DETAILS_TTL_MS = 30L * 24 * 60 * 60 * 1000;
    private static final long BLOCK_WAIT_MS = 11 * 60 * 1000L;
    private static final int MAX_BLOCK_RETRIES = 3;
    private static final Pattern LEADING_NUMBER = Pattern.compile("[\\d.]+");
    private static final double FUZZY_THRESHOLD = 0.90;

    private final HttpRetry httpRetry = new HttpRetry(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NORMAL).build(),
            MAX_RETRIES, MIN_REQUEST_INTERVAL_MS, BLOCK_WAIT_MS, MAX_BLOCK_RETRIES,
            true, false, (statusCode, body) -> HttpRetry.looksLikeBlockedPage(body));

    @Override
    public void parse(Set<RumProduct> rumSet) {
        System.out.println("\n[2/3] Starting RumRatings Parser...");

        Map<RumProduct, RumProduct> existingIndex = new HashMap<>();
        for (RumProduct p : rumSet) {
            existingIndex.put(p, p);
        }

        Map<String, RumProduct> topRumsToScrape = new LinkedHashMap<>();
        int page = 1;
        boolean keepGoing = true;
        int skippedAlreadyScraped = 0;

        while (keepGoing) {
            System.out.println("Fetching listing page " + page + "...");

            String html;
            try {
                html = fetchHtml(LISTING_URL + "?order_by=average_rating&min_rating=20&page=" + page + "&format=turbo_stream");
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
                    Element link = bottle.selectFirst("a[href^=/rum/]");
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
    }

    private void upsertRating(RumProduct product, String provider, double value) {
        product.getRatings().removeIf(r -> provider.equals(r.getProvider()));
        product.getRatings().add(new RumProduct.Rating(provider, value));
    }

    private String fetchHtml(String url) throws Exception {
        return httpRetry.fetch(url, builder -> builder
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/vnd.turbo-stream.html, text/html, application/xhtml+xml")
                .header("Referer", "https://rumratings.com/rum"));
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

    private boolean mergeIntoCollection(Set<RumProduct> rumSet, RumProduct incomingRum) {
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
