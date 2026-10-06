package org.booklore.service.metadata.parser;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Exact author-name comparison for metadata auto-matching: a provider result is applied only when its name equals ours. */
public final class AuthorNameMatcher {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern APOSTROPHES = Pattern.compile("['’`]");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    // Letters NFKD leaves whole, so accent stripping alone would keep "Søren" and "Soren" apart.
    private static final Map<String, String> TRANSLITERATIONS = Map.of(
            "ø", "o", "ł", "l", "ß", "ss", "æ", "ae", "œ", "oe", "đ", "d", "þ", "th");
    private static final Set<String> SUFFIXES = Set.of("jr", "sr", "ii", "iii", "iv");

    record Name(String base, String suffix) {}

    private AuthorNameMatcher() {
    }

    /** Comparable form: lowercased, accent- and punctuation-free, "Last, First" as "First Last", generational suffix dropped. */
    public static String normalise(String name) {
        return parse(name).base();
    }

    /**
     * Equal names, where a generational suffix only has to agree when both sides carry one.
     * Every short given-name token is spelt out letter by letter on both sides, so "AC", "A.C." and "A. C." compare equal.
     */
    public static boolean matches(String ours, String theirs) {
        Name a = parse(ours);
        Name b = parse(theirs);
        if (a.base().isEmpty() || !a.base().equals(b.base())) return false;
        return a.suffix().isEmpty() || b.suffix().isEmpty() || a.suffix().equals(b.suffix());
    }

    static Name parse(String name) {
        if (name == null) return new Name("", "");
        String suffix = "";
        List<String> parts = new ArrayList<>();
        for (String part : name.split(",")) {
            String folded = fold(part);
            if (SUFFIXES.contains(folded)) {
                suffix = folded;
            } else if (!folded.isEmpty()) {
                parts.add(folded);
            }
        }
        if (parts.size() == 2) {
            parts = List.of(parts.get(1), parts.get(0));
        }
        List<String> tokens = new ArrayList<>(Arrays.asList(String.join(" ", parts).split(" ")));
        tokens.removeIf(String::isEmpty);
        if (tokens.size() > 2 && SUFFIXES.contains(tokens.getLast())) {
            suffix = tokens.removeLast();
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (i < tokens.size() - 1 && token.length() <= 3) {
                token.chars().forEach(c -> out.add(String.valueOf((char) c)));
            } else {
                out.add(token);
            }
        }
        return new Name(String.join(" ", out), suffix);
    }

    /** Query spellings worth sending for a name with leading initials: as given, "A.C. Cobble" and "A. C. Cobble". */
    public static List<String> queryVariants(String name) {
        Set<String> variants = new LinkedHashSet<>();
        variants.add(name.strip());
        String[] tokens = fold(name).split(" ");
        int initials = 0;
        while (initials < tokens.length - 1 && tokens[initials].length() == 1) initials++;
        if (initials > 0) {
            String rest = String.join(" ", List.of(tokens).subList(initials, tokens.length));
            List<String> letters = List.of(tokens).subList(0, initials).stream()
                    .map(t -> t.toUpperCase(Locale.ROOT) + ".").toList();
            variants.add(capitalise(String.join("", letters) + " " + rest));
            variants.add(capitalise(String.join(" ", letters) + " " + rest));
        }
        return List.copyOf(variants);
    }

    /** Normalised book titles without subtitles, for comparing a provider's work list with our library. */
    public static Set<String> normaliseTitles(Collection<String> titles) {
        return titles.stream().filter(Objects::nonNull).map(AuthorNameMatcher::normaliseTitle)
                .filter(t -> !t.isEmpty()).collect(Collectors.toSet());
    }

    public static String normaliseTitle(String title) {
        return title == null ? "" : fold(title.split("[:(\\[]", 2)[0]);
    }

    /** Lowercase, transliterated, accent-free words separated by single spaces. */
    private static String fold(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : TRANSLITERATIONS.entrySet()) {
            lower = lower.replace(entry.getKey(), entry.getValue());
        }
        String plain = MARKS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFKD)).replaceAll("");
        plain = APOSTROPHES.matcher(plain).replaceAll("");
        return NON_ALNUM.matcher(plain).replaceAll(" ").strip();
    }

    private static String capitalise(String value) {
        return List.of(value.split(" ")).stream()
                .map(t -> t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1))
                .collect(Collectors.joining(" "));
    }
}
