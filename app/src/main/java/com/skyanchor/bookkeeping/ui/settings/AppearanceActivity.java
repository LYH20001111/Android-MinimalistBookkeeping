package com.skyanchor.bookkeeping.ui.settings;

import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.UserSettingsEntity;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.databinding.ActivityAppearanceBinding;
import com.skyanchor.bookkeeping.util.InsetsUtil;
import com.skyanchor.bookkeeping.util.ThemeStore;

/**
 * 外观设置（V1 基线第 9 章）。
 *
 * <p>V1 只提供「浅色」与「跟随系统」两项，不含独立的深色开关——深色外观由系统设置驱动。
 * Room 里的 {@code user_settings.theme} 是唯一事实来源，{@link ThemeStore} 只是它的镜像缓存，
 * 供 {@code BookkeepingApp} 冷启动时同步应用，避免第一个 Activity 创建前无法查库。
 */
public class AppearanceActivity extends AppCompatActivity {

    private ActivityAppearanceBinding binding;
    private BookkeepingRepository repository;

    /** 当前生效的主题，用于忽略对同一项的重复点击。 */
    @NonNull
    private String currentTheme = UserSettingsEntity.THEME_LIGHT;

    /** 程序化回填单选态时置位，避免监听器把同一个值再写一次库。 */
    private boolean updatingGroup;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAppearanceBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applySystemBarsPadding(binding.appearanceRoot);
        InsetsUtil.syncSystemBarAppearance(this);

        repository = BookkeepingApp.get(this).getRepository();
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        // 先用镜像缓存立刻画出选中态，等 LiveData 到达后再以库里的值为准
        currentTheme = ThemeStore.get(this);
        checkTheme(currentTheme);

        binding.themeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (updatingGroup) {
                return;
            }
            applyTheme(checkedId == R.id.themeSystem
                    ? UserSettingsEntity.THEME_SYSTEM : UserSettingsEntity.THEME_LIGHT);
        });

        repository.observeSettings().observe(this, settings -> {
            if (settings == null) {
                return;
            }
            currentTheme = settings.theme;
            checkTheme(settings.theme);
        });
    }

    /**
     * 切换主题。
     *
     * <p>{@code setDefaultNightMode} 会重建 Activity，因此必须先把镜像缓存写好再切换，
     * 否则重建后的进程状态读到的仍是旧主题，出现「切了又弹回去」的现象。
     */
    private void applyTheme(@NonNull String theme) {
        if (theme.equals(currentTheme)) {
            return;
        }
        currentTheme = theme;
        checkTheme(theme);

        ThemeStore.put(this, theme);
        repository.setTheme(theme, null);
        AppCompatDelegate.setDefaultNightMode(ThemeStore.nightMode(theme));
        Toast.makeText(this, R.string.appearance_saved, Toast.LENGTH_SHORT).show();
    }

    private void checkTheme(@NonNull String theme) {
        updatingGroup = true;
        binding.themeGroup.check(UserSettingsEntity.THEME_SYSTEM.equals(theme)
                ? R.id.themeSystem : R.id.themeLight);
        updatingGroup = false;
    }
}
