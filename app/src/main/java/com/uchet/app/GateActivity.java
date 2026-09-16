package com.uchet.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatActivity;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Вход в приложение. Снаружи — обычная партия в крестики-нолики; на деле
 * последовательность ходов игрока работает как графический пароль.
 *
 * Сравнение делает Java, а не страница: в JS уходит только ответ «да/нет»,
 * сам секрет и его хеш из WebView недоступны. Хранится солёный SHA-256,
 * исходную комбинацию из файла настроек восстановить нельзя.
 */
public class GateActivity extends AppCompatActivity {

    private static final String PREFS = "pqs_gate";
    private static final String K_SALT = "salt";
    private static final String K_HASH = "hash";

    /** Открыть экран с уже стёртой комбинацией — чтобы задать её заново. */
    public static final String EXTRA_RESET = "reset";

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_RESET, false)) {
            prefs().edit().remove(K_SALT).remove(K_HASH).apply();
        }

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        web.addJavascriptInterface(new Gate(), "Gate");
        web.setBackgroundColor(0xFF0C0D11);
        web.loadUrl("file:///android_asset/gate.html");
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private static byte[] digest(String salt, String seq) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest((salt + "|" + seq).getBytes(Charset.forName("UTF-8")));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16))
                           .append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    private void openVault() {
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    /** Методы вызываются из потока WebView, поэтому переходы уводим в UI-поток. */
    private class Gate {

        @JavascriptInterface
        public boolean hasSecret() {
            return prefs().getString(K_HASH, null) != null;
        }

        @JavascriptInterface
        public void setSecret(String seq) {
            if (seq == null || seq.isEmpty()) return;
            byte[] saltBytes = new byte[16];
            new SecureRandom().nextBytes(saltBytes);
            String salt = hex(saltBytes);
            prefs().edit()
                   .putString(K_SALT, salt)
                   .putString(K_HASH, hex(digest(salt, seq)))
                   .apply();
        }

        @JavascriptInterface
        public boolean check(String seq) {
            String salt = prefs().getString(K_SALT, null);
            String hash = prefs().getString(K_HASH, null);
            if (salt == null || hash == null || seq == null) return false;
            return MessageDigest.isEqual(
                    hex(digest(salt, seq)).getBytes(Charset.forName("UTF-8")),
                    hash.getBytes(Charset.forName("UTF-8")));
        }

        @JavascriptInterface
        public void unlock() {
            runOnUiThread(new Runnable() {
                @Override public void run() { openVault(); }
            });
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("Gate");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
