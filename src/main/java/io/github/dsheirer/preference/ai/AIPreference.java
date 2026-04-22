package io.github.dsheirer.preference.ai;

import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.sample.Listener;
import java.util.prefs.Preferences;

public class AIPreference extends Preference {
    public static final String PREFERENCE_NAME = "AI";
    public static final String KEY_GEMINI_API_KEY = "gemini.api.key";
    public static final String KEY_AI_ENABLED = "ai.enabled";
    public static final String KEY_AI_LOG_ANALYSIS_ENABLED = "ai.log.analysis.enabled";

    private Preferences mPreferences = Preferences.userNodeForPackage(AIPreference.class);

    public AIPreference(Listener<PreferenceType> updateListener) {
        super(updateListener);
    }

    @Override
    public PreferenceType getPreferenceType() {
        return PreferenceType.APPLICATION; // Or create a new one, but let's reuse APPLICATION for simplicity or create AI
    }

    public boolean isAIEnabled() {
        return mPreferences.getBoolean(KEY_AI_ENABLED, false);
    }

    public void setAIEnabled(boolean enabled) {
        mPreferences.putBoolean(KEY_AI_ENABLED, enabled);
        notifyPreferenceUpdated();
    }

    public boolean isAILogAnalysisEnabled() {
        return mPreferences.getBoolean(KEY_AI_LOG_ANALYSIS_ENABLED, false);
    }

    public void setAILogAnalysisEnabled(boolean enabled) {
        mPreferences.putBoolean(KEY_AI_LOG_ANALYSIS_ENABLED, enabled);
        notifyPreferenceUpdated();
    }

    public String getGeminiApiKey() {
        return mPreferences.get(KEY_GEMINI_API_KEY, "");
    }

    public void setGeminiApiKey(String apiKey) {
        mPreferences.put(KEY_GEMINI_API_KEY, apiKey);
        notifyPreferenceUpdated();
    }
}
