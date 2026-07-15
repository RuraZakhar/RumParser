package org.example;

import com.google.gson.annotations.SerializedName;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RumProduct {

    private static final Pattern AGE_PATTERN = Pattern.compile(
            "(?i)\\b(\\d{1,2})\\s*(?:year|years|yr|yo|років|роки|річний)\\b"
    );

    private String name;
    private String description;
    private Double abv;
    private Double age;

    @SerializedName(value = "region", alternate = {"country"})
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
    private Object silpoMatch = null;
    private Set<Rating> ratings = new LinkedHashSet<>();
    private Set<Review> reviews = new LinkedHashSet<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Double getAbv() { return abv; }
    public void setAbv(Double abv) { this.abv = abv; }

    public Double getAge() { return age; }
    public void setAge(Double age) { this.age = age; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getCountry() { return region; }
    public void setCountry(String country) { this.region = country; }

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

    public Object getSilpoMatch() {return silpoMatch;}
    public void setSilpoMatch(Object silpoMatch) {
        this.silpoMatch = silpoMatch;
    }

    public Set<Rating> getRatings() { return ratings; }
    public void setRatings(Set<Rating> ratings) {
        this.ratings = ratings == null ? new LinkedHashSet<>() : ratings;
    }

    public Set<Review> getReviews() { return reviews; }
    public void setReviews(Set<Review> reviews) {
        this.reviews = reviews == null ? new LinkedHashSet<>() : reviews;
    }

    public boolean hasSilpoOffer() {
        return offer != null
                && "Silpo".equalsIgnoreCase(offer.getSource())
                && offer.getPrice() != null;
    }

    public void mergeFrom(RumProduct incoming) {
        if (incoming == null) {
            return;
        }

        copyStringIfMissing(this::getDescription, this::setDescription, incoming.getDescription());
        copyDoubleIfMissing(this::getAbv, this::setAbv, incoming.getAbv());
        copyDoubleIfMissing(this::getAge, this::setAge, incoming.getAge());
        copyStringIfMissing(this::getRegion, this::setRegion, incoming.getRegion());
        copyStringIfMissing(this::getBrand, this::setBrand, incoming.getBrand());
        copyStringIfMissing(this::getType, this::setType, incoming.getType());
        copyStringIfMissing(this::getCategory, this::setCategory, incoming.getCategory());
        copyStringIfMissing(this::getVolumeWeight, this::setVolumeWeight, incoming.getVolumeWeight());
        copyStringIfMissing(this::getImgUrl, this::setImgUrl, incoming.getImgUrl());
        copyStringIfMissing(this::getProductUrl, this::setProductUrl, incoming.getProductUrl());
        copyStringIfMissing(this::getCode, this::setCode, incoming.getCode());

        if (price == null && incoming.getPrice() != null) {
            price = incoming.getPrice();
        }
        if (offer == null && incoming.getOffer() != null) {
            offer = incoming.getOffer();
        }

        ratings.addAll(incoming.getRatings());
        reviews.addAll(incoming.getReviews());
        enrichDerivedFields();
    }

    public void enrichDerivedFields() {
        if (isBlank(category)) {
            category = "rum";
        }
        if (age == null && name != null) {
            Matcher matcher = AGE_PATTERN.matcher(name);
            if (matcher.find()) {
                age = Double.parseDouble(matcher.group(1));
            }
        }
    }

    public static String stableKey(RumProduct product) {
        if (product == null) {
            return "";
        }
        if (!isBlank(product.getProductUrl())) {
            return product.getProductUrl().trim().toLowerCase(Locale.ROOT);
        }
        return normalizeName(product.getName());
    }

    private static String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("(?iu)\\b(?:rum|ром|напій|drink)\\b", " ")
                .replaceAll("[^\\p{L}\\p{N}]", "")
                .trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void copyStringIfMissing(java.util.function.Supplier<String> getter,
                                            java.util.function.Consumer<String> setter,
                                            String incomingValue) {
        if (isBlank(getter.get()) && !isBlank(incomingValue)) {
            setter.accept(incomingValue);
        }
    }

    private static void copyDoubleIfMissing(java.util.function.Supplier<Double> getter,
                                            java.util.function.Consumer<Double> setter,
                                            Double incomingValue) {
        if (getter.get() == null && incomingValue != null) {
            setter.accept(incomingValue);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RumProduct that)) return false;
        return Objects.equals(normalizeName(name), normalizeName(that.name));
    }

    @Override
    public int hashCode() {
        return Objects.hash(normalizeName(name));
    }

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
        @SerializedName(value = "provider", alternate = {"source"})
        private String provider;

        @SerializedName(value = "rating", alternate = {"score"})
        private Double rating;

        public Rating(String provider, Double rating) {
            this.provider = provider;
            this.rating = rating;
        }

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public Double getRating() { return rating; }
        public void setRating(Double rating) { this.rating = rating; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Rating that)) return false;
            return Objects.equals(provider, that.provider);
        }

        @Override
        public int hashCode() {
            return Objects.hash(provider);
        }
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

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Review that)) return false;
            return Objects.equals(text, that.text) && Objects.equals(source, that.source);
        }

        @Override
        public int hashCode() {
            return Objects.hash(text, source);
        }
    }
}
