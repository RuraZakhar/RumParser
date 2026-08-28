package beer.parser.parsers;

import beer.parser.model.BeerProduct;
import beer.parser.model.Brewery;
import common.parser.http.HttpRetry;
import common.parser.util.JsonExporter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UntappdBeerParser implements BeerParser {

    private static final String BREWERIES_FILE = "src/main/resources/beer-breweries.txt";
    private static final String UNTAPPD_FILE = "src/main/resources/untappd_file.json";
    private static final double MIN_RATING = 3.8;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    // Tuned setting: 16 threads + 250ms throttle between requests.
    private static final int THREAD_POOL_SIZE = 16;
    private static final long MIN_REQUEST_INTERVAL_MS = 250;
    private static final int MAX_RETRIES = 5;

    private static final long BLOCK_WAIT_MS = 11 * 60 * 1000L;
    private static final int MAX_BLOCK_RETRIES = 3;
    private static final int MAX_PAGES_PER_BREWERY = 20;

    private static final Pattern LEADING_NUMBER = Pattern.compile("[\\d.]+");

    private final HttpRetry httpRetry = new HttpRetry(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
            MAX_RETRIES, MIN_REQUEST_INTERVAL_MS, BLOCK_WAIT_MS, MAX_BLOCK_RETRIES,
            true, true, (statusCode, body) -> HttpRetry.looksLikeBlockedPage(body));

    @Override
    public List<BeerProduct> parse(List<BeerProduct> existingCache) {
        List<BeerProduct> parsedBeers = Collections.synchronizedList(new ArrayList<>());
        List<Brewery> breweries = BreweryLoader.loadBreweries(BREWERIES_FILE);

        if (breweries.isEmpty()) {
            System.out.println("[Untappd] Brewery list is empty or file " + BREWERIES_FILE + " was not found. Parsing cancelled.");
            return new ArrayList<>();
        }

        System.out.println("[Untappd] Starting collection (" + THREAD_POOL_SIZE + " threads) for " + breweries.size() + " breweries...");

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        AtomicInteger breweryCount = new AtomicInteger(0);

        for (Brewery brewery : breweries) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                int current = breweryCount.incrementAndGet();
                System.out.println("[" + current + "/" + breweries.size() + "] Processing brewery: " + brewery.getName());
                try {
                    scrapeBrewery(brewery, parsedBeers);
                } catch (Exception e) {
                    System.err.println("   [" + brewery.getName() + "] Unexpected error, brewery skipped: " + e);
                }
            }, executor);

            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
        }

        System.out.println("[Untappd] Collection finished. Got " + parsedBeers.size() + " items with rating >= " + MIN_RATING);

        List<BeerProduct> result = new ArrayList<>(parsedBeers);
        new JsonExporter().exportToJson(result, UNTAPPD_FILE);
        return result;
    }

    private void scrapeBrewery(Brewery brewery, List<BeerProduct> parsedBeers) {
        int page = 1;
        boolean keepGoing = true;

        while (keepGoing && page <= MAX_PAGES_PER_BREWERY) {
            String url = brewery.getUntappdUrl() + "?sort=highest_rated" + (page > 1 ? "&page=" + page : "");
            System.out.println("   [" + brewery.getName() + "] Reading page " + page + "...");

            String html;
            try {
                html = fetchHtml(url);
            } catch (Exception e) {
                System.out.println("   [" + brewery.getName() + "] Error requesting page " + page + ": " + e.getMessage());
                break;
            }

            Document doc = Jsoup.parse(html, "https://untappd.com");
            Elements beerItems = doc.select("div.beer-item");

            if (beerItems.isEmpty()) {
                System.out.println("   [" + brewery.getName() + "] No beers found on page " + page + ". Stopping.");
                break;
            }

            for (Element item : beerItems) {
                BeerProduct beer;
                try {
                    beer = parseBeerItem(item, brewery.getName());
                } catch (Exception e) {
                    System.err.println("      -> Error parsing an item: " + e.getMessage());
                    continue;
                }
                if (beer == null) continue;

                if (beer.getUntappdRating() == null) {
                    System.out.println("      -> Skipped (no rating): " + beer.getName());
                    continue;
                }

                if (beer.getUntappdRating() < MIN_RATING) {
                    System.out.println("      -> Stop: beer '" + beer.getName() + "' has rating " + beer.getUntappdRating() + " (< " + MIN_RATING + ")");
                    keepGoing = false;
                    break;
                }

                parsedBeers.add(beer);

                String abvStr = beer.getAbv() != null ? beer.getAbv() + "%" : "-";
                String ibuStr = beer.getIbu() != null ? String.valueOf(beer.getIbu()) : "-";
                System.out.println("      Added: " + beer.getName() + " [" + beer.getStyle() + "] (rating " + beer.getUntappdRating() + " | ABV: " + abvStr + " | IBU: " + ibuStr + ")");
            }

            page++;
        }
    }

    private BeerProduct parseBeerItem(Element item, String breweryName) {
        Element nameLink = item.selectFirst("p.name a");
        if (nameLink == null) return null;

        String name = nameLink.text().trim();
        if (name.isEmpty()) return null;

        String url = nameLink.absUrl("href");

        Element styleEl = item.selectFirst("p.style");
        String style = styleEl != null ? styleEl.text().trim() : null;

        Element abvEl = item.selectFirst("div.details-item.abv");
        Double abv = parseLeadingDouble(abvEl != null ? abvEl.text() : null);

        Element ibuEl = item.selectFirst("div.details-item.ibu");
        Integer ibu = parseLeadingInt(ibuEl != null ? ibuEl.text() : null);

        Element ratingEl = item.selectFirst("div.caps");
        Double rating = null;
        if (ratingEl != null) {
            String rawRating = ratingEl.attr("data-rating");
            try {
                rating = Double.parseDouble(rawRating.trim());
            } catch (NumberFormatException ignored) {}
        }

        BeerProduct beer = new BeerProduct();
        beer.setBrand(breweryName);
        beer.setName(name);
        beer.setCleanName(name.toLowerCase());
        beer.setStyle(style);
        beer.setAbv(abv);
        beer.setIbu(ibu);
        beer.setUntappdRating(rating);
        beer.setUntappdUrl(url);

        return beer;
    }

    private String fetchHtml(String url) throws Exception {
        return httpRetry.fetch(url, builder -> builder
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html, application/xhtml+xml"));
    }

    private Double parseLeadingDouble(String text) {
        if (text == null) return null;
        Matcher m = LEADING_NUMBER.matcher(text);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group());
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private Integer parseLeadingInt(String text) {
        Double d = parseLeadingDouble(text);
        return d == null ? null : d.intValue();
    }
}
