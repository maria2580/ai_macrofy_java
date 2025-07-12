package com.example.ai_macrofy.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.ai_macrofy.R;
import com.example.ai_macrofy.utils.AppPreferences;

public class SettingsActivity extends AppCompatActivity {

    private RadioGroup radioGroupAiProvider;
    private RadioButton radioButtonOpenai;
    private RadioButton radioButtonGemini;
    private RadioButton radioButtonGemmaLocal;
    private RadioButton radioButtonGeminiWeb;
    private EditText editTextOpenaiApiKey;
    private EditText editTextGeminiApiKey;
    private LinearLayout layoutGemmaOptions;
    private RadioGroup radioGroupGemmaDelegate;
    private RadioButton radioButtonGemmaCpu;
    private RadioButton radioButtonGemmaGpu;
    private LinearLayout layoutApiKeys;
    private Button buttonGeminiWebLogout;

    private AppPreferences appPreferences;

    @Override
    protected void onResume() {
        super.onResume();
        // 다른 화면(WebViewActivity)에서 돌아왔을 때 UI를 갱신하기 위해
        updateUiForProvider(radioGroupAiProvider.getCheckedRadioButtonId());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        appPreferences = new AppPreferences(this);

        radioGroupAiProvider = findViewById(R.id.radioGroup_ai_provider);
        radioButtonOpenai = findViewById(R.id.radioButton_openai);
        radioButtonGemini = findViewById(R.id.radioButton_gemini);
        radioButtonGemmaLocal = findViewById(R.id.radioButton_gemma_local);
        radioButtonGeminiWeb = findViewById(R.id.radioButton_gemini_web);
        editTextOpenaiApiKey = findViewById(R.id.editText_openai_api_key_settings);
        editTextGeminiApiKey = findViewById(R.id.editText_gemini_api_key_settings);
        Button buttonSaveSettings = findViewById(R.id.button_save_settings);
        layoutGemmaOptions = findViewById(R.id.layout_gemma_options);
        radioGroupGemmaDelegate = findViewById(R.id.radioGroup_gemma_delegate);
        radioButtonGemmaCpu = findViewById(R.id.radioButton_gemma_cpu);
        radioButtonGemmaGpu = findViewById(R.id.radioButton_gemma_gpu);
        layoutApiKeys = findViewById(R.id.layout_api_keys);
        buttonGeminiWebLogout = findViewById(R.id.button_gemini_web_logout);

        loadSettings();

        radioGroupAiProvider.setOnCheckedChangeListener((group, checkedId) -> updateUiForProvider(checkedId));
        buttonSaveSettings.setOnClickListener(v -> saveSettings());
        buttonGeminiWebLogout.setOnClickListener(v -> logoutFromGeminiWeb());
    }

    private void updateUiForProvider(int checkedId) {
        layoutApiKeys.setVisibility(View.VISIBLE);
        buttonGeminiWebLogout.setVisibility(View.GONE);
        layoutGemmaOptions.setVisibility(View.GONE);

        if (checkedId == R.id.radioButton_gemma_local) {
            layoutApiKeys.setVisibility(View.GONE);
            layoutGemmaOptions.setVisibility(View.VISIBLE);
        } else if (checkedId == R.id.radioButton_gemini_web) {
            layoutApiKeys.setVisibility(View.GONE);
            handleGeminiWebSelection();
        }
    }

    private void handleGeminiWebSelection() {
        if (appPreferences.isGeminiWebLoggedIn()) {
            buttonGeminiWebLogout.setVisibility(View.VISIBLE);
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Login Required")
                    .setMessage("Gemini (Web UI) provider requires you to log in first. Would you like to log in now?")
                    .setPositiveButton("Login", (dialog, which) -> {
                        startActivity(new Intent(this, WebViewActivity.class));
                    })
                    .setNegativeButton("No", null)
                    .show();
        }
    }

    private void logoutFromGeminiWeb() {
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        appPreferences.setGeminiWebLoggedIn(false);
        Toast.makeText(this, "Logged out from Gemini (Web UI).", Toast.LENGTH_SHORT).show();
        updateUiForProvider(radioGroupAiProvider.getCheckedRadioButtonId());
    }

    private void loadSettings() {
        String aiProvider = appPreferences.getAiProvider();
        if (AppPreferences.PROVIDER_GEMINI.equals(aiProvider)) {
            radioButtonGemini.setChecked(true);
        } else if (AppPreferences.PROVIDER_GEMMA_LOCAL.equals(aiProvider)) {
            radioButtonGemmaLocal.setChecked(true);
        } else if (AppPreferences.PROVIDER_GEMINI_WEB.equals(aiProvider)) {
            radioButtonGeminiWeb.setChecked(true);
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

        updateUiForProvider(radioGroupAiProvider.getCheckedRadioButtonId());
    }

    private void saveSettings() {
        int selectedId = radioGroupAiProvider.getCheckedRadioButtonId();
        String provider = AppPreferences.PROVIDER_OPENAI;
        if (selectedId == R.id.radioButton_gemini) {
            provider = AppPreferences.PROVIDER_GEMINI;
        } else if (selectedId == R.id.radioButton_gemma_local) {
            provider = AppPreferences.PROVIDER_GEMMA_LOCAL;
        } else if (selectedId == R.id.radioButton_gemini_web) {
            provider = AppPreferences.PROVIDER_GEMINI_WEB;
            if (!appPreferences.isGeminiWebLoggedIn()) {
                Toast.makeText(this, "Login is required to use Gemini (Web UI). Please select it again to log in.", Toast.LENGTH_LONG).show();
                return;
            }
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