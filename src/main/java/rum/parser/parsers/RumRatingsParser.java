package rum.parser.parsers;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import rum.parser.model.RumProduct;
import rum.parser.util.RumNameMatcher;
import common.parser.http.HttpRetry;
import common.parser.util.JsonExporter;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RumRatingsParser implements RumParser {

    private static final String LISTING_URL = "https://rumratings.com/rum";
    private static final double MIN_RATING = 7.0;
    private static final String PROVIDER = "RumRatings";

    private static final long MIN_REQUEST_INTERVAL_MS = 2000;
    private static final int MAX_RETRIES = 5;
    private static final long DETAILS_TTL_MS = 30L * 24 * 60 * 60 * 1000;
    private static final Pattern LEADING_NUMBER = Pattern.compile("[\\d.]+");
    private static final double FUZZY_THRESHOLD = 0.90;
    private static final int MAX_LISTING_PAGES = 22;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final int ITEM_RETRY_ATTEMPTS = 2;

    // Snapshot for RumRatingFileLoader (same file-loader pattern as beer.parser's
    // UntappdFileLoader) -- lets this source's refresh cadence be decoupled from the others.
    private static final String RUM_RATING_FILE = "src/main/resources/rumrating_file.json";

    private long lastRequestTime = 0;

    @Override
    public void parse(Set<RumProduct> rumSet) {
        System.out.println("\n[2/3] Starting RumRatings Parser...");

        Map<RumProduct, RumProduct> existingIndex = new HashMap<>();
        for (RumProduct p : rumSet) {
            existingIndex.put(p, p);
        }

        Map<String, RumProduct> topRumsToScrape = new LinkedHashMap<>();

        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(false)
                     .setArgs(List.of(
                             "--disable-blink-features=AutomationControlled",
                             "--window-position=-32000,-32000",
                             "--window-size=1280,800")));
             BrowserContext context = browser.newContext(new Browser.NewContextOptions().setLocale("en-US"))) {

            Page page = context.newPage();

            System.out.println("Opening RumRatings to establish a session (passing Cloudflare check)...");
            page.navigate(LISTING_URL);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForTimeout(1000);

            if (HttpRetry.looksLikeBlockedPage(page.content())) {
                throw new RuntimeException("Still blocked after establishing Playwright session -- Cloudflare protection may have changed.");
            }

            int page1 = 1;
            boolean keepGoing = true;
            int skippedAlreadyScraped = 0;

            while (keepGoing) {
                if (page1 > MAX_LISTING_PAGES) {
                    System.out.println("Reached max listing pages (22). Stopping.");
                    break;
                }

                System.out.println("Fetching listing page " + page1 + "...");

                String html;
                try {
                    html = fetchHtml(page, LISTING_URL + "?order_by=average_rating&min_rating=20&page=" + page1);
                } catch (Exception e) {
                    System.err.println("Error fetching listing page " + page1 + ": " + e.getMessage());
                    break;
                }

                Document doc = Jsoup.parse(html, "https://rumratings.com");
                Elements bottles = doc.select(".brand-index-bottle");
                if (bottles.isEmpty()) {
                    System.out.println("No more rums found. Stopping.");
                    break;
                }
                System.out.println("Page " + page1 + ": found " + bottles.size() + " bottles.");

                for (Element bottle : bottles) {
                    try {
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

                page1++;
            }

            System.out.println("Skipped (already scraped before): " + skippedAlreadyScraped);

            if (topRumsToScrape.isEmpty()) {
                System.out.println("No new rums to deep-scrape.");
                return;
            }

            System.out.println("\nStep 1 completed. Found " + topRumsToScrape.size() + " rums to deep-scrape.");
            System.out.println("Moving to step 2: extracting deep details...\n");

            int current = 0;
            int deepScrapedCount = 0;
            int consecutiveFailures = 0;
            for (RumProduct basicRum : topRumsToScrape.values()) {
                current++;
                try {
                    System.out.println("[" + current + "/" + topRumsToScrape.size() + "] Deep scraping: " + basicRum.getName());


                    HttpRetry.retryWithBackoff(ITEM_RETRY_ATTEMPTS, () -> {
                        String detailHtml = fetchHtml(page, basicRum.getProductUrl());
                        Document detailDoc = Jsoup.parse(detailHtml, "https://rumratings.com");
                        enrichRumFromDetailPage(basicRum, detailDoc);
                        return null;
                    });

                    mergeIntoCollection(rumSet, basicRum);
                    deepScrapedCount++;
                    consecutiveFailures = 0;
                } catch (Exception e) {
                    System.err.println("Error extracting details for " + basicRum.getProductUrl() + ": " + e.getMessage());
                    consecutiveFailures++;
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        System.err.println(consecutiveFailures + " consecutive failures -- likely blocked again, stopping deep-scrape early ("
                                + (topRumsToScrape.size() - current) + " items left unscraped this run).");
                        break;
                    }
                }
            }

            System.out.println("Finished RumRatings. Total newly deep-scraped: " + deepScrapedCount);

            new JsonExporter().exportToJson(new ArrayList<>(topRumsToScrape.values()), RUM_RATING_FILE);

        } catch (Exception e) {
            System.err.println("Critical error in RumRatingsParser (Playwright): " + e.getMessage());
        }
    }

    private void upsertRating(RumProduct product, String provider, double value) {
        product.getRatings().removeIf(r -> provider.equals(r.getProvider()));
        product.getRatings().add(new RumProduct.Rating(provider, value));
    }

    private String fetchHtml(Page page, String url) throws Exception {
        return HttpRetry.retryWithBackoff(MAX_RETRIES, () -> {
            throttle();

            Object raw = page.evaluate(
                    "async (url) => { const r = await fetch(url, { headers: { 'Referer': location.href } }); "
                            + "const t = await r.text(); return { status: r.status, body: t }; }",
                    url);

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) raw;
            int status = ((Number) result.get("status")).intValue();
            String body = (String) result.get("body");

            if (status < 200 || status >= 300 || HttpRetry.looksLikeBlockedPage(body)) {
                System.err.println("HTTP " + status + " or blocked page for " + url + " -- refreshing session and retrying...");
                page.navigate(LISTING_URL);
                page.waitForLoadState(LoadState.NETWORKIDLE);
                throw new RuntimeException("HTTP " + status + " or blocked page for " + url);
            }

            return body;
        });
    }

    private void throttle() throws InterruptedException {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRequestTime;
        long wait = MIN_REQUEST_INTERVAL_MS - elapsed;
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequestTime = System.currentTimeMillis();
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
