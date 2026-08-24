package wine.parser.parsers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class OkwineParser {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    public static void parse(String[] args) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        int page = 1;
        boolean hasMore = true;

        while (hasMore && page <= 3) { // Для тесту перевіримо 3 сторінки
            String url = "https://product.okwine.ua/api/v1/filter/products"
                    + "?page=" + page
                    + "&category=61c460bf1fda1bf332a33bfd"
                    + "&city=61e159f3ab2700007200435c"
                    + "&lang=ua";

            System.out.println("Завантаження сторінки " + page + "...");

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

                    // Зазвичай масив товарів лежить у полі "data" або "products"
                    // (Перевірте точну назву поля у вашій відповіді в Postman/браузері)
                    JsonArray products = json.has("data") ? json.getAsJsonArray("data") : new JsonArray();

                    if (products.isEmpty()) {
                        hasMore = false;
                        System.out.println("Товари закінчилися.");
                        break;
                    }

                    for (JsonElement el : products) {
                        JsonObject product = el.getAsJsonObject();

                        String name = product.has("name") ? product.get("name").getAsString() : "Н/Д";
                        double price = product.has("price") ? product.get("price").getAsDouble() : 0.0;
                        String code = product.has("code") ? product.get("code").getAsString() : "Н/Д";

                        System.out.println("  - [" + code + "] " + name + " | Ціна: " + price + " грн");
                    }

                    page++;
                    Thread.sleep(500); // Невелика пауза між запитами
                } else {
                    System.err.println("Помилка HTTP: " + response.statusCode());
                    break;
                }
            } catch (Exception e) {
                System.err.println("Помилка запиту: " + e.getMessage());
                break;
            }
        }
    }
}