package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

public class RumRatingsParser implements RumParser {

    private static final String FIRECRAWL_API_KEY = "API_KEY";
    private static final String FIRECRAWL_SCRAPE_URL = "https://api.firecrawl.dev/v1/scrape";
    private static final String TARGET_URL = "https://rumratings.com/brands";

    @Override
    public void parse(Set<RumProduct> rumSet) {
        System.out.println("\n[2/2] Scanning second source (RumRatings) via Firecrawl API...");


        try {
            JsonObject jsonBody = new JsonObject();
            jsonBody.addProperty("url", TARGET_URL);

            JsonArray formatsArray = new JsonArray();
            formatsArray.add("json");
            jsonBody.add("formats", formatsArray);

            JsonObject extractOptions = new JsonObject();
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");

            JsonObject properties = new JsonObject();

            JsonObject rumsList = new JsonObject();
            rumsList.addProperty("type", "array");

            JsonObject items = new JsonObject();
            items.addProperty("type", "object");

            JsonObject itemProperties = new JsonObject();

            JsonObject nameProp = new JsonObject(); nameProp.addProperty("type", "string");
            JsonObject ratingProp = new JsonObject(); ratingProp.addProperty("type", "number");
            JsonObject descProp = new JsonObject(); descProp.addProperty("type", "string");
            JsonObject imgProp = new JsonObject(); imgProp.addProperty("type", "string");
            JsonObject urlProp = new JsonObject(); urlProp.addProperty("type", "string");

            itemProperties.add("name", nameProp);
            itemProperties.add("rating", ratingProp);
            itemProperties.add("description", descProp);
            itemProperties.add("imgUrl", imgProp);
            itemProperties.add("productUrl", urlProp);

            items.add("properties", itemProperties);
            JsonArray requiredItems = new JsonArray(); requiredItems.add("name");
            items.add("required", requiredItems);

            rumsList.add("items", items);
            properties.add("rums", rumsList);
            schema.add("properties", properties);

            extractOptions.add("schema", schema);
            jsonBody.add("extract", extractOptions);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(FIRECRAWL_SCRAPE_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + FIRECRAWL_API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody.toString()))
                    .build();

            System.out.println("Sending request to Firecrawl. Waiting for browser rendering and AI extraction...");
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();

                if (responseJson.has("data") && responseJson.getAsJsonObject("data").has("json")) {
                    JsonObject extractedData = responseJson.getAsJsonObject("data").getAsJsonObject("json");
                    JsonArray rumsArray = extractedData.getAsJsonArray("rums");

                    System.out.println("Firecrawl successfully extracted " + rumsArray.size() + " products.");

                    int count = 0;
                    for (JsonElement element : rumsArray) {
                        JsonObject rumJson = element.getAsJsonObject();

                        String name = rumJson.has("name") ? rumJson.get("name").getAsString() : "";
                        if (name.isEmpty()) continue;

                        RumProduct rum = new RumProduct();
                        rum.setName(name);
                        rum.setCategory("Rum");

                        if (rumJson.has("description")) rum.setDescription(rumJson.get("description").getAsString());
                        if (rumJson.has("imgUrl")) rum.setImgUrl(rumJson.get("imgUrl").getAsString());
                        if (rumJson.has("productUrl")) rum.setProductUrl(rumJson.get("productUrl").getAsString());

                        if (rumJson.has("rating") && !rumJson.get("rating").isJsonNull()) {
                            double score = rumJson.get("rating").getAsDouble();
                            rum.getRatings().add(new RumProduct.Rating("RumRatings", score));
                        }

                        boolean isNew = rumSet.add(rum);
                        if (!isNew) {
                            System.out.println("🔄 Duplicate found via Firecrawl. Merging rating for: " + name);
                            for (RumProduct existingRum : rumSet) {
                                if (existingRum.equals(rum)) {
                                    existingRum.getRatings().addAll(rum.getRatings());
                                    if ((existingRum.getDescription() == null || existingRum.getDescription().isEmpty()) && rum.getDescription() != null) {
                                        existingRum.setDescription(rum.getDescription());
                                    }
                                    if ((existingRum.getImgUrl() == null || existingRum.getImgUrl().isEmpty()) && rum.getImgUrl() != null) {
                                        existingRum.setImgUrl(rum.getImgUrl());
                                    }
                                    break;
                                }
                            }
                        } else {
                            count++;
                        }

                        if (count >= 250) {
                            System.out.println("Target count reached for RumRatings.");
                            break;
                        }
                    }
                    System.out.println("Successfully integrated " + count + " new products from RumRatings.");
                } else {
                    System.err.println("Firecrawl response did not contain expected structured data. Response body: " + response.body());
                }
            } else {
                System.err.println("Firecrawl API error. Status Code: " + response.statusCode() + " | Response: " + response.body());
            }

        } catch (Exception e) {
            System.err.println("Error communicating with Firecrawl API: " + e.getMessage());
            e.printStackTrace();
        }
    }
}