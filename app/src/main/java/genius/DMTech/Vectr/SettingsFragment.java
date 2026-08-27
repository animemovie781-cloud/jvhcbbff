package genius.DMTech.Vectr;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;

public class SettingsFragment extends Fragment {

    private VectrDropdown dropProvider, dropModel, dropTheme, dropLanguage, dropCmdRunner;
    private EditText editApiKey, editApiBaseUrl, editCustomModelName, editMaxTokens, editSystemPrompt;
    private Button btnSavePreset;
    private RecyclerView presetList;
    private LinearLayout tokenChips, compatBlock, customModelBlock, deepseekModelBlock, settingsRoot;
    private TextView activeModelTitle, activeModelMeta, trustWhitelistEmpty, btnClearWhitelist;
    private View settingsHero;
    private SwitchMaterial switchAutoAcceptEdits;
    private ChipGroup trustWhitelistChips;

    private PresetAdapter presetAdapter;
    private PresetRepository presetRepo;

    private boolean suppressSave = false;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindViews(view);
        setupDropdowns();
        buildTokenChips();
        suppressSave = true;
        loadSettings();
        suppressSave = false;
        setupAutoSave();
        setupListeners();
        loadPresets();
        refreshHero();
        updateConditionalVisibility(false);
        Anim.staggerChildren(settingsRoot, 40, 45);
        if (settingsHero != null) Anim.fadeSlideIn(settingsHero, 0);
    }

    @Override
    public void onResume() {
        super.onResume();
        suppressSave = true;
        loadSettings();
        suppressSave = false;
        refreshHero();
        updateConditionalVisibility(false);
    }

    @Override
    public void onPause() {
        if (dropProvider != null) dropProvider.dismiss();
        if (dropModel != null) dropModel.dismiss();
        if (dropTheme != null) dropTheme.dismiss();
        if (dropLanguage != null) dropLanguage.dismiss();
        if (dropCmdRunner != null) dropCmdRunner.dismiss();
        super.onPause();
    }

    private void bindViews(View view) {
        settingsRoot = view.findViewById(R.id.settings_root);
        settingsHero = view.findViewById(R.id.settings_hero);
        activeModelTitle = view.findViewById(R.id.active_model_title);
        activeModelMeta = view.findViewById(R.id.active_model_meta);

        dropProvider = new VectrDropdown(requireContext(), view.findViewById(R.id.field_provider));
        dropModel = new VectrDropdown(requireContext(), view.findViewById(R.id.field_model));
        dropTheme = new VectrDropdown(requireContext(), view.findViewById(R.id.field_theme));
        dropLanguage = new VectrDropdown(requireContext(), view.findViewById(R.id.field_language));
        dropCmdRunner = new VectrDropdown(requireContext(), view.findViewById(R.id.field_cmd_runner));

        editApiKey = view.findViewById(R.id.edit_api_key);
        editApiBaseUrl = view.findViewById(R.id.edit_api_base_url);
        editCustomModelName = view.findViewById(R.id.edit_custom_model_name);
        editMaxTokens = view.findViewById(R.id.edit_max_tokens);
        editSystemPrompt = view.findViewById(R.id.edit_system_prompt);
        switchAutoAcceptEdits = view.findViewById(R.id.switch_auto_accept_edits);
        trustWhitelistChips = view.findViewById(R.id.trust_whitelist_chips);
        trustWhitelistEmpty = view.findViewById(R.id.trust_whitelist_empty);
        btnClearWhitelist = view.findViewById(R.id.btn_clear_whitelist);

        btnSavePreset = view.findViewById(R.id.btn_save_preset);
        presetList = view.findViewById(R.id.preset_list);
        tokenChips = view.findViewById(R.id.token_chips);
        compatBlock = view.findViewById(R.id.compat_block);
        customModelBlock = view.findViewById(R.id.custom_model_block);
        deepseekModelBlock = view.findViewById(R.id.deepseek_model_block);
    }

    private void setupDropdowns() {
        List<VectrDropdown.Option> providers = new ArrayList<>();
        providers.add(new VectrDropdown.Option(
                getString(R.string.provider_deepseek),
                getString(R.string.provider_deepseek_sub), null, false));
        providers.add(new VectrDropdown.Option(
                getString(R.string.provider_ollama),
                getString(R.string.provider_ollama_sub),
                null, false));
        providers.add(new VectrDropdown.Option(
                getString(R.string.provider_openrouter),
                getString(R.string.provider_openrouter_sub),
                getString(R.string.badge_any), true));
        providers.add(new VectrDropdown.Option(
                getString(R.string.provider_openai_compat),
                getString(R.string.provider_compat_sub),
                getString(R.string.badge_any), true));
        dropProvider.setOptions(providers);

        List<VectrDropdown.Option> models = new ArrayList<>();
        int providerIdx = dropProvider.getSelectedIndex();
        for (AiModels.ModelOption m : AiModels.getModelsForProvider(providerIdx)) {
            models.add(new VectrDropdown.Option(
                    m.custom ? getString(R.string.model_custom) : (""
                            + AiModels.PROVIDERS[providerIdx] + " " + m.title),
                    resolveModelSubtitle(providerIdx, m),
                    resolveModelBadge(m),
                    m.large));
        }
        dropModel.setOptions(models);

        List<VectrDropdown.Option> themes = new ArrayList<>();
        themes.add(new VectrDropdown.Option(getString(R.string.theme_dark)));
        themes.add(new VectrDropdown.Option(getString(R.string.theme_light)));
        themes.add(new VectrDropdown.Option(getString(R.string.theme_system)));
        dropTheme.setOptions(themes);

        List<VectrDropdown.Option> langs = new ArrayList<>();
        langs.add(new VectrDropdown.Option(getString(R.string.lang_russian)));
        langs.add(new VectrDropdown.Option(getString(R.string.lang_english)));
        langs.add(new VectrDropdown.Option(getString(R.string.lang_hindi)));
        dropLanguage.setOptions(langs);

        List<VectrDropdown.Option> runners = new ArrayList<>();
        runners.add(new VectrDropdown.Option(getString(R.string.settings_runner_shell)));
        runners.add(new VectrDropdown.Option(getString(R.string.settings_runner_termux)));
        dropCmdRunner.setOptions(runners);

        dropProvider.setListener((index, option) -> {
            saveSettings();
            refreshModelDropdown(index);
            updateConditionalVisibility(true);
            refreshHero();
        });
        dropModel.setListener((index, option) -> {
            saveSettings();
            updateConditionalVisibility(true);
            int pidx = dropProvider.getSelectedIndex();
            AiModels.ModelOption m = AiModels.byIndex(pidx, index);
            if (m.large && editMaxTokens != null) {
                int cur;
                try { cur = Integer.parseInt(editMaxTokens.getText().toString().trim()); }
                catch (Exception e) { cur = 0; }
                if (cur < m.suggestedMaxTokens) {
                    editMaxTokens.setText(String.valueOf(m.suggestedMaxTokens));
                    highlightTokenChip(m.suggestedMaxTokens);
                }
            }
            refreshHero();
        });
        dropTheme.setListener((index, option) -> {
            if (suppressSave) return;
            saveSettings();
            ThemeHelper.setThemeIndex(index);
            ThemeHelper.apply(index);
            Toast.makeText(requireContext(), R.string.toast_theme_restart, Toast.LENGTH_SHORT).show();
            requireActivity().recreate();
        });
        dropLanguage.setListener((index, option) -> {
            if (suppressSave) return;
            saveSettings();
            LocaleHelper.setLangIndex(index);
            Toast.makeText(requireContext(), R.string.toast_language_restart, Toast.LENGTH_SHORT).show();
            requireActivity().recreate();
        });
        dropCmdRunner.setListener((index, option) -> {
            if (suppressSave) return;
            AgentTrust.setAutoRunner(index == 1
                    ? AgentTrust.RUNNER_TERMUX : AgentTrust.RUNNER_SHELL);
        });
    }

    private String resolveModelSubtitle(AiModels.ModelOption m) {
        return resolveModelSubtitle(0, m);
    }

    private String resolveModelSubtitle(int providerIndex, AiModels.ModelOption m) {
        if (m.custom) return getString(R.string.model_custom_sub);
        int pi = providerIndex < 0 ? 0 : providerIndex;
        switch (pi) {
            case 1:
                if ("llama3.2".equals(m.id)) return getString(R.string.model_ollama_llama32_sub);
                if ("llama3.1".equals(m.id)) return getString(R.string.model_ollama_llama31_sub);
                if ("qwen2.5-coder".equals(m.id)) return getString(R.string.model_ollama_qwen_sub);
                if ("mistral".equals(m.id)) return getString(R.string.model_ollama_mistral_sub);
                if ("codellama".equals(m.id)) return getString(R.string.model_ollama_codellama_sub);
                break;
            case 2:
                if ("openai/gpt-4o".equals(m.id)) return getString(R.string.model_or_gpt4o_sub);
                if ("anthropic/claude-3.5-sonnet".equals(m.id)) return getString(R.string.model_or_claude_sub);
                if ("google/gemini-2.0-flash-001".equals(m.id)) return getString(R.string.model_or_gemini_sub);
                if ("meta-llama/llama-3.1-405b-instruct".equals(m.id)) return getString(R.string.model_or_llama_sub);
                if ("mistralai/mistral-large".equals(m.id)) return getString(R.string.model_or_mistral_sub);
                break;
            default:
                if ("deepseek-v4-flash".equals(m.id)) return getString(R.string.model_flash_sub);
                if ("deepseek-v4-pro".equals(m.id)) return getString(R.string.model_pro_sub);
                if ("deepseek-chat".equals(m.id)) return getString(R.string.model_chat_sub);
                if ("deepseek-reasoner".equals(m.id)) return getString(R.string.model_reasoner_sub);
                break;
        }
        return m.subtitle != null ? m.subtitle : "";
    }

    private String resolveModelBadge(AiModels.ModelOption m) {
        if (m.badge == null) return null;
        switch (m.badge) {
            case "SPEED": return getString(R.string.badge_speed);
            case "LARGE": return getString(R.string.badge_large);
            case "DEPRECATED": return getString(R.string.badge_deprecated);
            case "CUSTOM": return getString(R.string.badge_custom);
            default: return m.badge;
        }
    }

    private void buildTokenChips() {
        tokenChips.removeAllViews();
        float dens = getResources().getDisplayMetrics().density;
        for (int tokens : AiModels.TOKEN_PRESETS) {
            TextView chip = new TextView(requireContext());
            chip.setText(formatTokens(tokens));
            chip.setTextSize(12f);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding((int) (12 * dens), (int) (8 * dens), (int) (12 * dens), (int) (8 * dens));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) (8 * dens));
            chip.setLayoutParams(lp);
            chip.setTag(tokens);
            chip.setOnClickListener(v -> {
                int val = (int) v.getTag();
                editMaxTokens.setText(String.valueOf(val));
                highlightTokenChip(val);
                Anim.pulseSelect(v);
                saveSettings();
                refreshHero();
            });
            tokenChips.addView(chip);
        }
    }

    private String formatTokens(int n) {
        if (n >= 1000) return (n / 1000) + "K";
        return String.valueOf(n);
    }

    private void highlightTokenChip(int selected) {
        for (int i = 0; i < tokenChips.getChildCount(); i++) {
            View c = tokenChips.getChildAt(i);
            boolean on = c.getTag() instanceof Integer && (int) c.getTag() == selected;
            c.setBackgroundResource(on ? R.drawable.bg_chip_token_selected : R.drawable.bg_chip_token);
            if (c instanceof TextView) {
                ((TextView) c).setTextColor(requireContext().getColor(on ? R.color.accent : R.color.text_secondary));
            }
        }
    }

    private void updateConditionalVisibility(boolean animate) {
        int providerIdx = dropProvider.getSelectedIndex();
        boolean compat = providerIdx >= 1;
        boolean customModel = compat || AiModels.byIndex(providerIdx, dropModel.getSelectedIndex()).custom;

        if (deepseekModelBlock != null) {
            deepseekModelBlock.setVisibility(providerIdx == 0 ? View.VISIBLE : View.GONE);
        }

        setBlockVisible(compatBlock, compat, animate);
        setBlockVisible(customModelBlock, customModel, animate);
    }

    private void refreshModelDropdown(int providerIndex) {
        List<VectrDropdown.Option> models = new ArrayList<>();
        for (AiModels.ModelOption m : AiModels.getModelsForProvider(providerIndex)) {
            models.add(new VectrDropdown.Option(
                    m.custom ? getString(R.string.model_custom) : (""
                            + AiModels.PROVIDERS[providerIndex] + " " + m.title),
                    resolveModelSubtitle(providerIndex, m),
                    resolveModelBadge(m),
                    m.large));
        }
        dropModel.setOptions(models);
        int savedModelIdx = 0;
        if (SecurePrefsProvider.get() != null) {
            savedModelIdx = SecurePrefsProvider.get().getInt("model_index", 0);
        }
        int modelCount = AiModels.getModelCount(providerIndex);
        if (savedModelIdx < 0 || savedModelIdx >= modelCount) savedModelIdx = 0;
        dropModel.setSelectedIndex(savedModelIdx);
    }

    private void setBlockVisible(View block, boolean show, boolean animate) {
        if (block == null) return;
        boolean visible = block.getVisibility() == View.VISIBLE;
        if (show == visible) return;
        if (!animate) {
            block.setVisibility(show ? View.VISIBLE : View.GONE);
            return;
        }
        if (show) Anim.expand(block);
        else Anim.collapse(block);
    }

    private void loadSettings() {
        SharedPreferences prefs = SecurePrefsProvider.get();
        if (prefs == null) {
            Toast.makeText(requireContext(), "Ошибка: хранилище недоступно", Toast.LENGTH_SHORT).show();
            return;
        }

        editApiKey.setText(prefs.getString("api_key", ""));
        editCustomModelName.setText(prefs.getString("custom_model_name", ""));
        // Default API base URL based on provider
        String provider = AiConfig.getProvider(requireContext());
        if (prefs.contains("api_base_url")) {
            editApiBaseUrl.setText(prefs.getString("api_base_url", ""));
        } else {
            switch (provider) {
                case "ollama":
                    editApiBaseUrl.setText("http://localhost:11434/v1");
                    break;
                case "openrouter":
                    editApiBaseUrl.setText("https://openrouter.ai/api/v1");
                    break;
                case "openai_compat":
                    editApiBaseUrl.setText("https://api.openai.com/v1");
                    break;
                case "deepseek":
                default:
                    editApiBaseUrl.setText("https://api.deepseek.com");
                    break;
            }
        }
        int maxTok = prefs.getInt("max_tokens", 8192);
        editMaxTokens.setText(String.valueOf(maxTok));
        highlightTokenChip(maxTok);
        editSystemPrompt.setText(prefs.getString("system_prompt", ""));

        int providerIdx = prefs.getInt("provider_index", 0);
        dropProvider.setSelectedIndex(providerIdx);
        refreshModelDropdown(providerIdx);
        dropModel.setSelectedIndex(prefs.getInt("model_index", 0));
        dropTheme.setSelectedIndex(prefs.getInt("theme_index", 0));
        dropLanguage.setSelectedIndex(prefs.getInt("lang_index", 0));

        if (switchAutoAcceptEdits != null) {
            switchAutoAcceptEdits.setOnCheckedChangeListener(null);
            switchAutoAcceptEdits.setChecked(AgentTrust.isAutoAcceptEdits());
            switchAutoAcceptEdits.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressSave) return;
                AgentTrust.setAutoAcceptEdits(isChecked);
            });
        }
        if (dropCmdRunner != null) {
            dropCmdRunner.setSelectedIndex(
                    AgentTrust.RUNNER_TERMUX.equals(AgentTrust.getAutoRunner()) ? 1 : 0);
        }
        refreshWhitelistChips();
    }

    private void setupAutoSave() {
        TextWatcher saver = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (suppressSave) return;
                saveSettings();
                refreshHero();
                if (s == editMaxTokens.getText()) {
                    try {
                        highlightTokenChip(Integer.parseInt(s.toString().trim()));
                    } catch (Exception ignored) {}
                }
            }
        };

        editApiKey.addTextChangedListener(saver);
        editApiBaseUrl.addTextChangedListener(saver);
        editCustomModelName.addTextChangedListener(saver);
        editMaxTokens.addTextChangedListener(saver);
        editSystemPrompt.addTextChangedListener(saver);
    }

    private void setupListeners() {
        btnSavePreset.setOnClickListener(v -> {
            Anim.pulseSelect(v);
            savePresetDialog();
        });
        if (btnClearWhitelist != null) {
            btnClearWhitelist.setOnClickListener(v -> {
                AgentTrust.clearWhitelist();
                refreshWhitelistChips();
            });
        }
    }

    private void refreshWhitelistChips() {
        if (trustWhitelistChips == null) return;
        trustWhitelistChips.removeAllViews();
        List<String> list = AgentTrust.getWhitelist();
        if (trustWhitelistEmpty != null) {
            trustWhitelistEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (btnClearWhitelist != null) {
            btnClearWhitelist.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        }
        for (String bin : list) {
            Chip chip = new Chip(requireContext());
            chip.setText(bin);
            chip.setClickable(true);
            chip.setCheckable(false);
            chip.setOnClickListener(v -> {
                AgentTrust.removeBinary(bin);
                refreshWhitelistChips();
            });
            trustWhitelistChips.addView(chip);
        }
    }

    private void saveSettings() {
        if (suppressSave) return;
        SharedPreferences prefs = SecurePrefsProvider.get();
        if (prefs == null) return;

        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("api_key", editApiKey.getText().toString());
        editor.putString("api_base_url", editApiBaseUrl.getText().toString().trim());
        editor.putString("custom_model_name", editCustomModelName.getText().toString().trim());
        editor.putString("system_prompt", editSystemPrompt.getText().toString());
        editor.putInt("provider_index", dropProvider.getSelectedIndex());
        editor.putInt("model_index", dropModel.getSelectedIndex());
        editor.putInt("theme_index", dropTheme.getSelectedIndex());
        editor.putInt("lang_index", dropLanguage.getSelectedIndex());

        try {
            editor.putInt("max_tokens", Integer.parseInt(editMaxTokens.getText().toString().trim()));
        } catch (NumberFormatException e) {
            editor.putInt("max_tokens", 8192);
        }
        editor.apply();
    }

    private void refreshHero() {
        if (activeModelTitle == null) return;
        int providerIdx = dropProvider.getSelectedIndex();
        activeModelTitle.setText(AiConfig.getDisplayName(requireContext()));
        activeModelMeta.setText(AiConfig.getModelId(requireContext())
                + " · " + AiConfig.getMaxTokens(requireContext()) + " tokens"
                + (providerIdx >= 1 && providerIdx <= 3 ? " · compat" : ""));
    }

    private void loadPresets() {
        presetRepo = new PresetRepository(requireContext());
        new Thread(() -> {
            List<PresetRepository.Preset> presets = presetRepo.getAll();
            if (getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                presetAdapter = new PresetAdapter(presets, this::applyPreset);
                presetList.setLayoutManager(new LinearLayoutManager(requireContext()));
                presetList.setAdapter(presetAdapter);
            });
        }).start();
    }

    private void applyPreset(PresetRepository.Preset preset) {
        suppressSave = true;
        editSystemPrompt.setText(preset.systemPrompt);
        editMaxTokens.setText(String.valueOf(preset.maxTokens));
        highlightTokenChip(preset.maxTokens);
        if (preset.provider != null && !preset.provider.isEmpty()) {
            int providerIdx = 0;
            for (int i = 0; i < AiModels.PROVIDERS.length; i++) {
                if (preset.provider.equalsIgnoreCase(AiModels.PROVIDERS[i])) {
                    providerIdx = i;
                    break;
                }
            }
            dropProvider.setSelectedIndex(providerIdx);
        }
        if (preset.model != null && !preset.model.isEmpty()) {
            boolean matched = false;
            for (int i = 0; i < AiModels.DEEPSEEK.length; i++) {
                AiModels.ModelOption m = AiModels.DEEPSEEK[i];
                if (!m.custom && (preset.model.equals(m.id) || preset.model.contains(m.id))) {
                    dropModel.setSelectedIndex(i);
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                dropModel.setSelectedIndex(AiModels.DEEPSEEK.length - 1);
                editCustomModelName.setText(preset.model);
            }
        }
        suppressSave = false;
        saveSettings();
        updateConditionalVisibility(true);
        refreshHero();
        Toast.makeText(requireContext(), "Пресет «" + preset.name + "»", Toast.LENGTH_SHORT).show();
    }

    private void savePresetDialog() {
        EditText input = new EditText(requireContext());
        input.setHint("Имя пресета");
        input.setTextColor(requireContext().getColor(R.color.text_primary));
        input.setHintTextColor(requireContext().getColor(R.color.text_tertiary));
        input.setBackgroundResource(R.drawable.bg_input_field);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(requireContext())
                .setTitle("Сохранить пресет")
                .setView(input)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    PresetRepository.Preset p = new PresetRepository.Preset();
                    p.name = name;
                    p.provider = AiModels.PROVIDERS[dropProvider.getSelectedIndex()];
                    p.model = AiConfig.getModelId(requireContext());
                    p.systemPrompt = editSystemPrompt.getText().toString();
                    try {
                        p.maxTokens = Integer.parseInt(editMaxTokens.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        p.maxTokens = 8192;
                    }
                    new Thread(() -> {
                        presetRepo.save(p);
                        if (getActivity() == null) return;
                        requireActivity().runOnUiThread(() -> {
                            loadPresets();
                            Toast.makeText(requireContext(), "Пресет сохранён", Toast.LENGTH_SHORT).show();
                        });
                    }).start();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static class PresetAdapter extends RecyclerView.Adapter<PresetAdapter.Holder> {
        private final List<PresetRepository.Preset> items;
        private final OnPresetClick listener;

        interface OnPresetClick {
            void onClick(PresetRepository.Preset p);
        }

        PresetAdapter(List<PresetRepository.Preset> items, OnPresetClick listener) {
            this.items = items;
            this.listener = listener;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            float dens = parent.getResources().getDisplayMetrics().density;
            tv.setPadding((int) (14 * dens), (int) (12 * dens), (int) (14 * dens), (int) (12 * dens));
            tv.setTextColor(parent.getContext().getColor(R.color.text_primary));
            tv.setTextSize(14f);
            tv.setBackgroundResource(R.drawable.bg_spinner_field);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (8 * dens);
            tv.setLayoutParams(lp);
            return new Holder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            PresetRepository.Preset p = items.get(position);
            holder.textView.setText(p.name + (p.model != null ? "  ·  " + p.model : ""));
            holder.itemView.setOnClickListener(v -> {
                Anim.pulseSelect(v);
                listener.onClick(p);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final TextView textView;
            Holder(TextView tv) {
                super(tv);
                textView = tv;
            }
        }
    }
}
