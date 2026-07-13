package org.example;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.Set;

public class RumHowlerParser implements RumParser {

    private static final String CATALOG_URL = "https://therumhowlerblog.com/rum-reviews/";
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";

    @Override
    public void parse(Set<RumProduct> rumSet) {System.out.println("\n[1/2] Scanning catalog page: " + CATALOG_URL);

        try {
            Document catalogDoc = Jsoup.connect(CATALOG_URL)
                    .userAgent(USER_AGENT)
                    .timeout(15000)
                    .get();

            Elements reviewLinks = catalogDoc.select("ul li a");
            System.out.println("Found potential links count: " + reviewLinks.size());

            int count = 0;
            for (Element link : reviewLinks) {
                String fullText = link.parent().text();
                String productUrl = link.attr("abs:href");

                if (productUrl.contains("/rum-reviews/") && fullText.contains("(") && fullText.contains(")")) {
                    try {
                        String cleanName = fullText.substring(0, fullText.lastIndexOf("(")).trim();
                        String ratingStr = fullText.substring(fullText.lastIndexOf("(") + 1, fullText.lastIndexOf(")")).trim();

                        double ratingValue = Double.parseDouble(ratingStr) / 10.0;

                        RumProduct rum = new RumProduct();
                        rum.setName(cleanName);
                        rum.setProductUrl(productUrl);
                        rum.setCategory("Rum");
                        rum.getRatings().add(new RumProduct.Rating("The Rum Howler Blog", ratingValue));

                        System.out.println("Parsing product page [" + (count + 1) + "]: " + cleanName);

                        Document innerDoc = Jsoup.connect(productUrl)
                                .userAgent(USER_AGENT)
                                .timeout(10000)
                                .get();

                        Element imgElement = innerDoc.select(".entry-content img, article img").first();
                        if (imgElement != null) {
                            rum.setImgUrl(imgElement.attr("abs:src"));
                        }

                        Element contentElement = innerDoc.select(".entry-content, article").first();
                        if (contentElement != null) {
                            rum.setDescription(contentElement.text().trim());
                        }

                        boolean isAdded = rumSet.add(rum);
                        if (isAdded) {
                            count++;
                        } else {
                            System.out.println("⚠️ Duplicate ignored: " + cleanName);
                        }

                        if (count >= 350) {
                            System.out.println("Target count reached for Rum Howler Blog.");
                            break;
                        }

                        Thread.sleep(150);

                    } catch (Exception e) {
                        System.err.println("Error processing rum item: " + fullText + " | " + e.getMessage());
                    }
                }
            }

            System.out.println("Successfully gathered from the first source: " + count + " unique products.");

        } catch (IOException e) {
            System.err.println("Failed to connect to the catalog page: " + e.getMessage());
        }

    }

}



