package com.carcolate.agents.cfg;

public class EnumUtil {

    public static <T extends EnumInterface<String>> T getByValue(String value, Class<T> enumClass) {
        if (value == null) {
            return null;
        }
        for (T each : enumClass.getEnumConstants()) {
            if (value.equals(each.getCode())) {
                return each;
            }
        }
        return null;
    }

    public static <T extends EnumInterface<Integer>> T getByValue(Integer value, Class<T> enumClass) {
        if (value == null) {
            return null;
        }
        for (T each : enumClass.getEnumConstants()) {
            if (value.equals(each.getCode())) {
                return each;
            }
        }
        return null;
    }
}
