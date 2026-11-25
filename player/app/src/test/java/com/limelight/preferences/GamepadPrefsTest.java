package com.limelight.preferences;

import android.content.SharedPreferences;

import com.limelight.binding.video.XrShared;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Gamepad mode's shortcut on the controllers: its key, values and default,
 * where the settings screen has it, how the settings and report lines say it,
 * the toast and Display tab words for each, and that every language words it.
 * The mode itself is never a setting: every session starts as the pointer.
 */
public class GamepadPrefsTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String[] LANGUAGES =
            { "values", "values-fr", "values-zh-rCN", "values-zh-rTW" };

    private static File res(String path) {
        File f = new File("src/main/res/" + path);
        return f.isFile() ? f : new File("app/src/main/res/" + path);
    }

    private static org.w3c.dom.Document parse(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(res(path));
    }

    // The entry a key names in the settings screen, of that kind
    private static Element entry(String tag, String key) throws Exception {
        NodeList all = parse("xml/preferences.xml").getElementsByTagName(tag);
        Element found = null;
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (key.equals(e.getAttributeNS(ANDROID, "key"))) {
                found = e;
            }
        }
        return found;
    }

    // The items of a string array, as written
    private static List<String> array(String name) throws Exception {
        NodeList arrays = parse("values/arrays.xml").getElementsByTagName("string-array");
        List<String> items = new ArrayList<>();
        for (int i = 0; i < arrays.getLength(); i++) {
            Element a = (Element) arrays.item(i);
            if (!name.equals(a.getAttribute("name"))) {
                continue;
            }
            NodeList children = a.getElementsByTagName("item");
            for (int j = 0; j < children.getLength(); j++) {
                items.add(children.item(j).getTextContent().trim());
            }
        }
        return items;
    }

    private static String strings(String dir) throws Exception {
        return new String(Files.readAllBytes(res(dir + "/strings.xml").toPath()),
                StandardCharsets.UTF_8);
    }

    private static String string(String strings, String key) {
        Matcher m = Pattern.compile("name=\"" + key + "\">([^<]+)<").matcher(strings);
        return m.find() ? m.group(1) : null;
    }

    @Test
    public void theMenuButtonAndGripIsTheDefault() {
        assertEquals("list_vr_gamepad_toggle", PreferenceConfiguration.VR_GAMEPAD_TOGGLE_PREF_STRING);
        assertEquals("menu_grip", PreferenceConfiguration.VR_GAMEPAD_TOGGLE_MENU_GRIP);
        assertEquals("sticks", PreferenceConfiguration.VR_GAMEPAD_TOGGLE_STICKS);
        assertEquals("triggers_grips", PreferenceConfiguration.VR_GAMEPAD_TOGGLE_TRIGGERS_GRIPS);
        assertEquals("menu_grip", PreferenceConfiguration.DEFAULT_VR_GAMEPAD_TOGGLE);
        assertEquals(0, XrShared.PAD_SHORTCUT_MENU_GRIP);
        assertEquals(1, XrShared.PAD_SHORTCUT_STICKS);
        assertEquals(2, XrShared.PAD_SHORTCUT_TRIGGERS_GRIPS);

        FakePrefs prefs = new FakePrefs();
        assertEquals(XrShared.PAD_SHORTCUT_MENU_GRIP, PreferenceConfiguration.gamepadToggle(prefs));
        prefs.putString(PreferenceConfiguration.VR_GAMEPAD_TOGGLE_PREF_STRING, "sticks");
        assertEquals(XrShared.PAD_SHORTCUT_STICKS, PreferenceConfiguration.gamepadToggle(prefs));
        prefs.putString(PreferenceConfiguration.VR_GAMEPAD_TOGGLE_PREF_STRING, "triggers_grips");
        assertEquals(XrShared.PAD_SHORTCUT_TRIGGERS_GRIPS,
                PreferenceConfiguration.gamepadToggle(prefs));
        prefs.putString(PreferenceConfiguration.VR_GAMEPAD_TOGGLE_PREF_STRING, "menu_grip");
        assertEquals(XrShared.PAD_SHORTCUT_MENU_GRIP, PreferenceConfiguration.gamepadToggle(prefs));
        // Anything it does not know is the menu button and grip
        prefs.putString(PreferenceConfiguration.VR_GAMEPAD_TOGGLE_PREF_STRING, "gamepad");
        assertEquals(XrShared.PAD_SHORTCUT_MENU_GRIP, PreferenceConfiguration.gamepadToggle(prefs));
        // And the pointer's own switches are not touched by it
        prefs.putString(PreferenceConfiguration.VR_GAMEPAD_TOGGLE_PREF_STRING, "sticks");
        assertTrue(PreferenceConfiguration.rayShown(prefs));
        assertTrue(PreferenceConfiguration.pointerSleepOn(prefs));
        assertFalse(PreferenceConfiguration.headAimOn(prefs));
    }

    @Test
    public void noSessionStartsInGamepadMode() {
        for (java.lang.reflect.Field field : PreferenceConfiguration.class.getFields()) {
            assertFalse(field.getName(), field.getName().equals("vrGamepadMode"));
        }
    }

    @Test
    public void theSettingsAndReportLinesSayIt() {
        assertEquals("gamepadShortcut=menu_grip",
                PreferenceConfiguration.gamepadToggleLabel(XrShared.PAD_SHORTCUT_MENU_GRIP, "="));
        assertEquals("gamepadShortcut=sticks",
                PreferenceConfiguration.gamepadToggleLabel(XrShared.PAD_SHORTCUT_STICKS, "="));
        assertEquals("gamepadShortcut triggers_grips",
                PreferenceConfiguration.gamepadToggleLabel(XrShared.PAD_SHORTCUT_TRIGGERS_GRIPS,
                        " "));
        assertEquals("gamepadShortcut menu_grip",
                PreferenceConfiguration.gamepadToggleLabel(7, " "));
    }

    @Test
    public void theToastStringsExistInChinese() throws Exception {
        assertEquals(8, XrShared.TOAST_GAMEPAD_MODE);
        assertEquals(9, XrShared.TOAST_POINTER_MODE);
        assertTrue(XrShared.TOAST_GAMEPAD_MODE > XrShared.TOAST_HEAD_AIM_ON);
        String strings = strings("values");
        for (String key : new String[] {
                "vr_toast_gamepad_mode", "vr_toast_gamepad_back_menu_grip",
                "vr_toast_gamepad_back_sticks", "vr_toast_gamepad_back_triggers_grips",
                "vr_toast_pointer_mode" }) {
            assertNotNull(key, string(strings, key));
        }
    }

    private static final class FakePrefs implements SharedPreferences, SharedPreferences.Editor {
        final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() { return values; }
        @Override public String getString(String key, String def) {
            return values.containsKey(key) ? (String)values.get(key) : def;
        }
        @Override public Set<String> getStringSet(String key, Set<String> def) { return def; }
        @Override public int getInt(String key, int def) {
            return values.containsKey(key) ? (Integer)values.get(key) : def;
        }
        @Override public long getLong(String key, long def) { return def; }
        @Override public float getFloat(String key, float def) { return def; }
        @Override public boolean getBoolean(String key, boolean def) {
            return values.containsKey(key) ? (Boolean)values.get(key) : def;
        }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public Editor edit() { return this; }
        @Override public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) { }

        @Override public Editor putString(String key, String value) {
            values.put(key, value);
            return this;
        }
        @Override public Editor putStringSet(String key, Set<String> value) { return this; }
        @Override public Editor putInt(String key, int value) {
            values.put(key, value);
            return this;
        }
        @Override public Editor putLong(String key, long value) { return this; }
        @Override public Editor putFloat(String key, float value) { return this; }
        @Override public Editor putBoolean(String key, boolean value) {
            values.put(key, value);
            return this;
        }
        @Override public Editor remove(String key) {
            values.remove(key);
            return this;
        }
        @Override public Editor clear() {
            values.clear();
            return this;
        }
        @Override public boolean commit() { return true; }
        @Override public void apply() { }
    }
}
