package beer.parser.parsers;

import beer.parser.model.BeerProduct;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class UntappdBeerParser implements BeerParser {

    private static final String FIRECRAWL_API_KEY = "fc-3ea567c377f949acb5421c6484be277d";
    private static final String FIRECRAWL_ENDPOINT = "https://api.firecrawl.dev/v1/scrape";

    private static final String BREWERIES_FILE = "src/main/resources/beer-breweries.txt";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Override
    public List<BeerProduct> parse(List<BeerProduct> existingCache) {
        List<BeerProduct> parsedBeers = java.util.Collections.synchronizedList(new ArrayList<>());

        List<beer.parser.model.Brewery> breweries = BreweryLoader.loadBreweries(BREWERIES_FILE);

        if (breweries.isEmpty()) {
            System.out.println("❌ [Untappd] Список броварень порожній або файл " + BREWERIES_FILE + " не знайдено. Парсинг скасовано.");
            return new ArrayList<>();
        }

        int maxPages = 2;
        System.out.println("🍺 [Untappd] Запуск багатопотокового збору (5 потоків) для " + breweries.size() + " броварень...");

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(5);
        List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();

        for (beer.parser.model.Brewery brewery : breweries) {
            String breweryName = brewery.getName();
            String baseUrl = brewery.getUntappdUrl();

            java.util.concurrent.CompletableFuture<Void> future = java.util.concurrent.CompletableFuture.runAsync(() -> {
                System.out.println("   [" + breweryName + "] Починаємо збір...");

                for (int page = 1; page <= maxPages; page++) {
                    String targetUrl = baseUrl + "?sort=highest_rated";
                    if (page > 1) {
                        targetUrl += "&page=" + page;
                    }

                    System.out.println("   [" + breweryName + "] Запит сторінки " + page + "...");
                    boolean success = fetchFromFirecrawl(targetUrl, breweryName, parsedBeers);

                    if (!success) {
                        System.out.println("   [" + breweryName + "] Немає даних на сторінці " + page + ". Завершуємо збір для цієї броварні.");
                        break;
                    }
                }
            }, executor);

            futures.add(future);
        }

        try {
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
        }

        System.out.println("✅ [Untappd] Збір завершено. Отримано " + parsedBeers.size() + " унікальних позицій.");
        return new ArrayList<>(parsedBeers);
    }

    private boolean fetchFromFirecrawl(String targetUrl, String breweryName, List<BeerProduct> parsedBeers) {
        try {
            String jsonPayload = String.format("""
            {
              "url": "%s",
              "formats": ["extract"],
              "extract": {
                "prompt": "Extract the list of beers from this Untappd brewery page. For each beer, get the name, style, ABV, IBU, Untappd rating, and the full URL to the beer detail page. If any value (like IBU, ABV, rating, or URL) is missing, return null for that field.",
                "schema": {
                  "type": "object",
                  "properties": {
                    "beers": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "name": {"type": "string"},
                          "style": {"type": ["string", "null"]},
                          "abv": {"type": ["number", "null"]},
                          "ibu": {"type": ["number", "null"]},
                          "untappdRating": {"type": ["number", "null"]},
                          "untappdUrl": {"type": ["string", "null"]}
                        },
                        "required": ["name"]
                      }
                    }
                  }
                }
              }
            }
            """, targetUrl);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(FIRECRAWL_ENDPOINT))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + FIRECRAWL_API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.out.println("   [Firecrawl Error] Статус: " + response.statusCode());
                return false;
            }

            JsonObject rootObj = JsonParser.parseString(response.body()).getAsJsonObject();

            if (rootObj.has("success") && rootObj.get("success").getAsBoolean()) {
                JsonObject data = rootObj.getAsJsonObject("data");
                JsonObject extract = data.getAsJsonObject("extract");

                if (extract.has("beers") && extract.get("beers").isJsonArray()) {
                    JsonArray beersArray = extract.getAsJsonArray("beers");

                    if (beersArray.isEmpty()) {
                        System.out.println("   [Untappd] Пива на сторінці не знайдено.");
                        return false;
                    }

                    for (JsonElement el : beersArray) {
                        JsonObject beerJson = el.getAsJsonObject();
                        BeerProduct beer = new BeerProduct();

                        beer.setBrand(breweryName);

                        if (beerJson.has("name") && !beerJson.get("name").isJsonNull()) {
                            beer.setName(beerJson.get("name").getAsString());
                            beer.setCleanName(beer.getName().toLowerCase());
                        }

                        if (beerJson.has("style") && !beerJson.get("style").isJsonNull()) {
                            beer.setStyle(beerJson.get("style").getAsString());
                        }

                        if (beerJson.has("abv") && !beerJson.get("abv").isJsonNull()) {
                            beer.setAbv(beerJson.get("abv").getAsDouble());
                        }

                        if (beerJson.has("ibu") && !beerJson.get("ibu").isJsonNull()) {
                            beer.setIbu(beerJson.get("ibu").getAsInt());
                        }

                        if (beerJson.has("untappdUrl") && !beerJson.get("untappdUrl").isJsonNull()) {
                            String beerUrl = beerJson.get("untappdUrl").getAsString().trim();
                            if (!beerUrl.isEmpty()) {
                                if (!beerUrl.startsWith("http")) {
                                    beerUrl = "https://untappd.com" + (beerUrl.startsWith("/") ? "" : "/") + beerUrl;
                                }
                                beer.setUntappdUrl(beerUrl);
                            }
                        }

                        if (beerJson.has("untappdRating") && !beerJson.get("untappdRating").isJsonNull()) {
                            beer.setUntappdRating(beerJson.get("untappdRating").getAsDouble());
                            parsedBeers.add(beer);

                            String abvStr = beer.getAbv() != null ? beer.getAbv() + "%" : "-";
                            String ibuStr = beer.getIbu() != null ? String.valueOf(beer.getIbu()) : "-";
                            System.out.println("      -> " + beer.getName() + " (" + beer.getUntappdRating() + " ★, ABV: " + abvStr + ", IBU: " + ibuStr + ") | URL: " + beer.getUntappdUrl());
                        }
                    }
                    return true;
                }
            }
        } catch (Exception e) {
            System.out.println("   [Untappd] Помилка запиту до Firecrawl: " + e.getMessage());
        }
        return false;
    }
}