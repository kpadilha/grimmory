package org.booklore.service.metadata.parser;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Exact author-name comparison for metadata auto-matching: a provider result is applied only when its name equals ours. */
public final class AuthorNameMatcher {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern APOSTROPHES = Pattern.compile("['’`]");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    // "AC Cobble" and "JK Rowling" spell initials without separators; the surname (last token) and all-caps names never split.
    private static final Pattern RUN_OF_INITIALS = Pattern.compile("\\p{Lu}{2,3}");

    private AuthorNameMatcher() {
    }

    /** Lowercased, accent- and punctuation-free form; initials become single-letter tokens and "Last, First" becomes "First Last". */
    public static String normalise(String name) {
        if (name == null) return "";
        String value = name.strip();
        String[] commaParts = value.split(",");
        if (commaParts.length == 2 && !commaParts[1].isBlank()) {
            value = commaParts[1] + " " + commaParts[0];
        }
        value = MARKS.matcher(Normalizer.normalize(value, Normalizer.Form.NFKD)).replaceAll("");
        value = APOSTROPHES.matcher(value).replaceAll("");
        String[] tokens = NON_ALNUM.matcher(value).replaceAll(" ").strip().split(" ");
        boolean allCaps = value.equals(value.toUpperCase(Locale.ROOT));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isEmpty()) continue;
            if (!allCaps && i < tokens.length - 1 && RUN_OF_INITIALS.matcher(token).matches()) {
                token.chars().forEach(c -> out.add(String.valueOf((char) c)));
            } else {
                out.add(token);
            }
        }
        return String.join(" ", out).toLowerCase(Locale.ROOT);
    }

    public static boolean matches(String ours, String theirs) {
        String a = normalise(ours);
        return !a.isEmpty() && a.equals(normalise(theirs));
    }

    /** Query spellings worth sending for a name with leading initials: as given, "A.C. Cobble" and "A. C. Cobble". */
    public static List<String> queryVariants(String name) {
        Set<String> variants = new LinkedHashSet<>();
        variants.add(name.strip());
        String[] tokens = normalise(name).split(" ");
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
        if (title == null) return "";
        String main = title.split("[:(\\[]", 2)[0];
        String value = MARKS.matcher(Normalizer.normalize(main, Normalizer.Form.NFKD)).replaceAll("");
        value = APOSTROPHES.matcher(value).replaceAll("");
        return NON_ALNUM.matcher(value).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }

    private static String capitalise(String value) {
        return List.of(value.split(" ")).stream()
                .map(t -> t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1))
                .collect(Collectors.joining(" "));
    }
}
