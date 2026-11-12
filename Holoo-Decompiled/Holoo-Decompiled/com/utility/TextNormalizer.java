/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  javax.servlet.http.HttpServletRequest
 *  org.springframework.lang.NonNull
 *  org.springframework.lang.Nullable
 */
package com.utility;

import com.utility.ConvertStr;
import java.lang.reflect.Field;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

public class TextNormalizer {
    private static final DecimalFormat SIMPLE_DEC_FORMATTER = (DecimalFormat)NumberFormat.getInstance(Locale.ENGLISH);
    private static final String ARABIC_YA = "\u064a";
    private static final String ARABIC_KA = "\u0643";
    private static final String PERSIAN_YE = "\u06cc";
    private static final String PERSIAN_KE = "\u06a9";
    private static final String[] persianChars = new String[]{"\u06f0", "\u06f1", "\u06f2", "\u06f3", "\u06f4", "\u06f5", "\u06f6", "\u06f7", "\u06f8", "\u06f9"};
    private static final String[] arabicChars = new String[]{"\u0660", "\u0661", "\u0662", "\u0663", "\u0664", "\u0665", "\u0666", "\u0667", "\u0668", "\u0669"};
    private static final String[] englishNums = new String[]{"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};

    public static String getNormalizedText(String text) {
        String normalizedText = TextNormalizer.normalizeFasterNoNull(text);
        return TextNormalizer.getPersianText(normalizedText);
    }

    public static String getNormalizedTextEnglishNumbers(String text) {
        String normalizedText = TextNormalizer.getNormalizedText(text);
        return TextNormalizer.getEnglishText(normalizedText);
    }

    public static List<Map<String, Object>> getNormalizedText(@NonNull List<Map<String, Object>> fullJson) {
        ArrayList<Map<String, Object>> newJson = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> jsonField : fullJson) {
            Set<String> keySet = jsonField.keySet();
            for (String key : keySet) {
                Object o = jsonField.get(key);
                if (o == null || o.toString().isEmpty() || o instanceof Number) continue;
                if (!(o instanceof List)) {
                    String field = o.toString();
                    jsonField.replace(key, field, TextNormalizer.getNormalizedText(field));
                    continue;
                }
                if (!((List)o).isEmpty() && ((List)o).get(0) instanceof String) {
                    jsonField.replace(key, o, TextNormalizer.getNormalizedTextList((List)o));
                    continue;
                }
                TextNormalizer.getNormalizedText((List)o);
            }
            newJson.add(jsonField);
        }
        return newJson;
    }

    public static <T> List<T> getNormalizedText(@NonNull List<T> fullJson, Class<T> tClass) {
        ArrayList<T> newJson = new ArrayList<T>();
        for (T o : fullJson) {
            for (Field tClassField : tClass.getFields()) {
                Object field = tClassField.get(o);
                if (field == null || field.toString().isEmpty() || field instanceof Number) continue;
                if (!(field instanceof List)) {
                    field = o.toString();
                    tClassField.set(o, TextNormalizer.getNormalizedText(field.toString()));
                    continue;
                }
                if (!((List)field).isEmpty() && ((List)field).get(0) instanceof String) {
                    tClassField.set(o, TextNormalizer.getNormalizedTextList((List)field));
                    continue;
                }
                TextNormalizer.getNormalizedText((List)o);
            }
            newJson.add(o);
        }
        return newJson;
    }

    private static List<String> getNormalizedTextList(List<String> list) {
        for (int i = 0; i < list.size(); ++i) {
            String text = list.get(i);
            list.remove(i);
            list.add(i, TextNormalizer.getNormalizedText(text));
        }
        return list;
    }

    private static String getPersianText(String s) {
        String persianText = s;
        if (s == null || s.isEmpty()) {
            return persianText;
        }
        if (s.contains(ARABIC_YA)) {
            persianText = s.replace(ARABIC_YA, PERSIAN_YE);
        }
        if (s.contains(ARABIC_KA)) {
            persianText = persianText.replace(ARABIC_KA, PERSIAN_KE);
        }
        return persianText;
    }

    private static String normalizeFasterNoNull(String unicodeStr) {
        ArrayList<int[]> ans = new ArrayList<int[]>();
        int[] indexPointers = new int[]{0, unicodeStr.length()};
        while (indexPointers[0] < indexPointers[1]) {
            char ch = unicodeStr.charAt(indexPointers[0]);
            if (ch > '\u00ff' && Character.isDigit((int)ch)) {
                int temp = indexPointers[0];
                TextNormalizer.processNumericPart(indexPointers, unicodeStr);
                ans.add(new int[]{temp, indexPointers[0]});
            }
            indexPointers[0] = indexPointers[0] + 1;
        }
        return ans.isEmpty() ? unicodeStr : TextNormalizer.regenerateString(new StringBuilder(unicodeStr), ans);
    }

    private static void processNumericPart(int[] indexPointers, String unicodeStr) {
        while (indexPointers[0] < indexPointers[1]) {
            char ch = unicodeStr.charAt(indexPointers[0]);
            if (ch <= '\u00ff' || !Character.isDigit((int)ch)) {
                return;
            }
            indexPointers[0] = indexPointers[0] + 1;
        }
    }

    private static String regenerateString(StringBuilder originalStr, ArrayList<int[]> parts) {
        for (int[] m : parts) {
            originalStr.replace(m[0], m[1], TextNormalizer.makeAsciiString(originalStr.substring(m[0], m[1])));
        }
        return originalStr.toString();
    }

    private static String makeAsciiString(String originalStr) {
        try {
            int len = originalStr.length();
            if (len > 9) {
                int i;
                int a = len / 9;
                StringBuilder sb = new StringBuilder(len);
                for (i = 0; i < a; ++i) {
                    sb.append(TextNormalizer.convertUnicodeDigitsToAscii(originalStr.substring(i * 9, (i + 1) * 9)));
                }
                if (len % 9 > 0) {
                    sb.append(TextNormalizer.convertUnicodeDigitsToAscii(originalStr.substring(i * 9, len)));
                }
                return sb.toString();
            }
            return TextNormalizer.convertUnicodeDigitsToAscii(originalStr);
        }
        catch (Exception e) {
            return "";
        }
    }

    private static String convertUnicodeDigitsToAscii(String originalStr) {
        String asciiStr = SIMPLE_DEC_FORMATTER.format(Integer.parseInt(originalStr));
        int diff = originalStr.length() - asciiStr.length();
        if (diff > 0) {
            StringBuilder prefix = new StringBuilder(diff);
            for (int i = 0; i < diff; ++i) {
                prefix.append('0');
            }
            return prefix.append(asciiStr).toString();
        }
        return asciiStr;
    }

    public static boolean isFarsi(HttpServletRequest request) {
        Map<String, String> info = ConvertStr.rebuildQueryString(request);
        String isFa = "false";
        if (info.containsKey("fa")) {
            isFa = info.get("fa");
        }
        return isFa.equals("true");
    }

    @Nullable
    private static String getEnglishText(String s) {
        String englishText = s;
        if (ConvertStr.isNullOrEmpty(s)) {
            return englishText;
        }
        for (int i = 0; i < englishNums.length; ++i) {
            if (englishText.contains(persianChars[i])) {
                englishText = englishText.replace(persianChars[i], englishNums[i]);
            }
            if (!englishText.contains(arabicChars[i])) continue;
            englishText = englishText.replace(arabicChars[i], englishNums[i]);
        }
        return englishText;
    }
}
