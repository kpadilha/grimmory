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
    private static final Pattern DOTTED_INITIALS = Pattern.compile("\\p{L}\\.?|(\\p{L}\\.)+\\p{L}?");
    private static final Pattern CAPS_RUN = Pattern.compile("\\p{Lu}{2,3}");
    private static final Set<String> SUFFIXES = Set.of("jr", "sr", "ii", "iii", "iv");

    record Name(String base, String suffix) {}

    private AuthorNameMatcher() {
    }

    /** Comparable form: lowercased, accent- and punctuation-free, "Last, First" as "First Last", generational suffix dropped. */
    public static String normalise(String name) {
        return parse(name).base();
    }

    /**
     * Equal names and equal generational suffixes ("Kurt Vonnegut" is not "Kurt Vonnegut Sr.").
     * Only tokens written as initials are spelt out letter by letter, so "Al Smith" never equals "A. L. Smith".
     */
    public static boolean matches(String ours, String theirs) {
        Name a = parse(ours);
        Name b = parse(theirs);
        return !a.base().isEmpty() && a.base().equals(b.base()) && a.suffix().equals(b.suffix());
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
                parts.add(part.strip());
            }
        }
        if (parts.size() == 2) {
            parts = List.of(parts.get(1), parts.get(0));
        }
        String raw = String.join(" ", parts);
        boolean allCaps = raw.equals(raw.toUpperCase(Locale.ROOT));
        List<String> rawTokens = new ArrayList<>(Arrays.asList(raw.split("\\s+")));
        rawTokens.removeIf(t -> fold(t).isEmpty());
        if (rawTokens.size() > 2 && SUFFIXES.contains(fold(rawTokens.getLast()))) {
            suffix = fold(rawTokens.removeLast());
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < rawTokens.size(); i++) {
            String folded = fold(rawTokens.get(i));
            if (i < rawTokens.size() - 1 && isInitials(rawTokens.get(i), allCaps)) {
                folded.replace(" ", "").chars().forEach(c -> out.add(String.valueOf((char) c)));
            } else {
                out.addAll(Arrays.asList(folded.split(" ")));
            }
        }
        return new Name(String.join(" ", out), suffix);
    }

    // "A.", "A.C.", "AC" in "AC Cobble", "JK" in "JK ROWLING"; a plain short word ("Al", "LEE") stays a word.
    private static boolean isInitials(String token, boolean allCapsName) {
        if (DOTTED_INITIALS.matcher(token).matches()) return true;
        if (!CAPS_RUN.matcher(token).matches()) return false;
        return allCapsName ? token.length() <= 2 : token.length() <= 3;
    }

    /** Query spellings worth sending for a name with leading initials: as given, "A.C. Cobble" and "A. C. Cobble". */
    public static List<String> queryVariants(String name) {
        Set<String> variants = new LinkedHashSet<>();
        variants.add(name.strip());
        Name parsed = parse(name);
        String[] tokens = parsed.base().split(" ");
        int initials = 0;
        while (initials < tokens.length - 1 && tokens[initials].length() == 1) initials++;
        if (initials > 0) {
            String rest = String.join(" ", List.of(tokens).subList(initials, tokens.length))
                    + (parsed.suffix().isEmpty() ? "" : " " + parsed.suffix());
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
