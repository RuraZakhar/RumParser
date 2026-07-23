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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;


public class RumHowlerParser implements RumParser {

    private static final String BASE_URL = "https://therumhowlerblog.com/rum-reviews/";
    private static final String PROVIDER = "The Rum Howler Blog";
    private static final double FUZZY_THRESHOLD = 0.90;

    @Override
    public void parse(Set<RumProduct> rumSet) {
        System.out.println("[1/3] Scanning first source (The Rum Howler Blog)...");

        try {
            Document mainPage = Jsoup.connect(BASE_URL)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .get();

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
                        Document itemPage = Jsoup.connect(rumUrl)
                                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                .timeout(5000)
                                .get();

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
        // 1. Точний збіг нормалізованої назви
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
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\b([789]\\d(\\.\\d)?|100)\\b");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}