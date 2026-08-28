package beer.parser.parsers;

import beer.parser.model.BeerProduct;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Reads the Untappd snapshot written by UntappdBeerParser.parse() instead of
// live-scraping, so Untappd's refresh cadence can be decoupled from Silpo/Flasker's.
public class UntappdFileLoader implements BeerParser {

    private static final String UNTAPPD_FILE = "src/main/resources/untappd_file.json";

    @Override
    public List<BeerProduct> parse(List<BeerProduct> existingCache) {
        List<BeerProduct> beers = new ArrayList<>();
        Path path = Path.of(UNTAPPD_FILE);

        if (!Files.exists(path)) {
            System.err.println("[UntappdFileLoader] Warning: file not found: " + UNTAPPD_FILE);
            return beers;
        }

        Gson gson = new GsonBuilder().create();
        try (Reader reader = Files.newBufferedReader(path)) {
            Type listType = new TypeToken<ArrayList<BeerProduct>>(){}.getType();
            List<BeerProduct> loaded = gson.fromJson(reader, listType);
            if (loaded != null) beers.addAll(loaded);
            System.out.println("[UntappdFileLoader] Loaded " + beers.size() + " items from: " + UNTAPPD_FILE);
        } catch (Exception e) {
            System.err.println("[UntappdFileLoader] Warning: failed to read/parse file: " + e.getMessage());
        }

        return beers;
    }
}
