package beer.parser.model;

import java.util.Objects;

public class BeerProduct {
    // Merge-policy categories per parser-conventions.md §1. Categories in play here:
    // ALWAYS_REFRESH, MAX_WINS, FILL_IF_MISSING, IDENTITY, and UNCATEGORIZED for the
    // two fields mergeFrom() never touches at all (flagged, not silently fixed).
    private String name; // IDENTITY -- never updated by mergeFrom(); open decision, see §1.1
    private transient String cleanName; // IDENTITY -- derived from name; same open decision as name
    private String brand; // FILL_IF_MISSING -- §1.1 flags brand as identity-adjacent (open: PREFER_SOURCE?)
    private Double untappdRating; // MAX_WINS
    private String imgUrl; // FILL_IF_MISSING
    private String untappdSearchUrl; // UNCATEGORIZED -- never touched by mergeFrom(); see refactor report
    private Double silpoPrice; // ALWAYS_REFRESH
    private String silpoUrl; // ALWAYS_REFRESH -- kept in lockstep with silpoPrice
    private Double flaskerPrice; // ALWAYS_REFRESH
    private String flaskerUrl; // ALWAYS_REFRESH -- kept in lockstep with flaskerPrice
    private Double silpoRating; // ALWAYS_REFRESH
    private String style; // FILL_IF_MISSING
    private Double abv; // FILL_IF_MISSING
    private Integer ibu; // FILL_IF_MISSING
    private String untappdUrl; // UNCATEGORIZED -- never touched by mergeFrom(); see refactor report
    private String country; // FILL_IF_MISSING
    private String packaging; // FILL_IF_MISSING
    private Double volume; // FILL_IF_MISSING
    private Long lastScrapedAt; // MAX_WINS -- newest timestamp wins

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCleanName() {
        if (cleanName == null && name != null) {
            cleanName = name.toLowerCase();
        }
        return cleanName;
    }
    public void setCleanName(String cleanName) { this.cleanName = cleanName; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public Double getUntappdRating() { return untappdRating; }
    public void setUntappdRating(Double untappdRating) { this.untappdRating = untappdRating; }

    public String getImgUrl() { return imgUrl; }
    public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }

    public String getUntappdSearchUrl() { return untappdSearchUrl; }
    public void setUntappdSearchUrl(String untappdSearchUrl) { this.untappdSearchUrl = untappdSearchUrl; }

    public Double getSilpoPrice() { return silpoPrice; }
    public void setSilpoPrice(Double silpoPrice) { this.silpoPrice = silpoPrice; }

    public String getSilpoUrl() { return silpoUrl; }
    public void setSilpoUrl(String silpoUrl) { this.silpoUrl = silpoUrl; }

    public Double getFlaskerPrice() { return flaskerPrice; }
    public void setFlaskerPrice(Double flaskerPrice) { this.flaskerPrice = flaskerPrice; }

    public String getFlaskerUrl() { return flaskerUrl; }
    public void setFlaskerUrl(String flaskerUrl) { this.flaskerUrl = flaskerUrl; }

    public Double getSilpoRating() { return silpoRating; }
    public void setSilpoRating(Double silpoRating) { this.silpoRating = silpoRating; }

    public String getStyle() { return style; }
    public void setStyle(String style) { this.style = style; }

    public Double getAbv() { return abv; }
    public void setAbv(Double abv) { this.abv = abv; }

    public Integer getIbu() { return ibu; }
    public void setIbu(Integer ibu) { this.ibu = ibu; }

    public String getUntappdUrl() { return untappdUrl; }
    public void setUntappdUrl(String untappdUrl) { this.untappdUrl = untappdUrl; }

    public Long getLastScrapedAt() { return lastScrapedAt; }
    public void setLastScrapedAt(Long lastScrapedAt) { this.lastScrapedAt = lastScrapedAt; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getPackaging() { return packaging; }
    public void setPackaging(String packaging) { this.packaging = packaging; }

    public Double getVolume() { return volume; }
    public void setVolume(Double volume) { this.volume = volume; }

    public void mergeFrom(BeerProduct incoming) {
        if (incoming == null) return;

        if (incoming.getLastScrapedAt() != null &&
                (this.lastScrapedAt == null || incoming.getLastScrapedAt() > this.lastScrapedAt)) {
            this.lastScrapedAt = incoming.getLastScrapedAt();
        }

        if (incoming.getSilpoPrice() != null) {
            this.silpoPrice = incoming.getSilpoPrice();
            this.silpoUrl = incoming.getSilpoUrl();
        }
        if (incoming.getFlaskerPrice() != null) {
            this.flaskerPrice = incoming.getFlaskerPrice();
            this.flaskerUrl = incoming.getFlaskerUrl();
        }
        if (incoming.getSilpoRating() != null) {
            this.silpoRating = incoming.getSilpoRating();
        }
        if (incoming.getUntappdRating() != null) {
            if (this.untappdRating == null || incoming.getUntappdRating() > this.untappdRating) {
                this.untappdRating = incoming.getUntappdRating();
            }
        }

        if (this.country == null) this.country = incoming.getCountry();
        if (this.packaging == null) this.packaging = incoming.getPackaging();
        if (this.volume == null) this.volume = incoming.getVolume();
        if (this.abv == null) this.abv = incoming.getAbv();
        if (this.ibu == null) this.ibu = incoming.getIbu();
        if (this.style == null) this.style = incoming.getStyle();
        if (this.imgUrl == null) this.imgUrl = incoming.getImgUrl();
        if (this.brand == null) this.brand = incoming.getBrand();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BeerProduct that = (BeerProduct) o;
        return Objects.equals(getCleanName(), that.getCleanName()) && Objects.equals(volume, that.volume);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getCleanName(), volume);
    }

}

