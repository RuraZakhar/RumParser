package rum.parser.util;

import rum.parser.model.RumProduct;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RumNameMatcher {

    private static final Pattern VOLUME_PATTERN =
            Pattern.compile("\\b\\d+[.,]?\\d*\\s*(л|мл|l|ml)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOISE_WORDS =
            Pattern.compile("\\b(ром|напій|на|основі|в|коробці|подарунковий|набір|rum)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d+");

    private static final Set<String> STOPWORDS = Set.of(
            "в", "на", "el", "la", "de", "of", "in", "and", "the", "&"
    );

    public static double similarity(String rawS1, String rawS2) {
        if (rawS1 == null || rawS2 == null) return 0.0;

        String s1 = clean(rawS1);
        String s2 = clean(rawS2);

        if (s1.isEmpty() || s2.isEmpty()) return 0.0;
        if (s1.equals(s2)) return 1.0;

        if (hasConflictingNumbers(s1, s2)) {
            return 0.0;
        }

        String[] words1 = s1.split("\\s+");
        String[] words2 = s2.split("\\s+");

        int matchCount = 0;
        int validWordsCount = 0;

        for (String word : words1) {
            if (STOPWORDS.contains(word)) continue;
            validWordsCount++;
            for (String w2 : words2) {
                if (w2.equals(word) || (w2.length() > 4 && w2.startsWith(word))) {
                    matchCount++;
                    break;
                }
            }
        }

        double wordMatchScore = validWordsCount > 0 ? (double) matchCount / validWordsCount : 0.0;
        int maxLength = Math.max(s1.length(), s2.length());
        int distance = levenshteinDistance(s1, s2);
        double levenshteinScore = maxLength == 0 ? 0.0 : 1.0 - ((double) distance / maxLength);

        return Math.max(wordMatchScore, levenshteinScore);
    }

    public static RumProduct findBestFuzzyMatch(RumProduct incoming, Collection<RumProduct> candidates, double threshold) {
        RumProduct best = null;
        double bestScore = 0.0;

        for (RumProduct candidate : candidates) {
            double score = similarity(candidate.getName(), incoming.getName());
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        return (best != null && bestScore >= threshold) ? best : null;
    }

    private static boolean hasConflictingNumbers(String s1, String s2) {
        Set<String> nums1 = extractNumbers(s1);
        Set<String> nums2 = extractNumbers(s2);
        if (nums1.isEmpty() || nums2.isEmpty()) return false;
        return Collections.disjoint(nums1, nums2);
    }

    private static Set<String> extractNumbers(String s) {
        Set<String> nums = new HashSet<>();
        Matcher m = DIGIT_PATTERN.matcher(s);
        while (m.find()) nums.add(m.group());
        return nums;
    }

    private static String clean(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        normalized = VOLUME_PATTERN.matcher(normalized).replaceAll("");
        normalized = NOISE_WORDS.matcher(normalized).replaceAll("");
        normalized = normalized.replaceAll("[^\\p{L}\\p{N} ]", "");
        return normalized.trim().replaceAll("\\s+", " ");
    }

    private static int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) {
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) dp[i][j] = j;
                else if (j == 0) dp[i][j] = i;
                else dp[i][j] = Math.min(
                            Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                            dp[i - 1][j - 1] + (s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1));
            }
        }
        return dp[s1.length()][s2.length()];
    }
}