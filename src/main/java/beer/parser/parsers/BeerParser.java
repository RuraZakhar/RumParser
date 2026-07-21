package beer.parser.parsers;

import beer.parser.model.BeerProduct;
import java.util.List;

public interface BeerParser {
    List<BeerProduct> parse();
}