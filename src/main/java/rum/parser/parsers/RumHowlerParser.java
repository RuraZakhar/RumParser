package rum.parser.parsers;

import rum.parser.model.RumProduct;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import rum.parser.util.RumNameMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class RumHowlerParser implements RumParser {

    private static final String BASE_URL = "https://therumhowlerblog.com/rum-reviews/";
    private static final String PROVIDER = "The Rum Howler Blog";
    private static final double FUZZY_THRESHOLD = 0.90;
    private static final int MAX_FETCH_RETRIES = 3;
    private static final Pattern RATING_PATTERN = Pattern.compile("\\b([789]\\d(\\.\\d)?|100)\\b");

    @Override
    public void parse(Set<RumProduct> rumSet) {
        System.out.println("[1/3] Scanning first source (The Rum Howler Blog)...");

        try {
            Document mainPage = fetchWithRetry(() -> Jsoup.connect(BASE_URL)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .get());

            Elements links = mainPage.select("article a[href*=/rum-reviews/]");
            if (links.isEmpty()) {
                links = mainPage.select("a[href*=/rum-reviews/]");
            }

            System.out.println("Found " + links.size() + " potential rum links. Starting download...");

            ExecutorService executor = Executors.newFixedThreadPool(16);
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            AtomicInteger count = new AtomicInteger(0);

            for (Element link : links) {
                String rumUrl = link.attr("abs:href");
                String rawName = link.text();

                if (rawName.trim().isEmpty() || rumUrl.equals(BASE_URL)) continue;

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        Document itemPage = fetchWithRetry(() -> Jsoup.connect(rumUrl)
                                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                .timeout(5000)
                                .get());

                        String pageText = itemPage.text();
                        String ratingStr = extractRatingFromPage(pageText);

                        if (ratingStr != null) {
                            double ratingValue = Double.parseDouble(ratingStr) / 10.0;

                            RumProduct rum = new RumProduct();
                            rum.setName(rawName.trim());
                            rum.setProductUrl(rumUrl);
                            rum.addSourceUrl("The Rum Howler Blog", rumUrl);
                            rum.getRatings().add(new RumProduct.Rating(PROVIDER, ratingValue));

                            synchronized (rumSet) {
                                if (mergeIntoSet(rumSet, rum)) {
                                    count.incrementAndGet();
                                }
                            }
                        }

                    } catch (Exception ignored) {
                    }
                }, executor);

                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            executor.shutdown();

            System.out.println("Finished Howler Blog. New items added: " + count.get());

        } catch (Exception e) {
            System.err.println("Critical error in RumHowlerParser: " + e.getMessage());
        }
    }

    private boolean mergeIntoSet(Set<RumProduct> rumSet, RumProduct incomingRum) {
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

    private String extractRatingFromPage(String text) {
        Matcher matcher = RATING_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private Document fetchWithRetry(Callable<Document> fetcher) throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_FETCH_RETRIES; attempt++) {
            try {
                return fetcher.call();
            } catch (Exception e) {
                lastError = e;
                if (attempt < MAX_FETCH_RETRIES) {
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException ignored) {}
                }
            }
        }
        throw lastError;
    }
}