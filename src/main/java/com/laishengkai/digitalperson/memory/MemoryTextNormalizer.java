package com.laishengkai.digitalperson.memory;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/** Stable normalization for entity aliases and fuzzy text comparison. */
public final class MemoryTextNormalizer {
    private MemoryTextNormalizer() {
    }

    public static String normalize(String value) {
        String source = Normalizer.normalize(
                Objects.requireNonNullElse(value, ""),
                Normalizer.Form.NFKC
        ).toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(source.length());
        source.codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(normalized::appendCodePoint);
        return normalized.toString();
    }
}
