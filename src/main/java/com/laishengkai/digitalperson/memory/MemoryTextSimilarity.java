package com.laishengkai.digitalperson.memory;

import java.util.HashSet;
import java.util.Set;

/** Typo-tolerant similarity used for alias resolution and fact ranking. */
public final class MemoryTextSimilarity {
    private MemoryTextSimilarity() {
    }

    public static double similarity(String left, String right) {
        String normalizedLeft = MemoryTextNormalizer.normalize(left);
        String normalizedRight = MemoryTextNormalizer.normalize(right);
        if (normalizedLeft.isEmpty() || normalizedRight.isEmpty()) {
            return 0.0;
        }
        if (normalizedLeft.equals(normalizedRight)) {
            return 1.0;
        }

        double containment = containment(normalizedLeft, normalizedRight);
        double edit = normalizedEditSimilarity(normalizedLeft, normalizedRight);
        double ngram = ngramJaccard(normalizedLeft, normalizedRight);
        return Math.max(containment, Math.max(edit, ngram));
    }

    private static double containment(String left, String right) {
        String shorter = left.length() <= right.length() ? left : right;
        String longer = left.length() <= right.length() ? right : left;
        if (!longer.contains(shorter)) {
            return 0.0;
        }
        double ratio = (double) shorter.length() / longer.length();
        return Math.min(0.95, 0.72 + (0.23 * ratio));
    }

    private static double normalizedEditSimilarity(String left, String right) {
        int maximumLength = Math.max(left.length(), right.length());
        return 1.0 - ((double) levenshtein(left, right) / maximumLength);
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            char leftCharacter = left.charAt(row - 1);
            for (int column = 1; column <= right.length(); column++) {
                int substitution = previous[column - 1]
                        + (leftCharacter == right.charAt(column - 1) ? 0 : 1);
                current[column] = Math.min(
                        Math.min(previous[column] + 1, current[column - 1] + 1),
                        substitution
                );
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static double ngramJaccard(String left, String right) {
        Set<String> leftNgrams = ngrams(left);
        Set<String> rightNgrams = ngrams(right);
        Set<String> intersection = new HashSet<>(leftNgrams);
        intersection.retainAll(rightNgrams);
        Set<String> union = new HashSet<>(leftNgrams);
        union.addAll(rightNgrams);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private static Set<String> ngrams(String value) {
        int width = value.length() < 3 ? 1 : 2;
        Set<String> result = new HashSet<>();
        for (int index = 0; index <= value.length() - width; index++) {
            result.add(value.substring(index, index + width));
        }
        return result;
    }
}
