package pl.fotoraport.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foto Raport – aplikacja w WebView (plik assets/index.html) + natywne dodatki:
 *  - aparat: każde zdjęcie od razu kopiowane do galerii: Pictures/FotoRaport/<stacja>/<nazwa>.jpg
 *  - zapis ZIP do Pobrane/FotoRaport
 *  - przycisk Wstecz obsługiwany przez aplikację
 */
public class MainActivity extends Activity {

    private static final int REQ_CAMERA = 101;
    private static final int REQ_FILE = 102;
    private static final String APP_URL = "https://appassets.androidplatform.net/assets/index.html";

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private File cameraFile;
    private Uri cameraUri;

    // cel kopii zdjęcia z aparatu (ustawia strona przed otwarciem aparatu)
    private volatile String captureSubdir = "";
    private volatile String captureName = "";
    private volatile String lastBackup = "";
    // jakość kopii w galerii: 0 = oryginał, >0 = dłuższy bok w px, -1 = bez kopii
    private volatile int backupMaxSide = 0;
    private final ExecutorService backupExecutor = Executors.newSingleThreadExecutor();

    // pliki zapisywane kawałkami z JavaScriptu
    private final Map<String, Uri> openUris = new HashMap<>();
    private final Map<String, OutputStream> openStreams = new HashMap<>();
    private final Map<String, String> openPaths = new HashMap<>();
    private volatile Uri lastSavedUri = null;
    private volatile String lastSavedMime = "application/zip";
    private int fileCounter = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        // sprzątanie starych plików tymczasowych z aparatu (kopie są już w galerii)
        try {
            File camDir = new File(getCacheDir(), "camera");
            File[] old = camDir.listFiles();
            if (old != null) {
                long limit = System.currentTimeMillis() - 2L * 24 * 3600 * 1000;
                for (File f : old) if (f.lastModified() < limit) f.delete();
            }
        } catch (Exception ignored) { }

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return openChooser(callback, params);
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(APP_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    // ---------------- wybór plików / aparat ----------------

    private boolean openChooser(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
        }
        filePathCallback = callback;

        String[] accept = params.getAcceptTypes();
        boolean image = false;
        boolean nonMime = false;
        if (accept != null) {
            for (String a : accept) {
                if (a == null) continue;
                if (a.startsWith("image")) image = true;
                if (a.startsWith(".")) nonMime = true;
            }
        }

        try {
            if (params.isCaptureEnabled() && image) {
                File dir = new File(getCacheDir(), "camera");
                if (!dir.exists()) dir.mkdirs();
                cameraFile = File.createTempFile("zdjecie_", ".jpg", dir);
                cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", cameraFile);
                Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(cam, REQ_CAMERA);
            } else {
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                if (image && !nonMime) {
                    pick.setType("image/*");
                } else if (accept != null && accept.length > 0 && !nonMime && accept[0] != null && accept[0].length() > 0) {
                    pick.setType(accept[0]);
                } else {
                    pick.setType("*/*");
                }
                if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                    pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                startActivityForResult(pick, REQ_FILE);
            }
            return true;
        } catch (ActivityNotFoundException e) {
            toast("Brak aplikacji aparatu / plików na telefonie.");
        } catch (Exception e) {
            toast("Nie udało się otworzyć: " + e.getMessage());
        }
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (filePathCallback == null) return;
        Uri[] result = null;

        if (requestCode == REQ_CAMERA) {
            if (resultCode == RESULT_OK && cameraFile != null && cameraFile.length() > 0) {
                final File src = cameraFile;
                final String sd = captureSubdir, nm = captureName;
                final int max = backupMaxSide;
                if (max >= 0) backupExecutor.execute(() -> backupCameraPhoto(src, sd, nm, max));
                result = new Uri[]{cameraUri};
            }
        } else if (requestCode == REQ_FILE) {
            if (resultCode == RESULT_OK && data != null) {
                List<Uri> list = new ArrayList<>();
                ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        Uri u = clip.getItemAt(i).getUri();
                        if (u != null) list.add(u);
                    }
                } else if (data.getData() != null) {
                    list.add(data.getData());
                }
                if (!list.isEmpty()) result = list.toArray(new Uri[0]);
            }
        }
        filePathCallback.onReceiveValue(result);
        filePathCallback = null;
    }

    /** Kopia zdjęcia z aparatu do galerii: Pictures/FotoRaport/<stacja>/<nazwa>.jpg (działa w tle). */
    private void backupCameraPhoto(File src, String captureSubdirValue, String captureNameValue, int maxSide) {
        String subdir = clean(captureSubdirValue);
        String name = clean(captureNameValue);
        if (name.isEmpty()) name = "zdjecie_" + System.currentTimeMillis();
        String rel = Environment.DIRECTORY_PICTURES + "/" + getString(R.string.media_dir) + (subdir.isEmpty() ? "" : "/" + subdir);
        ContentResolver cr = getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, name + ".jpg");
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        cv.put(MediaStore.Images.Media.RELATIVE_PATH, rel);
        cv.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
            if (uri == null) throw new Exception("brak dostępu do galerii");
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new Exception("brak dostępu do galerii");
                if (maxSide > 0) {
                    writeScaled(src, out, maxSide);
                } else {
                    try (InputStream in = new FileInputStream(src)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            lastBackup = rel + "/" + name + ".jpg";
        } catch (Throwable e) {
            lastBackup = "";
            if (uri != null) {
                try { cr.delete(uri, null, null); } catch (Exception ignored) { }
            }
            toast("Nie udało się zapisać kopii w galerii: " + e.getMessage());
        }
    }

    /** Pomniejszona kopia (dłuższy bok = maxSide), obrócona zgodnie z EXIF. */
    private static void writeScaled(File src, OutputStream out, int maxSide) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getAbsolutePath(), bounds);
        int w = bounds.outWidth, h = bounds.outHeight;
        if (w <= 0 || h <= 0) throw new Exception("nie można odczytać zdjęcia");
        int sample = 1;
        while (Math.max(w, h) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(src.getAbsolutePath(), opts);
        if (bmp == null) throw new Exception("nie można odczytać zdjęcia");
        float scale = Math.min(1f, (float) maxSide / Math.max(bmp.getWidth(), bmp.getHeight()));
        Matrix m = new Matrix();
        if (scale < 1f) m.postScale(scale, scale);
        int rot = 0;
        try {
            int o = new ExifInterface(src.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
            else if (o == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
            else if (o == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
        } catch (Exception ignored) { }
        if (rot != 0) m.postRotate(rot);
        Bitmap outBmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        try {
            outBmp.compress(Bitmap.CompressFormat.JPEG, 88, out);
        } finally {
            if (outBmp != bmp) outBmp.recycle();
            bmp.recycle();
        }
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]+", "_").trim();
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    // ---------------- przycisk Wstecz ----------------

    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(window.onAndroidBack && window.onAndroidBack()) ? 'yes' : 'no'", value -> {
            if (value == null || !value.contains("yes")) {
                MainActivity.super.onBackPressed();
            }
        });
    }

    // ---------------- most JavaScript <-> Android ----------------

    private class Bridge {

        @JavascriptInterface
        public String info() {
            return "{\"native\":true,\"sdk\":" + android.os.Build.VERSION.SDK_INT + "}";
        }

        @JavascriptInterface
        public void setCaptureTarget(String subdir, String name) {
            captureSubdir = subdir == null ? "" : subdir;
            captureName = name == null ? "" : name;
        }

        @JavascriptInterface
        public void setBackupQuality(int maxSide) {
            backupMaxSide = maxSide;
        }

        @JavascriptInterface
        public String lastBackup() {
            String b = lastBackup;
            lastBackup = "";
            return b;
        }

        /** kind: "download" (Pobrane/FotoRaport) albo "photo" (Pictures/FotoRaport/<subdir>). Zwraca id albo "" przy błędzie. */
        @JavascriptInterface
        public String fileBegin(String kind, String subdir, String name, String mime) {
            try {
                ContentResolver cr = getContentResolver();
                ContentValues cv = new ContentValues();
                String rel;
                Uri collection;
                if ("photo".equals(kind)) {
                    String sd = clean(subdir);
                    rel = Environment.DIRECTORY_PICTURES + "/" + getString(R.string.media_dir) + (sd.isEmpty() ? "" : "/" + sd);
                    collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                } else {
                    rel = Environment.DIRECTORY_DOWNLOADS + "/" + getString(R.string.media_dir);
                    collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                }
                String fname = clean(name);
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fname);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = cr.insert(collection, cv);
                if (uri == null) return "";
                OutputStream out = cr.openOutputStream(uri);
                if (out == null) return "";
                String id;
                synchronized (openUris) {
                    id = "f" + (++fileCounter);
                    openUris.put(id, uri);
                    openStreams.put(id, out);
                    openPaths.put(id, rel + "/" + fname);
                }
                lastSavedMime = mime;
                return id;
            } catch (Exception e) {
                toast("Nie udało się utworzyć pliku: " + e.getMessage());
                return "";
            }
        }

        @JavascriptInterface
        public boolean fileAppend(String id, String base64) {
            OutputStream out;
            synchronized (openUris) { out = openStreams.get(id); }
            if (out == null) return false;
            try {
                out.write(Base64.decode(base64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Zamyka plik. Zwraca ścieżkę widoczną dla użytkownika albo "" przy błędzie. */
        @JavascriptInterface
        public String fileEnd(String id) {
            Uri uri;
            OutputStream out;
            String path;
            synchronized (openUris) {
                uri = openUris.remove(id);
                out = openStreams.remove(id);
                path = openPaths.remove(id);
            }
            if (uri == null || out == null) return "";
            try {
                out.flush();
                out.close();
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);
                lastSavedUri = uri;
                return path == null ? "" : path;
            } catch (Exception e) {
                try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
                return "";
            }
        }

        @JavascriptInterface
        public void fileAbort(String id) {
            Uri uri;
            OutputStream out;
            synchronized (openUris) {
                uri = openUris.remove(id);
                out = openStreams.remove(id);
                openPaths.remove(id);
            }
            try { if (out != null) out.close(); } catch (Exception ignored) { }
            try { if (uri != null) getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
        }

        /** Udostępnij ostatnio zapisany plik (WhatsApp, mail, Dysk…). */
        @JavascriptInterface
        public void shareLast() {
            final Uri uri = lastSavedUri;
            if (uri == null) {
                toast("Najpierw zapisz plik.");
                return;
            }
            runOnUiThread(() -> {
                try {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType(lastSavedMime == null ? "application/zip" : lastSavedMime);
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Wyślij plik"));
                } catch (Exception e) {
                    toast("Nie udało się udostępnić: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void toast(String msg) {
            MainActivity.this.toast(msg);
        }
    }
}
