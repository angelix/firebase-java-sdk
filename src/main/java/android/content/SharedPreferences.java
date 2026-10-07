package android.content;

import java.util.Map;

public interface SharedPreferences {

    boolean contains(String key);
    String getString(String key, String defaultValue);
    Map<String, String> getAll();
    long getLong(String key, long defValue);
    int getInt(String key, int defValue);

    Editor edit();

    interface Editor {
        Editor putLong(String key, long value);
        Editor putString(String key, String value);
        Editor putInt(String key, int value);
        Editor clear();
        boolean commit();
        void apply();
    }
}
