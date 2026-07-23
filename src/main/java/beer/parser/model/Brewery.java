package beer.parser.model;

import lombok.Getter;

@Getter
public class Brewery {
    private String name;
    private String untappdUrl;
    private String searchKey;

    public Brewery(String name, String untappdUrl) {
        this.name = name;
        this.untappdUrl = untappdUrl;
        this.searchKey = generateSearchKey(name);
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
        return name + " (" + untappdUrl + ")";
    }
}