package rum.parser.model;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;
import lombok.Setter;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Getter
@Setter
public class RumProduct {

    private static final Pattern AGE_PATTERN = Pattern.compile(
            "(?i)\\b(\\d{1,2})\\s*(?:year|years|yr|yo|років|роки|річний)\\b"
    );
    private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{M}");
    private static final Pattern NAME_NOISE_PATTERN = Pattern.compile("(?iu)\\b(?:rum|ром|напій|drink)\\b");
    private static final Pattern NON_ALNUM_PATTERN = Pattern.compile("[^\\p{L}\\p{N}]");

    // Merge-policy categories per parser-conventions.md §1. Categories in play here:
    // MAX_WINS, FILL_IF_MISSING, IDENTITY, and one flagged case (price) where the
    // current FILL_IF_MISSING behavior looks like it should arguably be ALWAYS_REFRESH
    // instead -- see refactor report rather than a silent behavior change here.
    private String name; // IDENTITY -- never updated by mergeFrom(); open decision, see §1.1
    private String description; // FILL_IF_MISSING
    private Double abv; // FILL_IF_MISSING
    private Double age; // FILL_IF_MISSING

    @SerializedName(value = "region", alternate = {"country"})
    private String region; // FILL_IF_MISSING

    private String brand; // FILL_IF_MISSING -- §1.1 flags brand as identity-adjacent (open: PREFER_SOURCE?)
    private String type; // FILL_IF_MISSING
    private String category; // FILL_IF_MISSING (also defaulted to "rum" in enrichDerivedFields() if blank)
    private Double price; // FILL_IF_MISSING -- flagged: price is volatile like beer.parser's silpoPrice
                           // (ALWAYS_REFRESH there); currently dormant since SilpoParser never
                           // routes its own price updates through mergeFrom(). See refactor report.
    private String volumeWeight; // FILL_IF_MISSING
    private String imgUrl; // FILL_IF_MISSING
    private String productUrl; // FILL_IF_MISSING
    private String code; // FILL_IF_MISSING -- currently never populated by any parser (dormant)
    private Offer offer; // FILL_IF_MISSING -- currently never populated by any parser (dormant)

    private SilpoMatch silpoMatch = null; // FILL_IF_MISSING in mergeFrom(), but SilpoParser bypasses
                                           // mergeFrom() for its own updates and overwrites this directly
                                           // every run -- see refactor report

    private Map<String, String> sourceUrls = new LinkedHashMap<>(); // FILL_IF_MISSING per map key

    private Integer yearDistilled; // FILL_IF_MISSING
    private String rawMaterial; // FILL_IF_MISSING
    private String process; // FILL_IF_MISSING
    private String distillationMethod; // FILL_IF_MISSING
    private Boolean womenLed; // FILL_IF_MISSING
    private Long lastScrapedAt; // MAX_WINS -- newest timestamp wins

    private Set<Rating> ratings = new LinkedHashSet<>(); // ALWAYS_REFRESH per provider (remove+add keyed on Rating.provider)

    public void addSourceUrl(String provider, String url) {
        if (provider != null && url != null && !url.isBlank()) {
            sourceUrls.putIfAbsent(provider, url);
        }
    }

    public void mergeFrom(RumProduct incoming) {
        if (incoming == null) {
            return;
        }
        if (incoming.getLastScrapedAt() != null &&
                (lastScrapedAt == null || incoming.getLastScrapedAt() > lastScrapedAt)) {
            lastScrapedAt = incoming.getLastScrapedAt();
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
        copyIntegerIfMissing(this::getYearDistilled, this::setYearDistilled, incoming.getYearDistilled());
        copyStringIfMissing(this::getRawMaterial, this::setRawMaterial, incoming.getRawMaterial());
        copyStringIfMissing(this::getProcess, this::setProcess, incoming.getProcess());
        copyStringIfMissing(this::getDistillationMethod, this::setDistillationMethod, incoming.getDistillationMethod());
        copyBooleanIfMissing(this::getWomenLed, this::setWomenLed, incoming.getWomenLed());

        if (price == null && incoming.getPrice() != null) {
            price = incoming.getPrice();
        }
        if (offer == null && incoming.getOffer() != null) {
            offer = incoming.getOffer();
        }
        if (silpoMatch == null && incoming.getSilpoMatch() != null) {
            silpoMatch = incoming.getSilpoMatch();
        }

        if (incoming.getSourceUrls() != null) {
            for (Map.Entry<String, String> entry : incoming.getSourceUrls().entrySet()) {
                sourceUrls.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }

        for (Rating r : incoming.getRatings()) {
            ratings.removeIf(existing -> Objects.equals(existing.getProvider(), r.getProvider()));
            ratings.add(r);
        }
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
        String normalized = DIACRITICS_PATTERN.matcher(Normalizer.normalize(value, Normalizer.Form.NFD)).replaceAll("");
        normalized = normalized.toLowerCase(Locale.ROOT);
        normalized = NAME_NOISE_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = NON_ALNUM_PATTERN.matcher(normalized).replaceAll("");
        return normalized.trim();
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

    private static void copyIntegerIfMissing(java.util.function.Supplier<Integer> getter,
                                             java.util.function.Consumer<Integer> setter,
                                             Integer incomingValue) {
        if (getter.get() == null && incomingValue != null) {
            setter.accept(incomingValue);
        }
    }

    private static void copyBooleanIfMissing(java.util.function.Supplier<Boolean> getter,
                                             java.util.function.Consumer<Boolean> setter,
                                             Boolean incomingValue) {
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

    public static class SilpoMatch {
        @SerializedName("silpo_title")
        private String silpoTitle;
        @SerializedName("similarity_score")
        private Double similarityScore;
        private Double price;
        @SerializedName("in_stock")
        private Boolean inStock;
        private String url;

        public SilpoMatch(String silpoTitle, Double similarityScore, Double price, Boolean inStock, String url) {
            this.silpoTitle = silpoTitle;
            this.similarityScore = similarityScore;
            this.price = price;
            this.inStock = inStock;
            this.url = url;
        }

        public String getSilpoTitle() { return silpoTitle; }
        public void setSilpoTitle(String silpoTitle) { this.silpoTitle = silpoTitle; }
        public Double getSimilarityScore() { return similarityScore; }
        public void setSimilarityScore(Double similarityScore) { this.similarityScore = similarityScore; }
        public Double getPrice() { return price; }
        public void setPrice(Double price) { this.price = price; }
        public Boolean getInStock() { return inStock; }
        public void setInStock(Boolean inStock) { this.inStock = inStock; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
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