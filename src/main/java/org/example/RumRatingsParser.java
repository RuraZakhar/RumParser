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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class RumRatingsParser implements RumParser {

    private static final String FIRECRAWL_API_KEY = "fc-6643459e0664403694135d372d973aee";
    private static final String FIRECRAWL_SCRAPE_URL = "https://api.firecrawl.dev/v1/scrape";
    private static final String BASE_TARGET_URL = "https://rumratings.com/rum";

    @Override
    public void parse(Set<RumProduct> rumSet) {
        System.out.println("\n[2/2] Starting RumRatings Parser...");

        if (FIRECRAWL_API_KEY == null || FIRECRAWL_API_KEY.isBlank()) {
            System.err.println("ERROR: FIRECRAWL_API_KEY is not set.");
            return;
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(120))
                .build();

        Map<String, RumProduct> topRumsToScrape = new LinkedHashMap<>();
        int maxPages = 3;

        for (int page = 1; page <= maxPages; page++) {
            System.out.println(">>> Fetching RumRatings listing page " + page + "...");
            if (page > 1) waitBeforeNextRequest(2000);

            try {
                JsonObject extractedData = scrapeListingPage(client, page);
                if (extractedData == null) continue;

                JsonArray rumsArray = getArray(extractedData, "rums");
                if (rumsArray == null || rumsArray.isEmpty()) {
                    System.out.println("No rum products returned. Stopping list extraction.");
                    break;
                }

                for (JsonElement element : rumsArray) {
                    if (!element.isJsonObject()) continue;
                    JsonObject rumJson = element.getAsJsonObject();

                    String name = getStringOrNull(rumJson, "name");
                    String productUrl = getStringOrNull(rumJson, "productUrl");
                    Double rating = getDoubleOrNull(rumJson, "rating");

                    if (name != null && productUrl != null && rating != null && rating >= 7.0) {
                        RumProduct basicRum = new RumProduct();
                        basicRum.setName(name);
                        basicRum.setProductUrl(productUrl);
                        basicRum.getRatings().add(new RumProduct.Rating("RumRatings", rating));

                        topRumsToScrape.put(productUrl, basicRum);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error parsing RumRatings listing page " + page + ": " + e.getMessage());
            }
        }

        if (topRumsToScrape.isEmpty()) {
            System.out.println("No rums with rating >= 7.0 found. Exiting.");
            return;
        }

        System.out.println("\n>>> Step 1 Completed. Found " + topRumsToScrape.size() + " top rums to deep-scrape.");
        System.out.println(">>> Moving to Step 2: Extracting deep details...\n");

        int processed = 0;

        for (Map.Entry<String, RumProduct> entry : topRumsToScrape.entrySet()) {
            String productUrl = entry.getKey();
            RumProduct basicRum = entry.getValue();

            processed++;
            System.out.println("[" + processed + "/" + topRumsToScrape.size() + "] Deep scraping: " + basicRum.getName());

            if (processed > 1) waitBeforeNextRequest(2500);

            try {
                JsonObject detailedData = scrapeDetailPage(client, productUrl);

                if (detailedData != null) {
                    enrichRumWithAiData(basicRum, detailedData);
                }
                mergeIntoSet(rumSet, basicRum);

            } catch (Exception e) {
                System.err.println("Error extracting details for " + productUrl + ": " + e.getMessage());
            }
        }

        System.out.println("Finished RumRatings. Total top rums integrated: " + processed);
    }

    private JsonObject scrapeListingPage(HttpClient client, int page) throws Exception {
        String currentUrl = BASE_TARGET_URL + "?page=" + page;
        JsonObject requestBody = buildListingRequestBody(currentUrl);
        return sendFirecrawlRequest(client, requestBody, "Listing Page " + page);
    }

    private JsonObject buildListingRequestBody(String url) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("url", url);
        requestBody.addProperty("waitFor", 3000);
        requestBody.addProperty("onlyMainContent", true);

        JsonArray actions = new JsonArray();

        JsonObject clickRating = new JsonObject();
        clickRating.addProperty("type", "click");
        clickRating.addProperty("selector", "button[data-value=\"average_rating\"]");

        JsonObject wait1 = new JsonObject();
        wait1.addProperty("type", "wait");
        wait1.addProperty("milliseconds", 2000);

        JsonObject click50 = new JsonObject();
        click50.addProperty("type", "click");
        click50.addProperty("selector", "button[data-key=\"min_rating\"][data-value=\"50\"]");

        JsonObject wait2 = new JsonObject();
        wait2.addProperty("type", "wait");
        wait2.addProperty("milliseconds", 3000);

        actions.add(clickRating);
        actions.add(wait1);
        actions.add(click50);
        actions.add(wait2);

        requestBody.add("actions", actions);

        JsonArray formats = new JsonArray();
        formats.add("json");
        requestBody.add("formats", formats);

        JsonObject jsonOptions = new JsonObject();
        jsonOptions.addProperty("prompt", """
                Extract every rum product visible on this exact RumRatings listing page.
                For each product return ONLY these 3 fields: exact name, product URL, and rating on the 0-10 scale.
                """);

        JsonObject itemProperties = new JsonObject();
        itemProperties.add("name", stringSchema());
        itemProperties.add("productUrl", stringSchema());
        itemProperties.add("rating", numberSchema());

        JsonObject item = new JsonObject();
        item.addProperty("type", "object");
        item.add("properties", itemProperties);
        JsonArray required = new JsonArray();
        required.add("name");
        required.add("productUrl");
        item.add("required", required);

        JsonObject rums = new JsonObject();
        rums.addProperty("type", "array");
        rums.add("items", item);

        JsonObject properties = new JsonObject();
        properties.add("rums", rums);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);

        jsonOptions.add("schema", schema);
        requestBody.add("jsonOptions", jsonOptions);

        return requestBody;
    }

    private JsonObject scrapeDetailPage(HttpClient client, String url) throws Exception {
        JsonObject requestBody = buildDetailRequestBody(url);
        return sendFirecrawlRequest(client, requestBody, "Detail Page");
    }

    private JsonObject buildDetailRequestBody(String url) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("url", url);
        requestBody.addProperty("waitFor", 1500);
        requestBody.addProperty("onlyMainContent", true);

        JsonArray formats = new JsonArray();
        formats.add("json");
        requestBody.add("formats", formats);

        JsonObject jsonOptions = new JsonObject();
        jsonOptions.addProperty("prompt", """
                Extract the detailed characteristics of this specific rum.
                Return ONLY available fields: description, brand, image URL, rum type, category, 
                region/country, ABV percentage, age in years, year distilled, raw material, 
                process, distillation method, women led (true/false), and volume/weight.
                Do not invent values. If a field is missing, return null.
                """);

        JsonObject properties = new JsonObject();
        properties.add("description", stringSchema());
        properties.add("brand", stringSchema());
        properties.add("imgUrl", stringSchema());
        properties.add("type", stringSchema());
        properties.add("category", stringSchema());
        properties.add("region", stringSchema());
        properties.add("abv", numberSchema());
        properties.add("age", numberSchema());
        properties.add("volumeWeight", stringSchema());
        properties.add("yearDistilled", numberSchema());
        properties.add("rawMaterial", stringSchema());
        properties.add("process", stringSchema());
        properties.add("distillationMethod", stringSchema());

        JsonObject booleanSchema = new JsonObject();
        booleanSchema.addProperty("type", "boolean");
        properties.add("womenLed", booleanSchema);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);

        jsonOptions.add("schema", schema);
        requestBody.add("jsonOptions", jsonOptions);

        return requestBody;
    }

    private void enrichRumWithAiData(RumProduct rum, JsonObject aiData) {
        rum.setDescription(getStringOrNull(aiData, "description"));
        rum.setBrand(getStringOrNull(aiData, "brand"));
        rum.setType(getStringOrNull(aiData, "type"));
        rum.setCategory(getStringOrNull(aiData, "category"));
        rum.setRegion(getStringOrNull(aiData, "region"));
        rum.setAbv(getDoubleOrNull(aiData, "abv"));
        rum.setAge(getDoubleOrNull(aiData, "age"));
        rum.setVolumeWeight(getStringOrNull(aiData, "volumeWeight"));
        rum.setImgUrl(getStringOrNull(aiData, "imgUrl"));

        rum.setYearDistilled(getIntegerOrNull(aiData, "yearDistilled"));
        rum.setRawMaterial(getStringOrNull(aiData, "rawMaterial"));
        rum.setProcess(getStringOrNull(aiData, "process"));
        rum.setDistillationMethod(getStringOrNull(aiData, "distillationMethod"));

        if (aiData.has("womenLed") && !aiData.get("womenLed").isJsonNull()) {
            rum.setWomenLed(aiData.get("womenLed").getAsBoolean());
        }
        rum.enrichDerivedFields();
    }

    private JsonObject sendFirecrawlRequest(HttpClient client, JsonObject requestBody, String logContext) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(FIRECRAWL_SCRAPE_URL))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + FIRECRAWL_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build();

        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
                    JsonObject data = getObject(responseJson, "data");
                    return data == null ? null : getObject(data, "json");
                } else {
                    System.out.println("  API Error (" + response.statusCode() + ") for " + logContext + ". Retry " + attempt);
                    if (attempt < maxRetries) waitBeforeNextRequest(4000);
                }
            } catch (java.net.http.HttpTimeoutException e) {
                System.out.println("  Timeout for " + logContext + ". Retry " + attempt);
                if (attempt < maxRetries) waitBeforeNextRequest(4000);
            } catch (Exception e) {
                System.out.println("  Critical Error for " + logContext + ": " + e.getMessage());
                break;
            }
        }
        return null;
    }

    private boolean mergeIntoSet(Set<RumProduct> rumSet, RumProduct incomingRum) {
        if (rumSet.add(incomingRum)) return true;
        for (RumProduct existingRum : rumSet) {
            if (existingRum.equals(incomingRum)) {
                existingRum.mergeFrom(incomingRum);
                return false;
            }
        }
        return false;
    }

    private void waitBeforeNextRequest(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    private String getStringOrNull(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
        return element.getAsString().trim();
    }

    private Double getDoubleOrNull(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) return null;
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