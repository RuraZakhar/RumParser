package beer.parser.parsers;

import beer.parser.model.BeerProduct;
import beer.parser.model.Brewery;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UntappdBeerParser implements BeerParser {

    private static final String BREWERIES_FILE = "src/main/resources/beer-breweries.txt";
    private static final double MIN_RATING = 3.8;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    // Оптимальні налаштування: 16 потоків + 250 мс затримка
    private static final int THREAD_POOL_SIZE = 16;
    private static final long MIN_REQUEST_INTERVAL_MS = 250;
    private static final int MAX_RETRIES = 5;

    private static final long BLOCK_WAIT_MS = 11 * 60 * 1000L;
    private static final int MAX_BLOCK_RETRIES = 3;
    private static final int MAX_PAGES_PER_BREWERY = 20;

    private static final Pattern LEADING_NUMBER = Pattern.compile("[\\d.]+");

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final Object rateLimitLock = new Object();
    private volatile long lastRequestTime = 0;

    // Shared across all worker threads so a detected block pauses the whole pool
    // instead of each thread independently sleeping and then retrying in lockstep.
    private final AtomicLong blockedUntil = new AtomicLong(0);

    @Override
    public List<BeerProduct> parse(List<BeerProduct> existingCache) {
        List<BeerProduct> parsedBeers = Collections.synchronizedList(new ArrayList<>());
        List<Brewery> breweries = BreweryLoader.loadBreweries(BREWERIES_FILE);

        if (breweries.isEmpty()) {
            System.out.println("[Untappd] Список броварень порожній або файл " + BREWERIES_FILE + " не знайдено. Парсинг скасовано.");
            return new ArrayList<>();
        }

        System.out.println("[Untappd] Запуск збору (" + THREAD_POOL_SIZE + " потоки, стабільний режим) для " + breweries.size() + " броварень...");

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        AtomicInteger breweryCount = new AtomicInteger(0);

        for (Brewery brewery : breweries) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                int current = breweryCount.incrementAndGet();
                System.out.println("[" + current + "/" + breweries.size() + "] Обробка броварні: " + brewery.getName());
                try {
                    scrapeBrewery(brewery, parsedBeers);
                } catch (Exception e) {
                    System.err.println("   [" + brewery.getName() + "] ❌ Непередбачена помилка, броварню пропущено: " + e);
                }
            }, executor);

            futures.add(future);
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
        }

        System.out.println("[Untappd] Збір завершено. Отримано " + parsedBeers.size() + " позицій з рейтингом >= " + MIN_RATING);
        return new ArrayList<>(parsedBeers);
    }

    private void scrapeBrewery(Brewery brewery, List<BeerProduct> parsedBeers) {
        int page = 1;
        boolean keepGoing = true;

        while (keepGoing && page <= MAX_PAGES_PER_BREWERY) {
            String url = brewery.getUntappdUrl() + "?sort=highest_rated" + (page > 1 ? "&page=" + page : "");
            System.out.println("   [" + brewery.getName() + "] Читаю сторінку " + page + "...");

            String html;
            try {
                html = fetchHtmlWithRetry(url);
            } catch (Exception e) {
                System.out.println("   [" + brewery.getName() + "] ❌ Помилка запиту сторінки " + page + ": " + e.getMessage());
                break;
            }

            Document doc = Jsoup.parse(html, "https://untappd.com");
            Elements beerItems = doc.select("div.beer-item");

            if (beerItems.isEmpty()) {
                System.out.println("   [" + brewery.getName() + "] Пива на сторінці " + page + " не знайдено. Завершую.");
                break;
            }

            for (Element item : beerItems) {
                BeerProduct beer;
                try {
                    beer = parseBeerItem(item, brewery.getName());
                } catch (Exception e) {
                    System.err.println("      -> ⚠️ Помилка парсингу елемента: " + e.getMessage());
                    continue;
                }
                if (beer == null) continue;

                if (beer.getUntappdRating() == null) {
                    System.out.println("      -> ⚠️ Пропуск (немає рейтингу): " + beer.getName());
                    continue;
                }

                if (beer.getUntappdRating() < MIN_RATING) {
                    System.out.println("      -> 🛑 Стоп: пиво '" + beer.getName() + "' має рейтинг " + beer.getUntappdRating() + " (< " + MIN_RATING + ")");
                    keepGoing = false;
                    break;
                }

                parsedBeers.add(beer);

                String abvStr = beer.getAbv() != null ? beer.getAbv() + "%" : "-";
                String ibuStr = beer.getIbu() != null ? String.valueOf(beer.getIbu()) : "-";
                System.out.println("      ✅ Додано: " + beer.getName() + " [" + beer.getStyle() + "] (★ " + beer.getUntappdRating() + " | ABV: " + abvStr + " | IBU: " + ibuStr + ")");
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

    private String fetchHtmlWithRetry(String url) throws Exception {
        Exception lastError = null;
        int blockRetries = 0;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            awaitIfBlocked();
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
                    if (isBlockedPage(response.body())) {
                        if (blockRetries >= MAX_BLOCK_RETRIES) {
                            throw new RuntimeException("Заблоковано навіть після довгих очікувань: " + url);
                        }
                        blockRetries++;
                        System.out.println("   !!! Виявлено блокування для " + url + ". Чекаю "
                                + (BLOCK_WAIT_MS / 60000) + " хв (" + blockRetries + "/" + MAX_BLOCK_RETRIES + ")...");
                        reportBlock();
                        attempt--;
                        continue;
                    }
                    return response.body();
                }

                if (response.statusCode() == 403) {
                    if (blockRetries >= MAX_BLOCK_RETRIES) {
                        throw new RuntimeException("HTTP 403 навіть після довгих очікувань: " + url);
                    }
                    blockRetries++;
                    System.out.println("   !!! HTTP 403 для " + url + ". Чекаю "
                            + (BLOCK_WAIT_MS / 60000) + " хв (" + blockRetries + "/" + MAX_BLOCK_RETRIES + ")...");
                    reportBlock();
                    attempt--;
                    continue;
                }

                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    long backoffMs = resolveBackoff(response, attempt);
                    System.out.println("   HTTP " + response.statusCode() + " for " + url
                            + " -- retry " + attempt + "/" + MAX_RETRIES + " after " + backoffMs + "ms");
                    Thread.sleep(backoffMs);
                    lastError = new RuntimeException("HTTP " + response.statusCode() + " for " + url);
                    continue;
                }

                if (response.statusCode() == 404) {
                    return "";
                }

                throw new RuntimeException("HTTP " + response.statusCode() + " for " + url);

            } catch (java.io.IOException e) {
                lastError = e;
                Thread.sleep(1000L * attempt);
            }
        }

        throw lastError != null ? lastError : new RuntimeException("Failed to fetch " + url);
    }

    private void awaitIfBlocked() throws InterruptedException {
        long wait = blockedUntil.get() - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait + ThreadLocalRandom.current().nextLong(0, 5000));
        }
    }

    private void reportBlock() {
        long candidate = System.currentTimeMillis() + BLOCK_WAIT_MS;
        blockedUntil.updateAndGet(prev -> Math.max(prev, candidate));
    }

    private boolean isBlockedPage(String body) {
        if (body == null) return false;
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("just a moment")
                || lower.contains("checking your browser")
                || lower.contains("temporarily unavailable")
                || lower.contains("cf-challenge")
                || lower.contains("captcha")
                || lower.contains("attention required");
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