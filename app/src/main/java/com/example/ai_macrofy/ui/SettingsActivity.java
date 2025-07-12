package com.example.ai_macrofy.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.ai_macrofy.R;
import com.example.ai_macrofy.utils.AppPreferences;

public class SettingsActivity extends AppCompatActivity {

    private RadioGroup radioGroupAiProvider;
    private RadioButton radioButtonOpenai;
    private RadioButton radioButtonGemini;
    private RadioButton radioButtonGemmaLocal;
    private EditText editTextOpenaiApiKey;
    private EditText editTextGeminiApiKey;
    private LinearLayout layoutGemmaOptions;
    private RadioGroup radioGroupGemmaDelegate;
    private RadioButton radioButtonGemmaCpu;
    private RadioButton radioButtonGemmaGpu;

    private AppPreferences appPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        appPreferences = new AppPreferences(this);

        radioGroupAiProvider = findViewById(R.id.radioGroup_ai_provider);
        radioButtonOpenai = findViewById(R.id.radioButton_openai);
        radioButtonGemini = findViewById(R.id.radioButton_gemini);
        radioButtonGemmaLocal = findViewById(R.id.radioButton_gemma_local);
        editTextOpenaiApiKey = findViewById(R.id.editText_openai_api_key_settings);
        editTextGeminiApiKey = findViewById(R.id.editText_gemini_api_key_settings);
        Button buttonSaveSettings = findViewById(R.id.button_save_settings);
        layoutGemmaOptions = findViewById(R.id.layout_gemma_options);
        radioGroupGemmaDelegate = findViewById(R.id.radioGroup_gemma_delegate);
        radioButtonGemmaCpu = findViewById(R.id.radioButton_gemma_cpu);
        radioButtonGemmaGpu = findViewById(R.id.radioButton_gemma_gpu);

        loadSettings();

        radioGroupAiProvider.setOnCheckedChangeListener((group, checkedId) -> updateApiKeyFieldsVisibility(checkedId));
        buttonSaveSettings.setOnClickListener(v -> saveSettings());
    }

    private void updateApiKeyFieldsVisibility(int checkedId) {
        if (checkedId == R.id.radioButton_gemma_local) {
            editTextOpenaiApiKey.setVisibility(View.GONE);
            editTextGeminiApiKey.setVisibility(View.GONE);
            layoutGemmaOptions.setVisibility(View.VISIBLE);
        } else {
            editTextOpenaiApiKey.setVisibility(View.VISIBLE);
            editTextGeminiApiKey.setVisibility(View.VISIBLE);
            layoutGemmaOptions.setVisibility(View.GONE);
        }
    }

    private void loadSettings() {
        String aiProvider = appPreferences.getAiProvider();
        if (AppPreferences.PROVIDER_GEMINI.equals(aiProvider)) {
            radioButtonGemini.setChecked(true);
        } else if (AppPreferences.PROVIDER_GEMMA_LOCAL.equals(aiProvider)) {
            radioButtonGemmaLocal.setChecked(true);
        } else {
            radioButtonOpenai.setChecked(true);
        }
        editTextOpenaiApiKey.setText(appPreferences.getOpenAiApiKey());
        editTextGeminiApiKey.setText(appPreferences.getGeminiApiKey());

        if (AppPreferences.DELEGATE_GPU.equals(appPreferences.getGemmaDelegate())) {
            radioButtonGemmaGpu.setChecked(true);
        } else {
            radioButtonGemmaCpu.setChecked(true);
        }

        updateApiKeyFieldsVisibility(radioGroupAiProvider.getCheckedRadioButtonId());
    }

    private void saveSettings() {
        int selectedId = radioGroupAiProvider.getCheckedRadioButtonId();
        String provider = AppPreferences.PROVIDER_OPENAI;
        if (selectedId == R.id.radioButton_gemini) {
            provider = AppPreferences.PROVIDER_GEMINI;
        } else if (selectedId == R.id.radioButton_gemma_local) {
            provider = AppPreferences.PROVIDER_GEMMA_LOCAL;
        }
        appPreferences.saveAiProvider(provider);
        appPreferences.saveOpenAiApiKey(editTextOpenaiApiKey.getText().toString().trim());
        appPreferences.saveGeminiApiKey(editTextGeminiApiKey.getText().toString().trim());

        String gemmaDelegate = radioGroupGemmaDelegate.getCheckedRadioButtonId() == R.id.radioButton_gemma_gpu
                ? AppPreferences.DELEGATE_GPU
                : AppPreferences.DELEGATE_CPU;
        appPreferences.saveGemmaDelegate(gemmaDelegate);

        Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show();
        finish();
    }
}