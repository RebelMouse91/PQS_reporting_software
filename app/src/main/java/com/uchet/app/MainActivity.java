package com.uchet.app;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * Оболочка вокруг offline-страницы приложения.
 * Вся логика учёта живёт в assets/index.html, здесь только WebView,
 * кнопка «назад» и сохранение CSV в папку «Загрузки».
 */
public class MainActivity extends AppCompatActivity {

    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);      // без этого не работает сохранение баланса
        s.setDatabaseEnabled(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        // нужен для работы confirm() и prompt() внутри страницы
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setBackgroundColor(0x00000000);

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl("file:///android_asset/index.html");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    /** «Назад» сначала закрывает окно справки, и только потом выходит из приложения. */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript(
            "(function(){var m=document.getElementById('modal');" +
            "if(m && !m.hidden){document.getElementById('mClose').click();return 'closed';}" +
            "return 'exit';})()",
            new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (value == null || !value.contains("closed")) {
                        finish();
                    }
                }
            });
    }

    private class Bridge {

        /** Страница отдаёт готовый CSV, приложение кладёт его в «Загрузки». */
        @JavascriptInterface
        public void saveCsv(final String fileName, final String content) {
            String where;
            try {
                where = writeToDownloads(fileName, content);
            } catch (Exception e) {
                where = null;
            }
            final String result = where;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this,
                            result != null ? "Сохранено: " + result : "Не удалось сохранить файл",
                            Toast.LENGTH_LONG).show();
                }
            });
        }

        private String writeToDownloads(String fileName, String content) throws Exception {
            byte[] bytes = content.getBytes(Charset.forName("UTF-8"));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return null;
                OutputStream out = getContentResolver().openOutputStream(uri);
                if (out == null) return null;
                out.write(bytes);
                out.close();
                return "Загрузки/" + fileName;
            }

            // Android 7–9: пишем в собственную папку приложения, разрешения не нужны
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (dir == null) return null;
            if (!dir.exists() && !dir.mkdirs()) return null;
            File f = new File(dir, fileName);
            FileOutputStream out = new FileOutputStream(f);
            out.write(bytes);
            out.close();
            return f.getAbsolutePath();
        }
    }
}
