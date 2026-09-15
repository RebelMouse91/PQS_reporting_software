package com.uchet.app;

import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Заставка при запуске: проигрывает лого-ролик (тёмный или светлый —
 * по системной теме) из res/raw, затем открывает MainActivity.
 * Если видео не воспроизвелось по какой-то причине — открывает
 * MainActivity сразу, приложение не должно зависать на заставке.
 */
public class SplashActivity extends AppCompatActivity {

    private boolean navigated = false;
    private final Handler fallback = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        VideoView video = new VideoView(this);
        setContentView(video);
        video.setOnClickListener(v -> goNext());

        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int resId = night ? R.raw.splash_dark : R.raw.splash_light;

        try {
            Uri uri = Uri.parse("android.resource://" + getPackageName() + "/" + resId);
            video.setVideoURI(uri);
            video.setOnPreparedListener(mp -> {
                mp.setLooping(false);
                video.start();
            });
            video.setOnCompletionListener(mp -> goNext());
            video.setOnErrorListener((mp, what, extra) -> {
                goNext();
                return true;
            });
        } catch (Exception e) {
            goNext();
            return;
        }

        // предохранитель: если видео зависло/не проигралось — всё равно откроем приложение
        fallback.postDelayed(this::goNext, 8000);
    }

    private void goNext() {
        if (navigated) return;
        navigated = true;
        fallback.removeCallbacksAndMessages(null);
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        fallback.removeCallbacksAndMessages(null);
    }
}
