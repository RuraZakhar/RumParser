package beer.parser.model;

import lombok.Getter;

@Getter
public class Brewery {
    private String originalName;
    private String searchKey;

    public Brewery(String originalName) {
        this.originalName = originalName;
        this.searchKey = generateSearchKey(originalName);
    }

    private String generateSearchKey(String name) {
        if (name == null) { return ""; }

        return name.toLowerCase()
                .replaceAll("\\s+", "")
                .trim()
                .replaceAll("\\b(brewing|brewery|brouwerij|brasserie|co\\.?|company|ales|privatbrauerei|brauerei|pivovar|craft)\\b", "")
                .replaceAll("[^\\p{L}\\p{N} ]", "");
    }

    @Override
    public String toString() {
        return originalName + " (Key: [" + searchKey + "])";
    }
}
