package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

public class RumRatingsParser implements RumParser {

    private static final String FIRECRAWL_API_KEY = "fc-e61c2ab5344641e6952f05f314cba080";
    private static final String FIRECRAWL_SCRAPE_URL = "https://api.firecrawl.dev/v1/scrape";
    private static final String BASE_TARGET_URL = "https://rumratings.com/rum";

    @Override
    public void parse(Set<RumProduct> rumSet) {
        System.out.println("\n[2/2] Scanning RumRatings via Firecrawl API...");

        if (FIRECRAWL_API_KEY == null || FIRECRAWL_API_KEY.isBlank()) {
            System.err.println("ERROR: FIRECRAWL_API_KEY environment variable is not set.");
            return;
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(120))
                .build();

        int totalIntegrated = 0;
        int maxPages = 3;

        for (int page = 1; page <= maxPages; page++) {
            if (page > 1 && !waitBeforeNextRequest()) {
                return;
            }

            System.out.println("\n>>> Fetching RumRatings page " + page + "...");

            try {
                JsonObject extractedData = scrapePage(client, page);
                if (extractedData == null) {
                    continue;
                }

                JsonArray rumsArray = getArray(extractedData, "rums");
                if (rumsArray == null || rumsArray.isEmpty()) {
                    System.out.println("No rum products returned. Stopping.");
                    break;
                }

                int newItemsThisPage = 0;
                for (JsonElement element : rumsArray) {
                    if (!element.isJsonObject()) {
                        continue;
                    }

                    RumProduct rum = toRumProduct(element.getAsJsonObject());
                    if (rum == null) {
                        continue;
                    }

                    if (mergeIntoSet(rumSet, rum)) {
                        newItemsThisPage++;
                        totalIntegrated++;
                    }
                }

                System.out.println("Page " + page + " finished. Added "
                        + newItemsThisPage + " new unique rums.");

            } catch (Exception e) {
                System.err.println("Error parsing RumRatings page " + page + ": " + e.getMessage());
            }
        }

        System.out.println("Finished RumRatings. Total integrated: " + totalIntegrated);
    }

    private JsonObject scrapePage(HttpClient client, int page) throws Exception {
        String currentUrl = BASE_TARGET_URL + "?page=" + page;
        JsonObject requestBody = buildRequestBody(currentUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(FIRECRAWL_SCRAPE_URL))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + FIRECRAWL_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build();

        int maxRetries = 3;
        int attempt = 0;

        while (attempt < maxRetries) {
            try {
                attempt++;

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (responseJson.has("success") && !responseJson.get("success").getAsBoolean()) {
                        System.err.println("Firecrawl returned an unsuccessful response: " + response.body());
                        return null;
                    }

                    JsonObject data = getObject(responseJson, "data");
                    JsonObject extractedData = data == null ? null : getObject(data, "json");

                    if (extractedData == null) {
                        System.err.println("Firecrawl response has no data.json: " + response.body());
                    }

                    return extractedData;

                } else {
                    System.out.println("Помилка API (статус " + response.statusCode() + ") на сторінці " + page + ". Спроба " + attempt + " з " + maxRetries);
                    if (attempt < maxRetries) Thread.sleep(5000);
                }

            } catch (java.net.http.HttpTimeoutException e) {
                System.out.println("⏳ Таймаут на сторінці " + page + ". Спроба " + attempt + " з " + maxRetries);
                if (attempt < maxRetries) Thread.sleep(5000);

            } catch (Exception e) {
                System.out.println("Критична помилка на сторінці " + page + ": " + e.getMessage());
                break;
            }
        }

        return null;
    }

    private JsonObject buildRequestBody(String url) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("url", url);
        requestBody.addProperty("waitFor", 2000);
        requestBody.addProperty("timeout", 30000);
        requestBody.addProperty("onlyMainContent", true);

        JsonArray formats = new JsonArray();
        formats.add("json");
        requestBody.add("formats", formats);

        JsonObject jsonOptions = new JsonObject();
        jsonOptions.addProperty("prompt", """
                Extract every rum product visible on this exact RumRatings listing page.
                For each product return every available field from this list: exact name,
                description, brand (company), rating on the 0-10 scale, product URL, image URL,
                rum type, category, region/country of origin, ABV percentage, age in years (years aged),
                year distilled, raw material, process, distillation method (e.g., Column Still),
                whether it is women led (boolean true/false), volume as text, and product code.
                If a field is missing, return null. Do not invent values.
                """);
        jsonOptions.add("schema", createRumSchema());
        requestBody.add("jsonOptions", jsonOptions);

        return requestBody;
    }

    private JsonObject createRumSchema() {
        JsonObject itemProperties = new JsonObject();
        itemProperties.add("name", stringSchema());
        itemProperties.add("description", stringSchema());
        itemProperties.add("brand", stringSchema());
        itemProperties.add("rating", numberSchema());
        itemProperties.add("imgUrl", stringSchema());
        itemProperties.add("productUrl", stringSchema());
        itemProperties.add("type", stringSchema());
        itemProperties.add("category", stringSchema());
        itemProperties.add("region", stringSchema());
        itemProperties.add("abv", numberSchema());
        itemProperties.add("age", numberSchema());
        itemProperties.add("volumeWeight", stringSchema());
        itemProperties.add("code", stringSchema());
        itemProperties.add("yearDistilled", numberSchema());
        itemProperties.add("rawMaterial", stringSchema());
        itemProperties.add("process", stringSchema());
        itemProperties.add("distillationMethod", stringSchema());

        JsonObject booleanSchema = new JsonObject();
        booleanSchema.addProperty("type", "boolean");
        itemProperties.add("womenLed", booleanSchema);

        JsonObject item = new JsonObject();
        item.addProperty("type", "object");
        item.add("properties", itemProperties);

        JsonArray requiredItemFields = new JsonArray();
        requiredItemFields.add("name");
        item.add("required", requiredItemFields);

        JsonObject rums = new JsonObject();
        rums.addProperty("type", "array");
        rums.add("items", item);

        JsonObject properties = new JsonObject();
        properties.add("rums", rums);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);

        JsonArray requiredRootFields = new JsonArray();
        requiredRootFields.add("rums");
        schema.add("required", requiredRootFields);

        itemProperties.add("volumeWeight", stringSchema());
        itemProperties.add("code", stringSchema());

        return schema;
    }

    private RumProduct toRumProduct(JsonObject rumJson) {
        String name = getString(rumJson, "name");
        if (name.isBlank()) {
            return null;
        }

        RumProduct rum = new RumProduct();
        rum.setName(name);
        rum.setDescription(getStringOrNull(rumJson, "description"));
        rum.setBrand(getStringOrNull(rumJson, "brand"));
        rum.setType(getStringOrNull(rumJson, "type"));
        rum.setCategory(getStringOrNull(rumJson, "category"));
        rum.setRegion(getStringOrNull(rumJson, "region"));
        rum.setAbv(getDoubleOrNull(rumJson, "abv"));
        rum.setAge(getDoubleOrNull(rumJson, "age"));
        rum.setVolumeWeight(getStringOrNull(rumJson, "volumeWeight"));
        rum.setCode(getStringOrNull(rumJson, "code"));
        rum.setImgUrl(getStringOrNull(rumJson, "imgUrl"));
        rum.setProductUrl(getStringOrNull(rumJson, "productUrl"));

        rum.setYearDistilled(getIntegerOrNull(rumJson, "yearDistilled"));
        rum.setRawMaterial(getStringOrNull(rumJson, "rawMaterial"));
        rum.setProcess(getStringOrNull(rumJson, "process"));
        rum.setDistillationMethod(getStringOrNull(rumJson, "distillationMethod"));

        if (rumJson.has("womenLed") && !rumJson.get("womenLed").isJsonNull()) {
            rum.setWomenLed(rumJson.get("womenLed").getAsBoolean());
        } else {
            rum.setWomenLed(null);
        }

        Double rating = getDoubleOrNull(rumJson, "rating");
        if (rating != null && rating >= 0.0 && rating <= 10.0) {
            rum.getRatings().add(new RumProduct.Rating("RumRatings", rating));
        }
        rum.enrichDerivedFields();

        return rum;
    }

    private boolean mergeIntoSet(Set<RumProduct> rumSet, RumProduct incomingRum) {
        if (rumSet.add(incomingRum)) {
            return true;
        }

        for (RumProduct existingRum : rumSet) {
            if (!existingRum.equals(incomingRum)) {
                continue;
            }

            existingRum.mergeFrom(incomingRum);
            return false;
        }

        return false;
    }

    private boolean waitBeforeNextRequest() {
        try {
            Thread.sleep(2000);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while waiting before the next request.");
            return false;
        }
    }

    private JsonObject stringSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        return schema;
    }

    private JsonObject numberSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "number");
        return schema;
    }

    private JsonObject getObject(JsonObject parent, String field) {
        JsonElement element = parent.get(field);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private JsonArray getArray(JsonObject parent, String field) {
        JsonElement element = parent.get(field);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private String getString(JsonObject object, String field) {
        String value = getStringOrNull(object, field);
        return value == null ? "" : value;
    }

    private String getStringOrNull(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString().trim();
    }

    private Double getDoubleOrNull(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            double value = element.getAsDouble();
            return value == 0.0 ? null : value;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Integer getIntegerOrNull(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) return null;
        try {
            int value = element.getAsInt();
            return value == 0 ? null : value;
        } catch (RuntimeException e) {
            return null;
        }
    }
}