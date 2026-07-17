package rum.parser.parsers;

import rum.parser.model.RumProduct;

import java.util.Set;

public interface RumParser {
    /**
     * Parses a specific web source and populates the provided set with products.
     * @param rumSet The shared collection where all unique products are collected.
     */

    void parse(Set<RumProduct> rumSet);
}