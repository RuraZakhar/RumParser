package beer.parser.model;

import java.util.Objects;

public class BeerProduct {
    private String name;
    private transient String cleanName;
    private String brand;
    private Double untappdRating;
    private String imgUrl;
    private String untappdSearchUrl;
    private Double silpoPrice;
    private String silpoUrl;
    private Double flaskerPrice;
    private String flaskerUrl;
    private Double silpoRating;
    private String style;
    private Double abv;
    private Integer ibu;
    private String untappdUrl;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCleanName() { return cleanName; }
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BeerProduct that = (BeerProduct) o;
        if (this.cleanName == null || that.cleanName == null) {
            return false;
        }
        return Objects.equals(cleanName, that.cleanName);
    }

    @Override
    public int hashCode() {
        return cleanName != null ? Objects.hash(cleanName) : super.hashCode();
    }
}