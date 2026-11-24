package com.limelight.utils;

import android.app.Activity;
import android.app.GameManager;
import android.app.GameState;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import com.limelight.preferences.PreferenceConfiguration;

import java.util.Locale;

public class UiHelper {

    private static void setGameModeStatus(Context context, boolean streaming, boolean interruptible) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                GameManager gameManager = context.getSystemService(GameManager.class);
                if (gameManager == null) {
                    return;
                }
                if (streaming) {
                    gameManager.setGameState(new GameState(false, interruptible
                            ? GameState.MODE_GAMEPLAY_INTERRUPTIBLE
                            : GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE));
                } else {
                    gameManager.setGameState(new GameState(false, GameState.MODE_NONE));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static void notifyStreamConnecting(Context context) {
        setGameModeStatus(context, true, true);
    }

    public static void notifyStreamConnected(Context context) {
        setGameModeStatus(context, true, false);
    }

    public static void notifyStreamEnteringPiP(Context context) {
        setGameModeStatus(context, true, true);
    }

    public static void notifyStreamExitingPiP(Context context) {
        setGameModeStatus(context, true, false);
    }

    public static void notifyStreamEnded(Context context) {
        setGameModeStatus(context, false, false);
    }

    public static void setLocale(Activity activity) {
        String locale = PreferenceConfiguration.readPreferences(activity).language;
        if (!locale.equals(PreferenceConfiguration.DEFAULT_LANGUAGE)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                LocaleManager localeManager = activity.getSystemService(LocaleManager.class);
                if (localeManager != null) {
                    localeManager.setApplicationLocales(LocaleList.forLanguageTags(locale));
                }
                PreferenceConfiguration.completeLanguagePreferenceMigration(activity);
            } else {
                Configuration config = new Configuration(activity.getResources().getConfiguration());
                if (locale.contains("-")) {
                    config.locale = new Locale(locale.substring(0, locale.indexOf('-')),
                            locale.substring(locale.indexOf('-') + 1));
                } else {
                    config.locale = new Locale(locale);
                }
                activity.getResources().updateConfiguration(config, activity.getResources().getDisplayMetrics());
            }
        }
    }
}
