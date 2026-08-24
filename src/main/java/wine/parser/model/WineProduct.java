package wine.parser.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class WineProduct {
    private String name;
    private transient String cleanName;
    private String brand;
    private String country;
    private Double abv;
    private Double volume;
    private Double rating;
    private Integer reviewsCount;
    private String imgUrl;
    private String description;
    private Long lastScrapedAt;

    // Maudau
    private Long maudauId;
    private Double maudauPrice;
    private String maudauUrl;

    // Zakaz-Zaraz
    private String ean;
    private String sku;
    private Double price;
    private Boolean inStock;
    private Boolean isAlcohol;
    private String productUrl;
    private Map<String, String> taxons = new LinkedHashMap<>();
    private Map<String, String> sourceUrls = new LinkedHashMap<>();

    // Silpo (вино)
    private String winery;
    private Double vivinoRating;
    private Double silpoPrice;
    private String silpoUrl;

    // OKWine
    private Double okwinePrice;
    private String okwineUrl;

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

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public Double getAbv() { return abv; }
    public void setAbv(Double abv) { this.abv = abv; }

    public Double getVolume() { return volume; }
    public void setVolume(Double volume) { this.volume = volume; }

    public Double getRating() { return rating; }
    public void setRating(Double rating) { this.rating = rating; }

    public Integer getReviewsCount() { return reviewsCount; }
    public void setReviewsCount(Integer reviewsCount) { this.reviewsCount = reviewsCount; }

    public String getImgUrl() { return imgUrl; }
    public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getLastScrapedAt() { return lastScrapedAt; }
    public void setLastScrapedAt(Long lastScrapedAt) { this.lastScrapedAt = lastScrapedAt; }

    public Long getMaudauId() { return maudauId; }
    public void setMaudauId(Long maudauId) { this.maudauId = maudauId; }

    public Double getMaudauPrice() { return maudauPrice; }
    public void setMaudauPrice(Double maudauPrice) { this.maudauPrice = maudauPrice; }

    public String getMaudauUrl() { return maudauUrl; }
    public void setMaudauUrl(String maudauUrl) { this.maudauUrl = maudauUrl; }

    public String getEan() { return ean; }
    public void setEan(String ean) { this.ean = ean; }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }

    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }

    public Boolean getInStock() { return inStock; }
    public void setInStock(Boolean inStock) { this.inStock = inStock; }

    public Boolean getIsAlcohol() { return isAlcohol; }
    public void setIsAlcohol(Boolean isAlcohol) { this.isAlcohol = isAlcohol; }

    public String getProductUrl() { return productUrl; }
    public void setProductUrl(String productUrl) { this.productUrl = productUrl; }

    public Map<String, String> getTaxons() { return taxons; }
    public void setTaxons(Map<String, String> taxons) { this.taxons = taxons; }

    public Map<String, String> getSourceUrls() { return sourceUrls; }

    public void addSourceUrl(String provider, String url) {
        if (provider != null && url != null && !url.isBlank()) {
            sourceUrls.putIfAbsent(provider, url);
        }
    }

    public String getWinery() { return winery; }
    public void setWinery(String winery) { this.winery = winery; }

    public Double getVivinoRating() { return vivinoRating; }
    public void setVivinoRating(Double vivinoRating) { this.vivinoRating = vivinoRating; }

    public Double getSilpoPrice() { return silpoPrice; }
    public void setSilpoPrice(Double silpoPrice) { this.silpoPrice = silpoPrice; }

    public String getSilpoUrl() { return silpoUrl; }
    public void setSilpoUrl(String silpoUrl) { this.silpoUrl = silpoUrl; }

    public Double getOkwinePrice() { return okwinePrice; }
    public void setOkwinePrice(Double okwinePrice) { this.okwinePrice = okwinePrice; }

    public String getOkwineUrl() { return okwineUrl; }
    public void setOkwineUrl(String okwineUrl) { this.okwineUrl = okwineUrl; }

    public void mergeFrom(WineProduct incoming) {
        if (incoming == null) return;

        if (incoming.getLastScrapedAt() != null &&
                (this.lastScrapedAt == null || incoming.getLastScrapedAt() > this.lastScrapedAt)) {
            this.lastScrapedAt = incoming.getLastScrapedAt();
        }

        if (incoming.getMaudauPrice() != null) {
            this.maudauPrice = incoming.getMaudauPrice();
            this.maudauUrl = incoming.getMaudauUrl();
            this.maudauId = incoming.getMaudauId();
        }
        if (incoming.getPrice() != null) {
            this.price = incoming.getPrice();
            this.productUrl = incoming.getProductUrl();
        }
        if (incoming.getSilpoPrice() != null) {
            this.silpoPrice = incoming.getSilpoPrice();
            this.silpoUrl = incoming.getSilpoUrl();
        }
        if (incoming.getOkwinePrice() != null) {
            this.okwinePrice = incoming.getOkwinePrice();
            this.okwineUrl = incoming.getOkwineUrl();
        }

        if (this.ean == null) this.ean = incoming.getEan();
        if (this.sku == null) this.sku = incoming.getSku();
        if (this.inStock == null) this.inStock = incoming.getInStock();
        if (this.isAlcohol == null) this.isAlcohol = incoming.getIsAlcohol();
        if (this.winery == null) this.winery = incoming.getWinery();

        if (incoming.getRating() != null) {
            if (this.rating == null || incoming.getRating() > this.rating) {
                this.rating = incoming.getRating();
            }
        }
        if (incoming.getVivinoRating() != null) {
            if (this.vivinoRating == null || incoming.getVivinoRating() > this.vivinoRating) {
                this.vivinoRating = incoming.getVivinoRating();
            }
        }

        if (this.country == null) this.country = incoming.getCountry();
        if (this.abv == null) this.abv = incoming.getAbv();
        if (this.volume == null) this.volume = incoming.getVolume();
        if (this.brand == null) this.brand = incoming.getBrand();
        if (this.imgUrl == null) this.imgUrl = incoming.getImgUrl();
        if (this.description == null) this.description = incoming.getDescription();
        if (this.reviewsCount == null) this.reviewsCount = incoming.getReviewsCount();

        if (incoming.getTaxons() != null) {
            for (Map.Entry<String, String> e : incoming.getTaxons().entrySet()) {
                this.taxons.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        if (incoming.getSourceUrls() != null) {
            for (Map.Entry<String, String> e : incoming.getSourceUrls().entrySet()) {
                this.sourceUrls.putIfAbsent(e.getKey(), e.getValue());
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WineProduct that = (WineProduct) o;
        return Objects.equals(cleanName, that.cleanName) && Objects.equals(volume, that.volume);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cleanName, volume);
    }
}