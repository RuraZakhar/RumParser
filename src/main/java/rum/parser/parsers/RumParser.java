package rum.parser.parsers;

import rum.parser.model.RumProduct;

import java.util.Set;

public interface RumParser {

    void parse(Set<RumProduct> rumSet);
}