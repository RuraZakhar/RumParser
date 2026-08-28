package rum.parser.parsers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import rum.parser.model.RumProduct;

import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// Reads the snapshot written by RumRatingsParser.parse() instead of live-scraping
// (and instead of paying for a fresh Firecrawl run), so RumRatings' refresh cadence
// can be decoupled from the other sources.
public class RumRatingFileLoader implements RumParser {

    private static final String RUM_RATING_FILE = "src/main/resources/rumrating_file.json";

    @Override
    public void parse(Set<RumProduct> rumSet) {
        List<RumProduct> loaded = loadFromFile();
        for (RumProduct incoming : loaded) {
            RumRatingsParser.mergeIntoCollection(rumSet, incoming);
        }
    }

    private List<RumProduct> loadFromFile() {
        List<RumProduct> rums = new ArrayList<>();
        Path path = Path.of(RUM_RATING_FILE);

        if (!Files.exists(path)) {
            System.err.println("[RumRatingFileLoader] Warning: file not found: " + RUM_RATING_FILE);
            return rums;
        }

        Gson gson = new GsonBuilder().create();
        try (Reader reader = Files.newBufferedReader(path)) {
            Type listType = new TypeToken<ArrayList<RumProduct>>(){}.getType();
            List<RumProduct> loaded = gson.fromJson(reader, listType);
            if (loaded != null) rums.addAll(loaded);
            System.out.println("[RumRatingFileLoader] Loaded " + rums.size() + " items from: " + RUM_RATING_FILE);
        } catch (Exception e) {
            System.err.println("[RumRatingFileLoader] Warning: failed to read/parse file: " + e.getMessage());
        }

        return rums;
    }
}
