package org.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.FileWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class SilpoTest {

    public static void main(String[] args) {
        System.out.println(">>> Стартуємо повний парсинг Сільпо...");

        HttpClient client = HttpClient.newHttpClient();
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        // Базові частини посилань
        String baseImageUrl = "https://images.silpo.ua/products/1600x1600/webp/";
        String baseProductUrl = "https://silpo.ua/product/";

        JsonArray allCleanProducts = new JsonArray(); // Тут зберемо взагалі ВСІ товари

        int limit = 100; // Максимум, який дозволяє сервер за один раз
        int offset = 0;
        boolean hasMore = true;

        try {
            while (hasMore) {
                // Динамічно підставляємо offset у посилання
                String apiUrl = "https://sf-ecom-api.silpo.ua/v1/uk/branches/00000000-0000-0000-0000-000000000000/products?limit=" + limit + "&offset=" + offset + "&deliveryType=DeliveryHome&category=rom-4468&includeChildCategories=true&sortBy=popularity&sortDirection=desc&inStock=false";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Referer", "https://silpo.ua/")
                        .GET()
                        .build();

                System.out.println("Завантажуємо товари з " + offset + " по " + (offset + limit) + "...");
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonObject rootObj = JsonParser.parseString(response.body()).getAsJsonObject();
                    JsonArray items = rootObj.getAsJsonArray("items");

                    int fetchedSize = items.size();
                    System.out.println("   Отримано: " + fetchedSize + " шт.");

                    // Парсимо поточну порцію
                    for (JsonElement element : items) {
                        JsonObject item = element.getAsJsonObject();
                        JsonObject cleanItem = new JsonObject();

                        // 1. Витягуємо текстові та числові дані
                        String title = getStringOrNull(item, "title");
                        String brand = getStringOrNull(item, "brandTitle");
                        String volume = getStringOrNull(item, "displayRatio");
                        Double price = getDoubleOrNull(item, "price");
                        Double oldPrice = getDoubleOrNull(item, "oldPrice");
                        Double rating = getDoubleOrNull(item, "guestProductRating");

                        // 2. Формуємо посилання на картинку
                        String icon = getStringOrNull(item, "icon");
                        String imageUrl = (icon != null && !icon.isEmpty()) ? baseImageUrl + icon : null;

                        // 3. Формуємо посилання на сторінку товару (через slug)
                        String slug = getStringOrNull(item, "slug");
                        String productUrl = (slug != null && !slug.isEmpty()) ? baseProductUrl + slug : null;

                        // 4. Записуємо у наш чистий об'єкт
                        cleanItem.addProperty("name", title);
                        cleanItem.addProperty("brand", brand);
                        cleanItem.addProperty("volume", volume);
                        cleanItem.addProperty("price", price);

                        if (oldPrice != null) cleanItem.addProperty("oldPrice", oldPrice);
                        if (rating != null) cleanItem.addProperty("silpoRating", rating);
                        if (imageUrl != null) cleanItem.addProperty("imageUrl", imageUrl);
                        if (productUrl != null) cleanItem.addProperty("productUrl", productUrl);

                        allCleanProducts.add(cleanItem); // Додаємо до загального списку
                    }

                    // Перевіряємо, чи є ще сторінки
                    if (fetchedSize < limit) {
                        hasMore = false; // Якщо прийшло менше 100, значить ми дійшли до кінця каталогу
                    } else {
                        offset += limit; // Збільшуємо відступ для наступного кроку (йдемо на наступну сторінку)
                    }

                } else {
                    System.err.println("Помилка API! Код: " + response.statusCode());
                    break; // Зупиняємо цикл при помилці
                }
            }

            // Коли цикл закінчився, зберігаємо ВЕЛИКИЙ масив у файл
            String outputFileName = "silpo_test_result.json";
            try (FileWriter writer = new FileWriter(outputFileName)) {
                gson.toJson(allCleanProducts, writer);
                System.out.println("-------------------------------------------------");
                System.out.println(">>> УСПІХ! Всього зібрано товарів: " + allCleanProducts.size());
                System.out.println(">>> Усі дані успішно збережено у файл: " + outputFileName);
            }

        } catch (Exception e) {
            System.err.println("Сталася помилка: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Допоміжні методи для безпечного витягування (щоб уникнути NullPointerException)
    private static String getStringOrNull(JsonObject obj, String field) {
        if (obj.has(field) && !obj.get(field).isJsonNull()) {
            return obj.get(field).getAsString();
        }
        return null;
    }

    private static Double getDoubleOrNull(JsonObject obj, String field) {
        if (obj.has(field) && !obj.get(field).isJsonNull()) {
            return obj.get(field).getAsDouble();
        }
        return null;
    }
}