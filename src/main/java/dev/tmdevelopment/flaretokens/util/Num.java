package dev.tmdevelopment.flaretokens.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Formatting and reading of token amounts. */
public final class Num {

    private static final String SUFFIXES = "kMBTQ";
    private static String[] suffixes = {"k", "M", "B", "T", "Q"};

    private Num() {
    }

    /** Custom suffixes from config.yml (k, M, B, T, Q). */
    public static void setSuffixes(java.util.List<String> list) {
        if (list == null || list.size() < 5) return;
        suffixes = list.subList(0, 5).toArray(new String[0]);
    }

    /** 1,234,567 */
    public static String commas(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    /** 1.2k, 3.4M ... (below 1000 the plain number) */
    public static String shortFormat(long n) {
        long abs = Math.abs(n);
        if (abs < 1000) return String.valueOf(n);
        int exp = (int) (Math.log(abs) / Math.log(1000));
        if (exp > SUFFIXES.length()) exp = SUFFIXES.length();
        DecimalFormat f = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.US));
        String value = f.format(n / Math.pow(1000, exp));
        // 999.95k -> 1000k: move to the next unit
        if (value.replace("-", "").equals("1000") && exp < SUFFIXES.length()) {
            exp++;
            value = f.format(n / Math.pow(1000, exp));
        }
        return value + suffixes[exp - 1];
    }

    /**
     * Reads "100", "1,000", "2.5k", "3m", "1b". Returns -1 for anything that is not a positive whole amount.
     */
    public static long parse(String s) {
        if (s == null) return -1;
        String t = s.trim().replace(",", "").replace("_", "").toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return -1;
        double mult = 1;
        char last = t.charAt(t.length() - 1);
        if (last == 'k') mult = 1e3;
        else if (last == 'm') mult = 1e6;
        else if (last == 'b') mult = 1e9;
        else if (last == 't') mult = 1e12;
        if (mult > 1) t = t.substring(0, t.length() - 1);
        try {
            double v = Double.parseDouble(t) * mult;
            if (Double.isNaN(v) || Double.isInfinite(v) || v < 0 || v > 9e15) return -1;
            double r = Math.floor(v);
            if (mult == 1 && r != v) return -1;
            return (long) r;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Amount from a config value: "5", "1-10", "1;10", "random_number(1;10)", "random_decimal(1;10)".
     */
    public static long roll(String spec) {
        if (spec == null) return 0;
        String t = spec.trim().toLowerCase(Locale.ROOT);
        int open = t.indexOf('(');
        int close = t.lastIndexOf(')');
        if (open >= 0 && close > open) t = t.substring(open + 1, close);
        String[] parts = t.split("[;\\-]|\\s+to\\s+");
        try {
            if (parts.length >= 2) {
                double a = Double.parseDouble(parts[0].trim());
                double b = Double.parseDouble(parts[1].trim());
                long min = (long) Math.ceil(Math.min(a, b));
                long max = (long) Math.floor(Math.max(a, b));
                if (max < min) return min;
                return ThreadLocalRandom.current().nextLong(min, max + 1);
            }
            return Math.round(Double.parseDouble(t));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
