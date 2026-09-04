package com.pingo.core.common.sql.dsl;

import java.util.Iterator;
import java.util.List;

public class TextHelper {

    public static String prefix(String value) {
        return value + "%";
    }

    public static String suffix(String value) {
        return "%" + value;
    }

    public static String contain(String value) {
        return "%" + value + "%";
    }

    public static void joinWith(StringBuilder query, List<String> data, String separator) {
        Iterator<String> iterator = data.iterator();
        while (iterator.hasNext()) {
            query.append(iterator.next());
            if (iterator.hasNext()) {
                query.append(separator);
            }
        }
    }

}
