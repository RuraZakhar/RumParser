package org.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class RumProduct {

    private String name;
    private String description;
    private Double abv;
    private Integer age;
    private String region;
    private String brand;
    private String type;
    private String category;
    private Double price;
    private String volumeWeight;
    private String imgUrl;
    private String productUrl;
    private String code;

    private Offer offer;

    private List<Rating> ratings = new ArrayList<>();
    private List<Review> reviews = new ArrayList<>();

    // --- GETTERS & SETTERS ДЛЯ ГОЛОВНОГО КЛАСУ ---

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Double getAbv() { return abv; }
    public void setAbv(Double abv) { this.abv = abv; }

    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }

    public String getVolumeWeight() { return volumeWeight; }
    public void setVolumeWeight(String volumeWeight) { this.volumeWeight = volumeWeight; }

    public String getImgUrl() { return imgUrl; }
    public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }

    public String getProductUrl() { return productUrl; }
    public void setProductUrl(String productUrl) { this.productUrl = productUrl; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public Offer getOffer() { return offer; }
    public void setOffer(Offer offer) { this.offer = offer; }

    public List<Rating> getRatings() { return ratings; }
    public void setRatings(List<Rating> ratings) { this.ratings = ratings; }

    public List<Review> getReviews() { return reviews; }
    public void setReviews(List<Review> reviews) { this.reviews = reviews; }

    // --- ВНУТРІШНІ КЛАСИ (INNER CLASSES) ---

    public static class Offer {
        private String id;
        private String category;
        private Double price;
        private Double discount;
        private Integer stock;
        private String url;
        private String source;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }

        public Double getPrice() { return price; }
        public void setPrice(Double price) { this.price = price; }

        public Double getDiscount() { return discount; }
        public void setDiscount(Double discount) { this.discount = discount; }

        public Integer getStock() { return stock; }
        public void setStock(Integer stock) { this.stock = stock; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }

    public static class Rating {
        private String provider;
        private Double rating;

        public Rating() {}
        public Rating(String provider, Double rating) {
            this.provider = provider;
            this.rating = rating;
        }

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public Double getRating() { return rating; }
        public void setRating(Double rating) { this.rating = rating; }
    }

    public static class Review {
        private String date;
        private String text;
        private Double rating;
        private String author;
        private String source;

        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public Double getRating() { return rating; }
        public void setRating(Double rating) { this.rating = rating; }

        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }

    // --- ЛОГІКА ДЕДУПЛІКАЦІЇ (EQUALS & HASHCODE) ---

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RumProduct that = (RumProduct) o;
        return Objects.equals(normalizeName(name), normalizeName(that.name));
    }

    @Override
    public int hashCode() {
        return Objects.hash(normalizeName(name));
    }

    private String normalizeName(String source) {
        if (source == null) return "";
        return source.toLowerCase().replaceAll("[^a-zA-Z0-9а-яА-Я]", "").trim();
    }
}