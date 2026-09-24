package org.booklore.util;

import org.booklore.model.dto.Shelf;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.CategoryEntity;
import org.booklore.model.entity.TagEntity;
import org.apache.commons.codec.language.Soundex;
import lombok.experimental.UtilityClass;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.text.Normalizer;

@UtilityClass
public class BookUtils {

    public static Set<Shelf> filterShelvesByUserId(Set<Shelf> shelves, Long userId) {
        if (shelves == null) return Collections.emptySet();
        return shelves.stream()
                .filter(shelf -> shelf.isPublicShelf() || userId.equals(shelf.getUserId()))
                .collect(Collectors.toSet());
    }

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern SPECIAL_CHARACTERS_PATTERN = Pattern.compile("[!@$%^&*_=|~`<>?/\"]");
    private static final Pattern DIACRITICAL_MARKS_PATTERN = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final Pattern NON_ASCII_LETTER_PATTERN = Pattern.compile("[^a-z]");
    private static final Pattern PARENTHESIS_PATTERN = Pattern.compile("\\s?\\([^()]*\\)");

    /**
     * The single searchable text of a book: title, subtitle, series, authors, categories, tags, ISBNs
     * and ASIN, normalised by {@link #normalizeForSearch}. Categories and tags are sorted so that a
     * recompute of unchanged data yields the same string.
     */
    public static String buildSearchText(BookMetadataEntity e) {
        if (e == null) return null;

        StringBuilder sb = new StringBuilder(256);
        appendWord(sb, e.getTitle());
        appendWord(sb, e.getSubtitle());
        appendWord(sb, e.getSeriesName());
        if (e.getAuthors() != null) {
            e.getAuthors().forEach(author -> appendWord(sb, author.getName()));
        }
        if (e.getCategories() != null) {
            e.getCategories().stream().map(CategoryEntity::getName).filter(Objects::nonNull).sorted().forEach(name -> appendWord(sb, name));
        }
        if (e.getTags() != null) {
            e.getTags().stream().map(TagEntity::getName).filter(Objects::nonNull).sorted().forEach(name -> appendWord(sb, name));
        }
        appendWord(sb, e.getIsbn13());
        appendWord(sb, e.getIsbn10());
        appendWord(sb, e.getAsin());

        return normalizeForSearch(sb.toString().trim());
    }

    /** Space-delimited Soundex codes of every author-name word, e.g. " G450 S363 ", for phonetic search. */
    public static String buildSearchPhonetic(BookMetadataEntity e) {
        if (e == null || e.getAuthors() == null) return null;
        Set<String> codes = new LinkedHashSet<>();
        for (AuthorEntity author : e.getAuthors()) {
            String name = normalizeForSearch(author.getName());
            if (name == null) continue;
            for (String word : WHITESPACE_PATTERN.split(name)) {
                String code = soundex(word);
                if (code != null) codes.add(code);
            }
        }
        return codes.isEmpty() ? null : " " + String.join(" ", codes) + " ";
    }

    /** American Soundex of a normalised word's ASCII letters, or null when it has none. */
    public static String soundex(String normalizedWord) {
        String letters = NON_ASCII_LETTER_PATTERN.matcher(normalizedWord).replaceAll("");
        return letters.isEmpty() ? null : Soundex.US_ENGLISH.encode(letters);
    }

    private static void appendWord(StringBuilder sb, String value) {
        if (value != null) sb.append(value).append(' ');
    }

    public static String normalizeForSearch(String term) {
        if (term == null) {
            return null;
        }
        String s = Normalizer.normalize(term, Normalizer.Form.NFD);
        s = DIACRITICAL_MARKS_PATTERN.matcher(s).replaceAll("");
        s = s.replace("ø", "o").replace("Ø", "O")
                .replace("ł", "l").replace("Ł", "L")
                .replace("æ", "ae").replace("Æ", "AE")
                .replace("œ", "oe").replace("Œ", "OE")
                .replace("ß", "ss");
        
        // Use cleanSearchTerm instead of cleanAndTruncateSearchTerm
        s = cleanSearchTerm(s);
        return s.toLowerCase();
    }

    public static String cleanFileName(String fileName) {
        String name = fileName;
        if (name == null) {
            return null;
        }
        name = name.replace("(Z-Library)", "").trim();
        
        String previous;
        do {
            previous = name;
            name = PARENTHESIS_PATTERN.matcher(name).replaceAll("").trim();
        } while (!name.equals(previous));
        
        int dotIndex = name.lastIndexOf('.'); // Remove the file extension (e.g., .pdf, .docx)
        if (dotIndex > 0) {
            name = name.substring(0, dotIndex).trim();
        }
        
        name = WHITESPACE_PATTERN.matcher(name).replaceAll(" ").trim();
        
        return name;
    }

    public static String cleanSearchTerm(String term) {
        if (term == null) {
            return "";
        }
        String s = term;
        s = SPECIAL_CHARACTERS_PATTERN.matcher(s).replaceAll("").trim();
        s = WHITESPACE_PATTERN.matcher(s).replaceAll(" ");
        return s;
    }

    public static String cleanAndTruncateSearchTerm(String term) {
        String s = cleanSearchTerm(term);
        if (s.length() > 60) {
            String[] words = WHITESPACE_PATTERN.split(s);
            if (words.length > 1) {
                StringBuilder truncated = new StringBuilder(64);
                for (String word : words) {
                    if (truncated.length() + word.length() + 1 > 60) break;
                    if (!truncated.isEmpty()) truncated.append(" ");
                    truncated.append(word);
                }
                s = truncated.toString();
            } else {
                s = s.substring(0, Math.min(60, s.length()));
            }
        }
        return s;
    }
    
    public static String isbn10To13(String isbn10) {
        if (isbn10 == null || isbn10.length() != 10) {
            return null;
        }
        String isbn13 = "978" + isbn10.substring(0, 9);
        boolean oneThree = false;
        int total = 0;
        for (char c : isbn13.toCharArray()) {
            total += (c - '0') * (oneThree ? 3 : 1);
            oneThree = !oneThree;
        }
        int checkDigit = 10 - (total % 10);
        isbn13 += checkDigit;
        return isbn13;
    }
    
    public static String isbn13to10(String isbn13) {
        if (isbn13 == null || isbn13.length() != 13 || !"978".equals(isbn13.substring(0, 3))) {
            // Only ISBN-13s that start with "978" have an equivalent ISBN-10
            return null;
        }
        String isbn10 = isbn13.substring(3, 12);
        int mult = 10;
        int total = 0;
        for (char c : isbn10.toCharArray()) {
            total += (c - '0') * mult;
            mult--;
        }
        int checkDigit = (11 - (total % 11)) % 11;
        if (checkDigit == 10) {
            isbn10 += "X";
        } else {
            isbn10 += checkDigit;
        }
        return isbn10;
    }
}
