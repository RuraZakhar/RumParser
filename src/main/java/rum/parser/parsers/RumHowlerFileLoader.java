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

// Reads the snapshot written by RumHowlerParser.parse() instead of live-scraping,
// so The Rum Howler Blog's refresh cadence can be decoupled from the other sources.
public class RumHowlerFileLoader implements RumParser {

    private static final String RUM_HOWLER_FILE = "src/main/resources/rumhowler_file.json";

    @Override
    public void parse(Set<RumProduct> rumSet) {
        List<RumProduct> loaded = loadFromFile();
        for (RumProduct incoming : loaded) {
            RumHowlerParser.mergeIntoCollection(rumSet, incoming);
        }
    }

    private List<RumProduct> loadFromFile() {
        List<RumProduct> rums = new ArrayList<>();
        Path path = Path.of(RUM_HOWLER_FILE);

        if (!Files.exists(path)) {
            System.err.println("[RumHowlerFileLoader] Warning: file not found: " + RUM_HOWLER_FILE);
            return rums;
        }

        Gson gson = new GsonBuilder().create();
        try (Reader reader = Files.newBufferedReader(path)) {
            Type listType = new TypeToken<ArrayList<RumProduct>>(){}.getType();
            List<RumProduct> loaded = gson.fromJson(reader, listType);
            if (loaded != null) rums.addAll(loaded);
            System.out.println("[RumHowlerFileLoader] Loaded " + rums.size() + " items from: " + RUM_HOWLER_FILE);
        } catch (Exception e) {
            System.err.println("[RumHowlerFileLoader] Warning: failed to read/parse file: " + e.getMessage());
        }

        return rums;
    }
}
