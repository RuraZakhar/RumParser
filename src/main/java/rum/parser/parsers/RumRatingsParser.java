package rum.parser.parsers;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import rum.parser.model.RumProduct;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RumRatingsParser implements RumParser {

    private static final String LISTING_URL = "https://rumratings.com/brand_explorers";
    private static final double MIN_RATING = 7.0;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String PROVIDER = "RumRatings";

    private static final int THREAD_POOL_SIZE = 3;
    private static final long MIN_REQUEST_INTERVAL_MS = 600;
    private static final int MAX_RETRIES = 5;

    private final Object rateLimitLock = new Object();
    private volatile long lastRequestTime = 0;

    @Override
    public void parse(Set<RumProduct> rumSet) {
        System.out.println("\n[2/3] Starting RumRatings Parser...");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        Map<RumProduct, RumProduct> existingIndex = new HashMap<>();
        for (RumProduct p : rumSet) {
            existingIndex.put(p, p);
        }

        Map<String, RumProduct> topRumsToScrape = new LinkedHashMap<>();
        int page = 1;
        boolean keepGoing = true;
        int skippedAlreadyScraped = 0;

        while (keepGoing) {
            System.out.println(">>> Fetching listing page " + page + "...");

            String html;
            try {
                html = fetchHtmlWithRetry(client, LISTING_URL
                        + "?order_by=average_rating&min_rating=30&page=" + page + "&format=turbo_stream");
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

            for (Element bottle : bottles) {
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

                if (existing != null && existing.getBrand() != null) {
                    // Уже був повністю заскрапений раніше -- деталі не тягнемо повторно,
                    // просто освіжаємо рейтинг і продовжуємо.
                    upsertRating(existing, PROVIDER, rating);
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
            }

            page++;
        }

        System.out.println(">>> Пропущено (вже було заскрапено раніше): " + skippedAlreadyScraped);

        if (topRumsToScrape.isEmpty()) {
            System.out.println("Немає нових ромів для deep-scrape.");
            return;
        }

        System.out.println("\n>>> Step 1 Completed. Found " + topRumsToScrape.size() + " rums to deep-scrape.");
        System.out.println(">>> Moving to Step 2: Extracting deep details...\n");

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        AtomicInteger count = new AtomicInteger(0);

        for (RumProduct basicRum : topRumsToScrape.values()) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    int current = count.incrementAndGet();
                    System.out.println("[" + current + "/" + topRumsToScrape.size() + "] Deep scraping: " + basicRum.getName());

                    String detailHtml = fetchHtmlWithRetry(client, basicRum.getProductUrl());
                    Document detailDoc = Jsoup.parse(detailHtml, "https://rumratings.com");
                    enrichRumFromDetailPage(basicRum, detailDoc);

                    synchronized (rumSet) {
                        mergeIntoSet(rumSet, basicRum);
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

    /**
     * Оновлює рейтинг конкретного провайдера, замінюючи старе значення новим
     * (Rating.equals() звіряє лише по provider, тому звичайний addAll() старе значення не оновить).
     */
    private void upsertRating(RumProduct product, String provider, double value) {
        product.getRatings().removeIf(r -> provider.equals(r.getProvider()));
        product.getRatings().add(new RumProduct.Rating(provider, value));
    }

    private void throttle() {
        synchronized (rateLimitLock) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRequestTime;
            long waitTime = MIN_REQUEST_INTERVAL_MS - elapsed;
            if (waitTime > 0) {
                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestTime = System.currentTimeMillis();
        }
    }

    private String fetchHtmlWithRetry(HttpClient client, String url) throws Exception {
        Exception lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            throttle();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html, application/xhtml+xml")
                    .GET()
                    .build();

            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return response.body();
                }

                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    long backoffMs = resolveBackoff(response, attempt);
                    System.out.println("  HTTP " + response.statusCode() + " for " + url
                            + " -- retry " + attempt + "/" + MAX_RETRIES + " after " + backoffMs + "ms");
                    Thread.sleep(backoffMs);
                    lastError = new RuntimeException("HTTP " + response.statusCode() + " for " + url);
                    continue;
                }

                throw new RuntimeException("HTTP " + response.statusCode() + " for " + url);

            } catch (java.io.IOException e) {
                lastError = e;
                Thread.sleep(1000L * attempt);
            }
        }

        throw lastError != null ? lastError : new RuntimeException("Failed to fetch " + url);
    }

    private long resolveBackoff(HttpResponse<String> response, int attempt) {
        Optional<String> retryAfter = response.headers().firstValue("Retry-After");
        if (retryAfter.isPresent()) {
            try {
                return Long.parseLong(retryAfter.get().trim()) * 1000L;
            } catch (NumberFormatException ignored) {}
        }
        return (long) Math.pow(2, attempt - 1) * 1000L;
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

    private boolean mergeIntoSet(Set<RumProduct> rumSet, RumProduct incomingRum) {
        if (rumSet.add(incomingRum)) return true;
        for (RumProduct existingRum : rumSet) {
            if (existingRum.equals(incomingRum)) {
                existingRum.mergeFrom(incomingRum);
                return false;
            }
        }
        return false;
    }

    private Double parseDoubleSafe(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("[\\d.]+").matcher(s);
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