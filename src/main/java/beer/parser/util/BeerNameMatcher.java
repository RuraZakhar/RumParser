package beer.parser.util;

import beer.parser.model.BeerProduct;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

// Domain-specific name matcher, shaped like rum.parser.util.RumNameMatcher
// (parser-conventions.md §7: similarity/matching -> a RumNameMatcher-like class).
// Kept as its own class rather than merged into one shared matcher: the noise-word
// list and the matching algorithm itself (plain Jaccard word-overlap here, vs.
// Jaccard+Levenshtein+numeric-conflict-guard in RumNameMatcher) are genuinely
// different between the two domains -- unifying them would change beer.parser's
// similarity scores, which is out of scope for this refactor (see report).
public class BeerNameMatcher {

    // Applied sequentially, not combined into one alternation pattern: combining them
    // would change matching behavior, since "пастеризоване" is a substring of
    // "непастеризоване" and removing it first (as this order does) leaves a stray
    // "не" fragment rather than the "непастеризоване" pattern ever getting a match.
    // Preserved as-is from the original code rather than "fixed", since correcting it
    // is a behavior change outside this refactor's scope.
    private static final Pattern P_PYVO = Pattern.compile("пиво");
    private static final Pattern P_SVITLE = Pattern.compile("світле");
    private static final Pattern P_TEMNE = Pattern.compile("темне");
    private static final Pattern P_NAPIVTEMNE = Pattern.compile("напівтемне");
    private static final Pattern P_NEFILTROVANE = Pattern.compile("нефільтроване");
    private static final Pattern P_FILTROVANE = Pattern.compile("фільтроване");
    private static final Pattern P_PASTERYZOVANE = Pattern.compile("пастеризоване");
    private static final Pattern P_NEPASTERYZOVANE = Pattern.compile("непастеризоване");
    private static final Pattern P_ZB = Pattern.compile("з/б");
    private static final Pattern P_ROZLYVNE = Pattern.compile("розливне");
    private static final Pattern P_PLYASHKA = Pattern.compile("пляшка");
    private static final Pattern P_BANKA = Pattern.compile("банка");
    private static final Pattern P_UNIT_SUFFIX = Pattern.compile("\\d+[.,]?\\d*\\s*(ml|мл|l|л|%|°)");
    private static final Pattern P_NON_ALNUM = Pattern.compile("[^a-zа-яіїєґ0-9]");

    public static double similarity(String name1, String name2) {
        Set<String> words1 = tokenize(clean(name1));
        Set<String> words2 = tokenize(clean(name2));

        if (words1.isEmpty() || words2.isEmpty()) return 0.0;

        int intersection = 0;
        for (String w : words1) {
            if (words2.contains(w)) {
                intersection++;
            }
        }

        int union = words1.size() + words2.size() - intersection;
        return (double) intersection / union;
    }

    public static BeerProduct findBestFuzzyMatch(BeerProduct incoming, Collection<BeerProduct> candidates, double threshold) {
        BeerProduct best = null;
        double bestScore = 0.0;

        for (BeerProduct candidate : candidates) {
            double score = similarity(candidate.getCleanName(), incoming.getCleanName());
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        return (best != null && bestScore >= threshold) ? best : null;
    }

    private static Set<String> tokenize(String cleaned) {
        Set<String> words = new HashSet<>();
        for (String w : cleaned.split("\\s+")) {
            if (!w.isEmpty()) {
                words.add(w);
            }
        }
        return words;
    }

    private static String clean(String name) {
        if (name == null) {
            return "";
        }
        String s = name.toLowerCase();
        s = P_PYVO.matcher(s).replaceAll("");
        s = P_SVITLE.matcher(s).replaceAll("");
        s = P_TEMNE.matcher(s).replaceAll("");
        s = P_NAPIVTEMNE.matcher(s).replaceAll("");
        s = P_NEFILTROVANE.matcher(s).replaceAll("");
        s = P_FILTROVANE.matcher(s).replaceAll("");
        s = P_PASTERYZOVANE.matcher(s).replaceAll("");
        s = P_NEPASTERYZOVANE.matcher(s).replaceAll("");
        s = P_ZB.matcher(s).replaceAll("");
        s = P_ROZLYVNE.matcher(s).replaceAll("");
        s = P_PLYASHKA.matcher(s).replaceAll("");
        s = P_BANKA.matcher(s).replaceAll("");
        s = P_UNIT_SUFFIX.matcher(s).replaceAll("");
        s = P_NON_ALNUM.matcher(s).replaceAll(" ");
        return s.trim();
    }
}
