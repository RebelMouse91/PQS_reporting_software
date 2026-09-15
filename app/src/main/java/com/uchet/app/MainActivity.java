package com.uchet.app;

import android.Manifest;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Оболочка вокруг offline-страницы приложения (assets/index.html).
 * Вся логика — WebView, спрятанные фото хранятся в собственном приватном
 * хранилище приложения (getFilesDir()/vault), куда другим приложениям и
 * галерее доступа нет. Съёмка идёт сразу в тайник, минуя публичную галерею.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PICK_IMAGES = 200;
    private static final int REQ_CAPTURE_IMAGE = 201;
    private static final int REQ_DELETE_CONFIRM = 202;
    private static final int REQ_CAMERA_PERM = 300;
    private static final int REQ_STORAGE_PERM = 301;

    private WebView web;
    private File pendingCaptureFile;
    private List<Uri> pendingDeleteAfterPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);      // нужно для сохранения темы оформления
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse r = serveVaultImage(request.getUrl());
                return r != null ? r : super.shouldInterceptRequest(view, request);
            }
        });
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

    /** «Назад» сначала закрывает просмотр фото / режим выбора / справку, и только потом выходит. */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript(
            "(function(){return (window.handleBack && window.handleBack()) || 'exit';})()",
            new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (value == null || !value.contains("handled")) {
                        finish();
                    }
                }
            });
    }

    // ---------- отдача картинок в WebView через свою схему ----------

    /** Перехватываем запросы вида https://vault.local/thumb/<id> и https://vault.local/full/<id>. */
    private WebResourceResponse serveVaultImage(Uri uri) {
        if (uri == null || !"vault.local".equals(uri.getHost())) return null;
        List<String> seg = uri.getPathSegments();
        if (seg.size() != 2) return null;
        String kind = seg.get(0), id = seg.get(1);
        File f = "thumb".equals(kind) ? new File(thumbDir(), id + ".jpg") : findFullFile(id);
        if (f == null || !f.exists()) {
            return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        }
        try {
            return new WebResourceResponse("image/jpeg", null, new FileInputStream(f));
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        }
    }

    // ---------- хранилище тайника ----------

    private File vaultDir() {
        File d = new File(getFilesDir(), "vault");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private File thumbDir() {
        File d = new File(vaultDir(), ".thumbs");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Файлы тайника называются <uuid>_<поворотГрадусы>_<времяМс>.jpg */
    private File findFullFile(String id) {
        File[] all = vaultDir().listFiles();
        if (all == null) return null;
        for (File f : all) {
            if (f.isFile() && f.getName().startsWith(id + "_")) return f;
        }
        return null;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static int readExifRotationDeg(File f) {
        try {
            ExifInterface exif = new ExifInterface(f.getAbsolutePath());
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** Кладёт файл в тайник и создаёт для него превью (с учётом поворота из EXIF). */
    private boolean importFile(File src) {
        int orient = readExifRotationDeg(src);
        File dst = new File(vaultDir(), newId() + "_" + orient + "_" + System.currentTimeMillis() + ".jpg");
        if (!src.renameTo(dst)) return false;
        makeThumb(dst, orient);
        return true;
    }

    private void makeThumb(File full, int orientDeg) {
        try {
            String id = full.getName().split("_")[0];
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(full.getAbsolutePath(), bounds);
            int side = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (side / sample > 640) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(full.getAbsolutePath(), opts);
            if (bmp == null) return;

            if (orientDeg != 0) {
                Matrix m = new Matrix();
                m.postRotate(orientDeg);
                Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (rotated != bmp) bmp.recycle();
                bmp = rotated;
            }

            File thumb = new File(thumbDir(), id + ".jpg");
            try (OutputStream out = new FileOutputStream(thumb)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out);
            }
            bmp.recycle();
        } catch (Exception ignored) { }
    }

    private static long parseTs(String fileName) {
        try {
            String[] p = fileName.substring(0, fileName.length() - 4).split("_");
            return Long.parseLong(p[2]);
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- съёмка с камеры прямо в тайник ----------

    private void launchCamera() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) == null) {
            notifyJs("error", "Камера недоступна на этом устройстве");
            return;
        }
        try {
            File tmp = new File(getCacheDir(), "capture_" + System.currentTimeMillis() + ".jpg");
            pendingCaptureFile = tmp;
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", tmp);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(intent, REQ_CAPTURE_IMAGE);
        } catch (Exception e) {
            notifyJs("error", "Не удалось открыть камеру");
        }
    }

    // ---------- перенос выбранных фото из галереи в тайник ----------

    private void handlePickedImages(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) {
            notifyJs("cancelled", null);
            return;
        }
        final List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData cd = data.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            notifyJs("cancelled", null);
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                final List<Uri> toDelete = new ArrayList<>();
                for (Uri u : uris) {
                    File tmp = new File(getCacheDir(), newId() + "_pick.jpg");
                    boolean copied = false;
                    try (InputStream in = getContentResolver().openInputStream(u);
                         FileOutputStream out = new FileOutputStream(tmp)) {
                        if (in == null) continue;
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        copied = true;
                    } catch (Exception ignored) { }
                    if (copied && importFile(tmp)) {
                        ok++;
                        toDelete.add(u);
                    } else {
                        tmp.delete();
                    }
                }
                final int okF = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        notifyJs("imported", okF);
                        deleteOriginals(toDelete);
                    }
                });
            }
        }).start();
    }

    /** Убирает исходники из публичной галереи после того, как копия легла в тайник. */
    private void deleteOriginals(List<Uri> uris) {
        if (uris.isEmpty()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), uris);
                startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE_CONFIRM, null, 0, 0, 0, null);
            } catch (Exception ignored) { }
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingDeleteAfterPermission = uris;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE_PERM);
            return;
        }
        for (Uri u : uris) {
            try { getContentResolver().delete(u, null, null); } catch (Exception ignored) { }
        }
    }

    // ---------- восстановление фото обратно в галерею ----------

    private boolean copyToPublicGallery(File src, String id) {
        String name = "IMG_" + id.substring(0, Math.min(8, id.length())) + ".jpg";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return false;
                try (OutputStream out = getContentResolver().openOutputStream(uri);
                     FileInputStream in = new FileInputStream(src)) {
                    if (out == null) return false;
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                return true;
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "PQS");
                if (!dir.exists() && !dir.mkdirs()) return false;
                File dst = new File(dir, name);
                try (FileInputStream in = new FileInputStream(src);
                     FileOutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                MediaScannerConnection.scanFile(this, new String[]{dst.getAbsolutePath()}, null, null);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- обратная связь со страницей ----------

    private void notifyJs(final String type, final Object extra) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", type);
            if (extra instanceof Integer) o.put("count", (int) (Integer) extra);
            else if (extra instanceof String) o.put("message", extra);
        } catch (Exception ignored) { }
        final String json = o.toString();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript("window.onVaultEvent && window.onVaultEvent(" + json + ")", null);
            }
        });
    }

    // ---------- результаты активити ----------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_CAPTURE_IMAGE) {
            File src = pendingCaptureFile;
            pendingCaptureFile = null;
            if (resultCode == RESULT_OK && src != null && src.exists() && src.length() > 0) {
                if (importFile(src)) {
                    notifyJs("imported", 1);
                } else {
                    notifyJs("error", "Не удалось сохранить снимок");
                }
            } else {
                if (src != null) src.delete();
                notifyJs("cancelled", null);
            }
        } else if (requestCode == REQ_PICK_IMAGES) {
            handlePickedImages(resultCode, data);
        } else if (requestCode == REQ_DELETE_CONFIRM) {
            notifyJs("originals-cleared", null);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        if (requestCode == REQ_CAMERA_PERM) {
            if (granted) launchCamera();
            else notifyJs("error", "Нет доступа к камере — разрешите его в настройках приложения");
        } else if (requestCode == REQ_STORAGE_PERM) {
            List<Uri> uris = pendingDeleteAfterPermission;
            pendingDeleteAfterPermission = null;
            if (granted && uris != null) {
                for (Uri u : uris) {
                    try { getContentResolver().delete(u, null, null); } catch (Exception ignored) { }
                }
            }
        }
    }

    // ---------- мост для JS ----------

    private class Bridge {

        @JavascriptInterface
        public String listVault() {
            JSONArray arr = new JSONArray();
            File[] all = vaultDir().listFiles();
            if (all != null) {
                List<File> files = new ArrayList<>();
                for (File f : all) {
                    if (f.isFile() && f.getName().endsWith(".jpg")) files.add(f);
                }
                Collections.sort(files, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Long.compare(parseTs(b.getName()), parseTs(a.getName()));
                    }
                });
                for (File f : files) {
                    String base = f.getName().substring(0, f.getName().length() - 4);
                    String[] parts = base.split("_");
                    if (parts.length != 3) continue;
                    try {
                        JSONObject o = new JSONObject();
                        o.put("id", parts[0]);
                        o.put("orient", Integer.parseInt(parts[1]));
                        o.put("ts", Long.parseLong(parts[2]));
                        arr.put(o);
                    } catch (Exception ignored) { }
                }
            }
            return arr.toString();
        }

        @JavascriptInterface
        public void takePhoto() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(MainActivity.this,
                                new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERM);
                        return;
                    }
                    launchCamera();
                }
            });
        }

        @JavascriptInterface
        public void pickFromGallery() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("image/*");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    try {
                        startActivityForResult(Intent.createChooser(intent, "Выберите фото"), REQ_PICK_IMAGES);
                    } catch (Exception e) {
                        notifyJs("error", "Нет приложения для выбора фото");
                    }
                }
            });
        }

        @JavascriptInterface
        public String deleteVault(String idsJson) {
            int deleted = 0;
            try {
                JSONArray ids = new JSONArray(idsJson);
                for (int i = 0; i < ids.length(); i++) {
                    String id = ids.getString(i);
                    File full = findFullFile(id);
                    if (full != null && full.delete()) deleted++;
                    File thumb = new File(thumbDir(), id + ".jpg");
                    if (thumb.exists()) thumb.delete();
                }
            } catch (Exception ignored) { }
            JSONObject res = new JSONObject();
            try { res.put("deleted", deleted); } catch (Exception ignored) { }
            return res.toString();
        }

        @JavascriptInterface
        public String restoreToGallery(String idsJson) {
            int restored = 0;
            try {
                JSONArray ids = new JSONArray(idsJson);
                for (int i = 0; i < ids.length(); i++) {
                    String id = ids.getString(i);
                    File full = findFullFile(id);
                    if (full == null) continue;
                    if (copyToPublicGallery(full, id)) {
                        restored++;
                        full.delete();
                        new File(thumbDir(), id + ".jpg").delete();
                    }
                }
            } catch (Exception ignored) { }
            JSONObject res = new JSONObject();
            try { res.put("restored", restored); } catch (Exception ignored) { }
            return res.toString();
        }
    }
}
