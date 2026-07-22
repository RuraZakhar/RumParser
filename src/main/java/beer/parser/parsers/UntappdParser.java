package beer.parser.parsers;

import beer.parser.model.BeerProduct;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UntappdParser {

    private static final String API_KEY = System.getenv("FIRECRAWL_API_KEY_1");
    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public static void main(String[] args) {
        System.out.println(">>> Відправляємо запит до Untappd через Firecrawl...");
        String testUrl = "https://untappd.com/SideProject/beer?sort=highest_rated";
        String brandName = "Side Project Brewing";

        List<BeerProduct> beers = fetchTopBeers(testUrl, brandName);

        System.out.println("\n=== РЕЗУЛЬТАТ ПАРСИНГУ ===");
        for (BeerProduct beer : beers) {
            System.out.println("Назва: " + beer.getName());
            System.out.println("Бренд: " + beer.getBrand());
            System.out.println("Сорт: " + beer.getStyle());
            System.out.println("Рейтинг: " + beer.getUntappdRating());
            System.out.println("Міцність (ABV): " + beer.getAbv() + "%");
            System.out.println("Гіркота (IBU): " + beer.getIbu());
            System.out.println("Фото: " + beer.getImgUrl());
            System.out.println("Посилання: " + beer.getUntappdUrl());
            System.out.println("------------------------------------------------");
        }
    }

    public static List<BeerProduct> fetchTopBeers(String untappdUrl, String brandName) {
        List<BeerProduct> topBeers = new ArrayList<>();
        try {
            String jsonBody = "{\n" +
                    "  \"url\": \"" + untappdUrl + "\",\n" +
                    "  \"formats\": [\"markdown\"]\n" +
                    "}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.firecrawl.dev/v1/scrape"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject rootObj = JsonParser.parseString(response.body()).getAsJsonObject();
                if (rootObj.has("data") && rootObj.getAsJsonObject("data").has("markdown")) {
                    String markdown = rootObj.getAsJsonObject("data").get("markdown").getAsString();
                    topBeers = parseMarkdownToBeers(markdown, brandName);
                }
            } else {
                System.err.println("Помилка Firecrawl: " + response.body());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return topBeers;
    }

    private static List<BeerProduct> parseMarkdownToBeers(String markdown, String brandName) {
        List<BeerProduct> beers = new ArrayList<>();

        // Магічний Regex, який витягує всі поля прямо з тексту Untappd
        String regex = "\\[!\\[.*?\\]\\((.*?)\\)\\]\\(.*?\\)\\s+" +
                "\\[(.*?)\\]\\((.*?)\\)\\s+" +
                "(.*?)\\s+" +
                "[\\s\\S]*?" +
                "([\\d.]+|N/A)% ABV\\s+" +
                "([\\d.]+|N/A) IBU\\s+" +
                "\\(([\\d.]+)\\)";

        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(markdown);

        int count = 0;
        while (matcher.find() && count < 10) {
            BeerProduct beer = new BeerProduct();

            beer.setBrand(brandName);
            beer.setImgUrl(matcher.group(1));
            beer.setName(matcher.group(2));
            beer.setUntappdUrl(matcher.group(3));
            beer.setStyle(matcher.group(4).trim());

            String abvStr = matcher.group(5);
            if (!abvStr.equals("N/A")) {
                beer.setAbv(Double.parseDouble(abvStr));
            }

            String ibuStr = matcher.group(6);
            if (!ibuStr.equals("N/A")) {
                beer.setIbu((int) Math.round(Double.parseDouble(ibuStr)));
            }

            beer.setUntappdRating(Double.parseDouble(matcher.group(7)));

            beers.add(beer);
            count++;
        }

        return beers;
    }
}