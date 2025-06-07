package com.example.ai_macrofy.ui;

// import android.content.Context; // AppPreferences에서 사용
// import android.content.SharedPreferences; // AppPreferences에서 사용
import android.os.Bundle;
// import android.view.View; // 필요시
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.ai_macrofy.R; // R import 추가
import com.example.ai_macrofy.utils.AppPreferences;

public class SettingsActivity extends AppCompatActivity {

    private RadioGroup radioGroupAiProvider;
    private RadioButton radioButtonOpenai;
    private RadioButton radioButtonGemini;
    private EditText editTextOpenaiApiKey;
    private EditText editTextGeminiApiKey;
    // private Button buttonSaveSettings; // onCreate에서 초기화

    private AppPreferences appPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings); // 레이아웃 파일 이름 확인

        appPreferences = new AppPreferences(this);

        radioGroupAiProvider = findViewById(R.id.radioGroup_ai_provider);
        radioButtonOpenai = findViewById(R.id.radioButton_openai);
        radioButtonGemini = findViewById(R.id.radioButton_gemini);
        editTextOpenaiApiKey = findViewById(R.id.editText_openai_api_key_settings);
        editTextGeminiApiKey = findViewById(R.id.editText_gemini_api_key_settings);
        Button buttonSaveSettings = findViewById(R.id.button_save_settings); // 여기서 초기화

        loadSettings();

        buttonSaveSettings.setOnClickListener(v -> saveSettings());
    }

    private void loadSettings() {
        String aiProvider = appPreferences.getAiProvider();
        if (AppPreferences.PROVIDER_GEMINI.equals(aiProvider)) {
            radioButtonGemini.setChecked(true);
        } else {
            radioButtonOpenai.setChecked(true);
        }
        editTextOpenaiApiKey.setText(appPreferences.getOpenAiApiKey());
        editTextGeminiApiKey.setText(appPreferences.getGeminiApiKey());
    }

    private void saveSettings() {
        int selectedId = radioGroupAiProvider.getCheckedRadioButtonId();
        String provider = AppPreferences.PROVIDER_OPENAI;
        if (selectedId == R.id.radioButton_gemini) {
            provider = AppPreferences.PROVIDER_GEMINI;
        }
        appPreferences.saveAiProvider(provider);
        appPreferences.saveOpenAiApiKey(editTextOpenaiApiKey.getText().toString().trim());
        appPreferences.saveGeminiApiKey(editTextGeminiApiKey.getText().toString().trim());

        Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show();
        finish();
    }
}