package wine.parser.parsers;

import wine.parser.model.WineProduct;
import java.util.List;

public interface WineParser {
    List<WineProduct> parse(List<WineProduct> existingCache);
}