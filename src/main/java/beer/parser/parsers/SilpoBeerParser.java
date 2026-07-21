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
import java.util.ArrayList;
import java.util.List;

public class SilpoBeerParser implements BeerParser {

    private final HttpClient client = HttpClient.newHttpClient();
    private static final String BASE_PRODUCT_URL = "https://silpo.ua/product/";
    private static final String BASE_IMAGE_URL = "https://s7g10.scene7.com/is/image/silpo/";
    private static final String[] CATEGORIES = {
            "kraftove-pyvo-4506",
            "importne-pyvo-4505"
    };

    @Override
    public List<BeerProduct> parse() {
        List<BeerProduct> allBeers = new ArrayList<>();

        for (String categorySlug : CATEGORIES) {
            int limit = 100;
            int offset = 0;
            boolean hasMore = true;

            try {
                while (hasMore) {
                    String url = "https://sf-ecom-api.silpo.ua/v1/uk/branches/00000000-0000-0000-0000-000000000000/products?" +
                            "limit=" + limit + "&offset=" + offset + "&deliveryType=DeliveryHome&category=" + categorySlug;

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .GET()
                            .build();

                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() == 200) {
                        JsonObject rootObj = JsonParser.parseString(response.body()).getAsJsonObject();
                        JsonArray items = rootObj.getAsJsonArray("items");
                        int fetchedSize = items.size();

                        for (JsonElement element : items) {
                            JsonObject item = element.getAsJsonObject();
                            BeerProduct beer = new BeerProduct();

                            beer.setName(getStringOrNull(item, "title"));
                            beer.setBrand(getStringOrNull(item, "brandTitle"));

                            if (beer.getName() != null) {
                                beer.setCleanName(beer.getName().toLowerCase());
                            }

                            Double guestRating = getDoubleOrNull(item, "guestProductRating");
                            if (guestRating != null) {
                                beer.setSilpoRating(guestRating);
                            }

                            Double untappd = getDoubleOrNull(item, "untappdRating");
                            if (untappd != null) {
                                beer.setUntappdRating(untappd);
                            }

                            beer.setSilpoPrice(getDoubleOrNull(item, "price"));

                            String icon = getStringOrNull(item, "icon");
                            if (icon != null) beer.setImgUrl(BASE_IMAGE_URL + icon);

                            String slug = getStringOrNull(item, "slug");
                            if (slug != null && !slug.isEmpty()) {
                                beer.setSilpoUrl(BASE_PRODUCT_URL + slug);
                            }

                            if (!allBeers.contains(beer)) {
                                allBeers.add(beer);
                            }
                        }

                        if (fetchedSize < limit) {
                            hasMore = false;
                        } else {
                            offset += limit;
                        }
                    } else {
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return allBeers;
    }

    private String getStringOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private Double getDoubleOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsDouble();
        }
        return null;
    }
}