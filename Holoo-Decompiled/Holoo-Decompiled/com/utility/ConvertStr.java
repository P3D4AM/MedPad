/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.utility.TncExceptionMessages
 *  javax.servlet.http.HttpServletRequest
 *  org.apache.commons.io.FileUtils
 *  org.apache.commons.lang3.time.DateUtils
 *  org.json.JSONArray
 *  org.json.JSONException
 *  org.json.JSONObject
 *  org.springframework.http.ResponseEntity
 *  org.springframework.jdbc.core.JdbcTemplate
 *  org.springframework.jdbc.support.rowset.SqlRowSet
 *  org.springframework.lang.NonNull
 *  org.springframework.lang.Nullable
 */
package com.utility;

import com.utility.TextNormalizer;
import com.utility.TncExceptionMessages;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.servlet.http.HttpServletRequest;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

public class ConvertStr {
    public static String encKey = "3500";
    public static SimpleDateFormat defaultDateTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);

    public static String getNullIfEmpty(String value) {
        if (value == null || value.trim().isEmpty() || value.equals("null")) {
            return null;
        }
        return value;
    }

    public static String normalizeDbDateTime(@NonNull String dbDate) throws ParseException {
        return defaultDateTimeFormat.format(defaultDateTimeFormat.parse(dbDate.replace("/", "-")));
    }

    @Nullable
    public static Date parseDate(String dateStr) {
        return ConvertStr.parseDate(dateStr, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).toPattern());
    }

    @Nullable
    public static Date parseDate(String dateStr, String pattern) {
        try {
            return DateUtils.parseDate((String)dateStr, (String[])new String[]{pattern});
        }
        catch (ParseException e) {
            return null;
        }
    }

    public static String timestampToCommonDateTimeStr(Timestamp timestamp) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(defaultDateTimeFormat.toPattern());
        return timestamp.toInstant().atZone(ZoneId.systemDefault()).format(formatter);
    }

    public static double parseNullSafeDouble(String doubleStr) {
        if (ConvertStr.isNullOrEmpty(doubleStr)) {
            return 0.0;
        }
        try {
            return Double.parseDouble(doubleStr);
        }
        catch (Exception ignore) {
            return 0.0;
        }
    }

    public static int parseNullSafeInt(String intStr) {
        return (int)ConvertStr.parseNullSafeDouble(intStr);
    }

    public static long parseNullSafeLong(String longStr) {
        Long result = ConvertStr.parseLong(longStr);
        return result == null ? 0L : result;
    }

    public static Long parseLong(String longStr) {
        if (ConvertStr.isNullOrEmpty(longStr)) {
            return null;
        }
        try {
            return Long.parseLong(longStr);
        }
        catch (Exception ignore) {
            return null;
        }
    }

    public static double parseNullAndMultiplySafeDouble(String doubleStr) {
        double result = ConvertStr.parseNullSafeDouble(doubleStr);
        return result == 0.0 ? 1.0 : result;
    }

    public static boolean isNullOrEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static boolean isNullOrEmpty(Collection<?> value) {
        return value == null || value.isEmpty();
    }

    public static String getCurrentDateStringNoTime() {
        return new SimpleDateFormat("yyyy-MM-dd").format(new Date());
    }

    public static boolean dateIsInvalid(String date) {
        int year;
        try {
            year = Integer.parseInt(date.substring(0, 4));
        }
        catch (Exception ignore) {
            year = 0;
        }
        return ConvertStr.isNullOrEmpty(date) || date.length() != 10 || year < 2000;
    }

    public static int nullSafeInt(Integer value) {
        return value != null ? value : 0;
    }

    public static Long nullSafeLong(Long value) {
        return value != null ? value : 0L;
    }

    public static double nullSafeDouble(Double value) {
        return value != null ? value : 0.0;
    }

    public static double nullAndMultiplySafeDouble(Double value) {
        double result = ConvertStr.nullSafeDouble(value);
        return result == 0.0 ? 1.0 : result;
    }

    public static boolean isNullOrZero(Double value) {
        return value == null || value == 0.0;
    }

    public static String nullSafeString(String value) {
        if (value == null) {
            return "";
        }
        return value;
    }

    public static boolean nullSafeBool(Boolean value) {
        return value != null ? value : false;
    }

    public static String getNullableStrSqlField(@Nullable String field) {
        return field == null ? " NULL " : "'" + field + "'";
    }

    public static int getBoolToInt(Boolean value) {
        if (value == null) {
            return 0;
        }
        if (value.booleanValue()) {
            return 1;
        }
        return 0;
    }

    public static boolean bitStrToBool(String bitStr) {
        return !ConvertStr.isNullOrEmpty(bitStr) && bitStr.equals("1");
    }

    public static int boolStrToInt(String value) {
        if (value == null) {
            return 0;
        }
        if (Boolean.parseBoolean(value)) {
            return 1;
        }
        return 0;
    }

    public static boolean bitToBool(Integer bitValue) {
        if (bitValue == null) {
            return false;
        }
        return bitValue == 1;
    }

    public static int boolToBit(Boolean bitValue) {
        if (bitValue == null) {
            return 0;
        }
        return bitValue != false ? 1 : 0;
    }

    public static boolean boolStrToBool(String value) {
        int result = ConvertStr.boolStrToInt(value);
        return result == 1;
    }

    public static String setTypeNameOfFacture(int value) {
        if (value == 1) {
            return "\u062e\u0631\u064a\u062f";
        }
        if (value == 2) {
            return "\u0641\u0631\u0648\u0634";
        }
        if (value == 3) {
            return "\u0628\u0631\u06af\u0634\u062a \u0627\u0632 \u062e\u0631\u064a\u062f";
        }
        if (value == 4) {
            return "\u0628\u0631\u06af\u0634\u062a \u0627\u0632 \u0641\u0631\u0648\u0634";
        }
        if (value == 5) {
            return "\u067e\u064a\u0634 \u0641\u0627\u0643\u062a\u0648\u0631";
        }
        if (value == 7) {
            return "\u0627\u0628\u0637\u0627\u0644\u06cc";
        }
        return "";
    }

    public static int setTypeOfFacture(String value) {
        switch (value) {
            case "K": {
                return 1;
            }
            case "F": {
                return 2;
            }
            case "X": {
                return 3;
            }
            case "Y": {
                return 4;
            }
            case "P": {
                return 5;
            }
            case "Q": {
                return 7;
            }
        }
        return -1;
    }

    public static String getTypeOfFacture(String value) {
        switch (value) {
            case "1": {
                return "K";
            }
            case "2": 
            case "6": {
                return "F";
            }
            case "3": {
                return "X";
            }
            case "4": {
                return "Y";
            }
            case "5": {
                return "P";
            }
        }
        return "-";
    }

    public static String produceCode(int digit, int howManyZeros) {
        String s = "00000000000000000000" + digit;
        return s.substring(s.length() - howManyZeros);
    }

    private static String removeRepeated(String s) {
        String buff = s;
        StringBuilder sBuilder = new StringBuilder();
        do {
            char cr = buff.substring(0, 1).charAt(0);
            if ((buff = buff.substring(1)).contains(String.valueOf(cr))) {
                boolean flag1 = true;
                while (flag1) {
                    int m = buff.indexOf(String.valueOf(cr));
                    buff = buff.substring(0, m) + buff.substring(m + 1);
                    flag1 = buff.contains(String.valueOf(cr));
                }
            }
            sBuilder.append(cr);
        } while (!buff.isEmpty());
        s = sBuilder.toString();
        return s;
    }

    private static String changeLength(String s) {
        if (s.length() < 10) {
            int jmp = s.length();
            int c = s.substring(0, 1).codePointAt(0) + jmp;
            boolean flag = true;
            StringBuilder sBuilder = new StringBuilder(s);
            while (flag) {
                char cr = (char)(c += jmp);
                if (!sBuilder.toString().contains(String.valueOf(cr))) {
                    sBuilder.append(cr);
                }
                flag = sBuilder.length() < 10;
            }
            s = sBuilder.toString();
        }
        return s;
    }

    private static String returnOrdAsciiValues(String s) {
        StringBuilder buff = new StringBuilder();
        for (int i = 1; i <= s.length(); ++i) {
            int b = s.substring(i - 1, i).codePointAt(0);
            StringBuilder a = new StringBuilder(String.valueOf(b));
            if (a.length() >= 4) continue;
            boolean flag = true;
            while (flag) {
                a.insert(0, "0");
                flag = a.length() < 4;
            }
            buff.append((CharSequence)a);
        }
        return buff.toString();
    }

    public static String encryptText(String text, String key) {
        StringBuilder cr;
        int i;
        String[] values = new String[100];
        String[] used = new String[101];
        int keyLen = key.length();
        for (i = 1; i <= key.length(); ++i) {
            int gg = key.substring(i - 1, i).codePointAt(0);
            char mm = (char)(gg += keyLen);
            key = key.substring(0, i - 1) + mm + key.substring(i);
        }
        key = ConvertStr.removeRepeated(key);
        key = ConvertStr.changeLength(key);
        String buff = ConvertStr.returnOrdAsciiValues(text);
        for (i = 0; i <= 99; ++i) {
            values[i] = "";
        }
        for (i = 0; i <= 100; ++i) {
            used[i] = "";
        }
        int c = 0;
        boolean flag = true;
        while (flag) {
            cr = new StringBuilder(key.substring(0, 1));
            key = key.substring(1);
            used[c] = cr.toString();
            flag = !key.equals("") && ++c <= 100;
        }
        c = 0;
        int f = 0;
        int s = 0;
        flag = true;
        while (flag) {
            cr = new StringBuilder(used[f] + used[s]);
            if (cr.length() < 2) {
                ++f;
                s = 0;
            } else {
                ++s;
                values[c] = cr.toString();
                ++c;
            }
            flag = values[99].equals("");
        }
        cr = new StringBuilder();
        flag = true;
        while (flag) {
            String mmm = values[Integer.parseInt(buff.substring(0, 2))];
            cr.append(mmm);
            buff = buff.substring(2);
            flag = !buff.isEmpty();
        }
        return cr.toString();
    }

    public static String decryptText(String text, String key) {
        StringBuilder res;
        String cr;
        char mm;
        int gg;
        int i;
        String[] values = new String[100];
        String[] used = new String[101];
        int keyLen = key.length();
        for (i = 1; i <= key.length(); ++i) {
            gg = key.substring(i - 1, i).codePointAt(0);
            mm = (char)(gg += keyLen);
            key = key.substring(0, i - 1) + mm + key.substring(i);
        }
        key = ConvertStr.removeRepeated(key);
        key = ConvertStr.changeLength(key);
        new StringBuilder(ConvertStr.returnOrdAsciiValues(text));
        for (i = 0; i <= 99; ++i) {
            values[i] = "";
        }
        for (i = 0; i <= 100; ++i) {
            used[i] = "";
        }
        int c = 0;
        boolean flag = true;
        while (flag) {
            cr = key.substring(0, 1);
            key = key.substring(1);
            used[c] = cr;
            flag = !key.equals("") && ++c <= 100;
        }
        c = 0;
        int f = 0;
        int s = 0;
        flag = true;
        while (flag) {
            cr = used[f] + used[s];
            if (cr.length() < 2) {
                ++f;
                s = 0;
            } else {
                ++s;
                values[c] = cr;
                ++c;
            }
            flag = values[99].equals("");
        }
        StringBuilder buff = new StringBuilder();
        flag = true;
        while (flag) {
            cr = text.substring(0, 2);
            text = text.substring(2);
            res = new StringBuilder();
            for (i = 0; i <= 99; ++i) {
                if (!cr.equals(values[i])) continue;
                res = new StringBuilder(String.valueOf(i));
            }
            if (!res.toString().equals("")) {
                if (res.length() == 1) {
                    res.insert(0, "0");
                }
                buff.append((CharSequence)res);
            }
            flag = !text.isEmpty();
        }
        res = new StringBuilder();
        flag = true;
        while (flag) {
            gg = Integer.parseInt(buff.substring(0, 4));
            mm = (char)gg;
            res.append(mm);
            buff = new StringBuilder(buff.substring(4));
            flag = buff.length() > 0;
        }
        return res.toString();
    }

    public static byte[] imageToBytes(String imgPath) {
        if (ConvertStr.isNullOrEmpty(imgPath)) {
            return null;
        }
        try {
            File tmpImg = new File(imgPath);
            return Files.readAllBytes(tmpImg.toPath());
        }
        catch (IOException e) {
            return null;
        }
    }

    public static String base64encodeNoLineBreak(byte[] byteArray) {
        if (byteArray == null) {
            return null;
        }
        return Base64.getEncoder().encodeToString(byteArray);
    }

    public static String base64encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64decode(String text) {
        return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }

    private static String xorMessage(String message, String key) {
        try {
            if (message == null || key == null) {
                return null;
            }
            char[] keys = key.toCharArray();
            char[] messageChars = message.toCharArray();
            int ml = messageChars.length;
            int kl = keys.length;
            char[] newMessage = new char[ml];
            for (int i = 0; i < ml; ++i) {
                newMessage[i] = (char)(messageChars[i] ^ keys[i % kl]);
            }
            return new String(newMessage);
        }
        catch (Exception e) {
            return null;
        }
    }

    public static String decrypt(String txt) {
        return ConvertStr.decrypt(txt, encKey);
    }

    public static String decrypt(String txt, String key) {
        if (ConvertStr.isNullOrEmpty(txt)) {
            return "";
        }
        key = "!TNC@23067#" + key + "$";
        String encoded = ConvertStr.base64decode(txt);
        txt = ConvertStr.xorMessage(encoded, key);
        txt = ConvertStr.base64decode(txt);
        return txt;
    }

    public static String encrypt(String txt) {
        return ConvertStr.encrypt(txt, encKey);
    }

    public static String encrypt(String txt, String key) {
        key = "!TNC@23067#" + key + "$";
        String encoded = ConvertStr.base64encode(txt);
        txt = ConvertStr.xorMessage(encoded, key);
        assert (txt != null);
        txt = ConvertStr.base64encode(txt);
        return txt;
    }

    public static JSONObject rebuildJsonObject(JSONObject jObj) {
        try {
            JSONObject resultJObj = new JSONObject();
            Iterator keys = jObj.keys();
            while (keys.hasNext()) {
                String key = (String)keys.next();
                ArrayList<Object> value = new ArrayList<Object>();
                if (jObj.get(key) instanceof JSONArray) {
                    for (int i = 0; i < jObj.getJSONArray(key).length(); ++i) {
                        if (jObj.getJSONArray(key).get(i) instanceof JSONObject) {
                            value.add(ConvertStr.rebuildJsonObject(jObj.getJSONArray(key).getJSONObject(i)));
                            continue;
                        }
                        if (jObj.getJSONArray(key).get(i) instanceof JSONArray) {
                            for (int j = 0; j < jObj.getJSONArray(key).getJSONArray(i).length(); ++j) {
                                value.add(ConvertStr.rebuildJsonObject(jObj.getJSONArray(key).getJSONArray(i).getJSONObject(j)));
                            }
                            continue;
                        }
                        value.add(jObj.getJSONArray(key).get(i));
                    }
                } else if (jObj.get(key) instanceof JSONObject && jObj.getJSONObject(key).toString().indexOf(44) > 0) {
                    value.add(ConvertStr.rebuildJsonObject(jObj.getJSONObject(key)));
                } else {
                    value.add(jObj.get(key));
                }
                StringBuilder strValue = new StringBuilder();
                for (Object e : value) {
                    if (strValue.length() > 0) {
                        strValue.append(",");
                    }
                    strValue.append(e);
                }
                if (jObj.get(key) instanceof JSONArray || strValue.toString().contains(",") && strValue.toString().startsWith("{") && strValue.toString().endsWith("}")) {
                    strValue = new StringBuilder("[" + strValue + "]");
                    JSONArray nn = new JSONArray(strValue.toString());
                    resultJObj.put(key.toLowerCase(), (Object)nn);
                    continue;
                }
                resultJObj.put(key.toLowerCase(), (Object)strValue.toString());
            }
            return resultJObj;
        }
        catch (JSONException ex) {
            throw new RuntimeException(TncExceptionMessages.WRONG_JSON_STRUCT.getMessage());
        }
    }

    public static Map<String, String> rebuildQueryString(HttpServletRequest request) {
        Map paramsOriginal = request.getParameterMap();
        LinkedHashMap<String, String> paramsNew = new LinkedHashMap<String, String>();
        for (String key : paramsOriginal.keySet()) {
            String value = ((String[])paramsOriginal.get(key))[0];
            paramsNew.put(key.toLowerCase(), value);
        }
        return paramsNew;
    }

    public static ResponseEntity<Map<String, Object>> getMapResponseEntity(String objectName, @Nullable List<Map<String, Object>> failure, @Nullable List<Map<String, Object>> success) {
        LinkedHashMap<String, Object> finalMap = new LinkedHashMap<String, Object>();
        finalMap.put("Header", objectName);
        if (!ConvertStr.isNullOrEmpty(success)) {
            if (success.size() == 1) {
                finalMap.put("Success", success.get(0));
            } else {
                finalMap.put("Success", success);
            }
        }
        if (!ConvertStr.isNullOrEmpty(failure)) {
            if (failure.size() == 1) {
                finalMap.put("Failure", failure.get(0));
            } else {
                finalMap.put("Failure", failure);
            }
        }
        return ResponseEntity.ok(finalMap);
    }

    public static ResponseEntity<Map<Object, List<Map<String, Object>>>> getMapResponseEntity(String objectName, List<Map<String, Object>> rowSet, boolean isFarsi) {
        LinkedHashMap<String, List<Map<String, Object>>> finalMap = new LinkedHashMap<String, List<Map<String, Object>>>();
        if (isFarsi) {
            rowSet = TextNormalizer.getNormalizedText(rowSet);
        }
        finalMap.put(objectName, rowSet);
        return ResponseEntity.ok(finalMap);
    }

    public static List<Map<String, Object>> getMapRowSet(List<Map<String, Object>> rowSet) {
        for (int i = 0; i < rowSet.size(); ++i) {
            Map<String, Object> map = rowSet.get(i);
            map.put("ErpCode", ConvertStr.encrypt(ConvertStr.getNullIfEmpty(map.get("ErpCode").toString()), encKey));
            rowSet.set(i, map);
        }
        return rowSet;
    }

    public static String deCompressionGZip(byte[] GZipMsg) {
        StringBuilder outStr = new StringBuilder();
        try {
            String line;
            ByteArrayInputStream fis = new ByteArrayInputStream(GZipMsg);
            GZIPInputStream gZip = new GZIPInputStream(fis);
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader((InputStream)gZip, StandardCharsets.UTF_8));
            while ((line = bufferedReader.readLine()) != null) {
                outStr.append(line);
            }
            return outStr.toString();
        }
        catch (IOException iOException) {
            return "";
        }
    }

    public static String addToSqlWhere(String sqlWhere, String whereNew) {
        if (!sqlWhere.isEmpty()) {
            sqlWhere = sqlWhere + " AND ";
        }
        sqlWhere = sqlWhere + whereNew;
        return sqlWhere;
    }

    public static double getNonNull(Double d) {
        if (d == null) {
            return 0.0;
        }
        if (d.isNaN()) {
            return 0.0;
        }
        return d;
    }

    public static void deleteTempTableIfExists(String tableName, JdbcTemplate jdbcTemplate) {
        block3: {
            try {
                String tName = tableName;
                if (tableName.contains("TempDB.DBO.")) {
                    tName = tableName.replace("TempDB.DBO.", "");
                }
                jdbcTemplate.execute("if Exists(Select Id from tempdb..sysobjects \n Where Name like '%" + tName + "%' And Type = 'U' ) \n" + ConvertStr.getDropTableString(tableName));
            }
            catch (Exception e) {
                if (e.getMessage().contains("it does not exist")) break block3;
                throw e;
            }
        }
    }

    private static String getDropTableString(String tableName) {
        return " Drop Table " + tableName;
    }

    public static void writeToFile(String parent, String child, String content) {
        File logFile = new File(parent, child);
        if (!logFile.exists()) {
            return;
        }
        ConvertStr.writeToFile(logFile, content);
    }

    static void writeToFile(File logFile, String content) {
        if (!logFile.exists()) {
            return;
        }
        try {
            FileUtils.write((File)logFile, (CharSequence)content, (String)"UTF-8", (boolean)true);
        }
        catch (IOException iOException) {
            // empty catch block
        }
    }

    public static String getCommaSeparatedString(String[] items) {
        return ConvertStr.getCommaSeparatedString(Arrays.asList(items));
    }

    public static String getCommaSeparatedString(Collection<String> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String col : items) {
            sb.append(col).append(",");
        }
        sb.deleteCharAt(sb.lastIndexOf(","));
        return sb.toString();
    }

    public static String getCommaSeparatedString(SqlRowSet items, String fieldName) {
        if (items == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        while (items.next()) {
            sb.append(items.getString(fieldName)).append(",");
        }
        sb.deleteCharAt(sb.lastIndexOf(","));
        return sb.toString();
    }

    public static StringBuilder removeFromStringBuilderLastIndexOf(StringBuilder sb, String removable) {
        try {
            int lastIndexOf = sb.lastIndexOf(removable);
            return sb.delete(lastIndexOf, lastIndexOf + removable.length());
        }
        catch (Exception e) {
            return sb;
        }
    }

    public static boolean exceptionContains(Exception ex, String containingStr) {
        return ex != null && !ConvertStr.isNullOrEmpty(ex.getMessage()) && ex.getMessage().contains(containingStr);
    }

    public static String commaBindStringsWithQuotes(Collection<String> items) {
        return ConvertStr.bindStringsWrapByCharacter(items, "'", "'");
    }

    public static String bindStringsWrapByCharacter(Collection<String> items, String startWrappingChar, String endWrappingChar) {
        if (ConvertStr.isNullOrEmpty(items)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if ((item = item.trim()).startsWith(startWrappingChar) && item.endsWith(endWrappingChar)) {
                sb.append(item).append(",");
                continue;
            }
            sb.append(startWrappingChar).append(item).append(endWrappingChar).append(",");
        }
        return ConvertStr.removeFromStringBuilderLastIndexOf(sb, ",").toString();
    }

    public static String removeDecimalPartFromDouble(double d) {
        DecimalFormat df = new DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        df.setMaximumFractionDigits(340);
        return df.format(d);
    }

    public static StringBuilder getValuesQuestionMarksBasedOnInsertColNum(String sql) {
        return new StringBuilder(String.join((CharSequence)",", Collections.nCopies(sql.split(",").length, "?")));
    }

    public static boolean queryBoolean(JdbcTemplate jdbcTemplate, String queryStr, Object ... args) {
        try {
            Boolean result = (Boolean)jdbcTemplate.queryForObject(queryStr, Boolean.class, args);
            return result != null ? result : false;
        }
        catch (Exception e) {
            return false;
        }
    }

    public static <T> ArrayList<T> singletonArrayList(T o) {
        ArrayList<T> result = new ArrayList<T>();
        result.add(o);
        return result;
    }

    public static <K, T> T removeFromMapOrDefault(Map<K, T> map, K key, T defValue) {
        T mVal = map.remove(key);
        if (mVal == null) {
            return defValue;
        }
        return mVal;
    }

    public static <T> T getLast(List<T> list) {
        return list.get(list.size() - 1);
    }
}
