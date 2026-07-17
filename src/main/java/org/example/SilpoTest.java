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

    private static final HttpClient client = HttpClient.newHttpClient();
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // Базові посилання
    private static final String BASE_IMAGE_URL = "https://images.silpo.ua/products/1600x1600/webp/";
    private static final String BASE_PRODUCT_URL = "https://silpo.ua/product/";
    private static final String BASE_API_DETAILS_URL = "https://sf-ecom-api.silpo.ua/v1/uk/branches/00000000-0000-0000-0000-000000000000/products/";

    public static void main(String[] args) {
        System.out.println(">>> Стартуємо МЕГА-парсинг Сільпо (з деталями)...");

        JsonArray allCleanProducts = new JsonArray();
        int limit = 100;
        int offset = 0;
        boolean hasMore = true;

        try {
            // ЕТАП 1: Збираємо всі товари з каталогу
            while (hasMore) {
                String catalogUrl = "https://sf-ecom-api.silpo.ua/v1/uk/branches/00000000-0000-0000-0000-000000000000/products?limit=" + limit + "&offset=" + offset + "&deliveryType=DeliveryHome&category=rom-4468&includeChildCategories=true&sortBy=popularity&sortDirection=desc&inStock=false";

                String responseBody = sendGetRequest(catalogUrl);

                if (responseBody != null) {
                    JsonObject rootObj = JsonParser.parseString(responseBody).getAsJsonObject();
                    JsonArray items = rootObj.getAsJsonArray("items");

                    int fetchedSize = items.size();
                    System.out.println("Завантажено з каталогу сторінку (відступ " + offset + "): " + fetchedSize + " шт.");

                    for (JsonElement element : items) {
                        JsonObject item = element.getAsJsonObject();
                        JsonObject cleanItem = new JsonObject();

                        String title = getStringOrNull(item, "title");
                        String brand = getStringOrNull(item, "brandTitle");
                        String volume = getStringOrNull(item, "displayRatio");
                        Double price = getDoubleOrNull(item, "price");
                        Double oldPrice = getDoubleOrNull(item, "oldPrice");
                        String icon = getStringOrNull(item, "icon");
                        String slug = getStringOrNull(item, "slug");

                        cleanItem.addProperty("name", title);
                        cleanItem.addProperty("brand", brand);
                        cleanItem.addProperty("volume", volume);
                        cleanItem.addProperty("price", price);
                        if (oldPrice != null) cleanItem.addProperty("oldPrice", oldPrice);
                        if (icon != null) cleanItem.addProperty("imageUrl", BASE_IMAGE_URL + icon);
                        if (slug != null) cleanItem.addProperty("productUrl", BASE_PRODUCT_URL + slug);

                        // ЕТАП 2: Робимо додатковий запит за характеристиками (якщо є slug)
                        if (slug != null && !slug.isEmpty()) {
                            fetchAndAddDetails(slug, cleanItem);
                            Thread.sleep(100); // Пауза 0.1 сек, щоб сервер нас не заблокував
                        }

                        allCleanProducts.add(cleanItem);
                    }

                    if (fetchedSize < limit) {
                        hasMore = false; // Кінець каталогу
                    } else {
                        offset += limit;
                    }
                } else {
                    break;
                }
            }

            // Збереження результату
            String outputFileName = "silpo_final_database.json";
            try (FileWriter writer = new FileWriter(outputFileName)) {
                gson.toJson(allCleanProducts, writer);
                System.out.println("-------------------------------------------------");
                System.out.println(">>> УСПІХ! Всього зібрано товарів: " + allCleanProducts.size());
                System.out.println(">>> Усі дані (з країною та спиртом) збережено у: " + outputFileName);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Метод для витягування характеристик (Країна, Спирт, Витримка)
    private static void fetchAndAddDetails(String slug, JsonObject cleanItem) {
        String detailsUrl = BASE_API_DETAILS_URL + slug;
        String responseBody = sendGetRequest(detailsUrl);

        if (responseBody == null) return;

        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray groups = root.getAsJsonArray("attributeGroups");

            if (groups != null) {
                for (JsonElement groupEl : groups) {
                    JsonObject group = groupEl.getAsJsonObject();

                    // Шукаємо блок "Загальна інформація"
                    if ("generalInfo".equals(getStringOrNull(group, "key"))) {
                        JsonArray attributes = group.getAsJsonArray("attributes");

                        for (JsonElement attrEl : attributes) {
                            JsonObject attr = attrEl.getAsJsonObject();
                            JsonObject attrKeyObj = attr.getAsJsonObject("attribute");
                            JsonObject valueObj = attr.getAsJsonObject("value");

                            if (attrKeyObj != null && valueObj != null) {
                                String key = getStringOrNull(attrKeyObj, "key");
                                String valueTitle = getStringOrNull(valueObj, "title");

                                if (valueTitle != null) {
                                    if ("country".equals(key)) {
                                        cleanItem.addProperty("country", valueTitle);
                                    } else if ("alcoholcontent".equals(key)) {
                                        cleanItem.addProperty("alcohol", valueTitle + "%");
                                    } else if ("strokvytrymky".equals(key)) {
                                        cleanItem.addProperty("aging", valueTitle);
                                    } else if ("color".equals(key)) {
                                        cleanItem.addProperty("color", valueTitle);
                                    }
                                }
                            }
                        }
                        break; // Знайшли потрібний блок, далі не шукаємо
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Помилка парсингу деталей для " + slug);
        }
    }

    // Універсальний метод для HTTP запитів з правильними заголовками
    private static String sendGetRequest(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0 Safari/537.36")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Referer", "https://silpo.ua/")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            } else {
                System.err.println("Помилка " + response.statusCode() + " на URL: " + url);
            }
        } catch (Exception e) {
            System.err.println("Помилка з'єднання: " + e.getMessage());
        }
        return null;
    }

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