package org.example;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class RumHowlerParser implements RumParser {

    private static final String BASE_URL = "https://therumhowlerblog.com/rum-reviews/";
    private static final int DEFAULT_MAX_PRODUCTS = 500;

    @Override
    public void parse(Set<RumProduct> rumSet) {
        System.out.println("[1/2] Scanning first source (The Rum Howler Blog)...");

        int maxProducts = getPositiveEnvironmentInt("MAX_HOWLER_PRODUCTS", DEFAULT_MAX_PRODUCTS);

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
                    if (count.get() >= maxProducts) return;

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

                            RumProduct.Rating rating = new RumProduct.Rating("The Rum Howler Blog", ratingValue);
                            rum.getRatings().add(rating);

                            synchronized (rumSet) {
                                if (count.get() < maxProducts && rumSet.add(rum)) {
                                    count.incrementAndGet();
                                }
                            }
                        }

                    } catch (Exception e) {}
                }, executor);

                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            executor.shutdown();

            System.out.println("Finished Howler Blog. Total collected: " + count.get());

        } catch (Exception e) {
            System.err.println("Critical error in RumHowlerParser: " + e.getMessage());
        }
    }

    private String extractRatingFromPage(String text) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\b([789]\\d(\\.\\d)?|100)\\b");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private int getPositiveEnvironmentInt(String variableName, int defaultValue) {
        String value = System.getenv(variableName);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsedValue = Integer.parseInt(value);
            return parsedValue > 0 ? parsedValue : defaultValue;
        } catch (NumberFormatException e) {
            System.err.println("Ignoring invalid " + variableName + " value: " + value);
            return defaultValue;
        }
    }
}
