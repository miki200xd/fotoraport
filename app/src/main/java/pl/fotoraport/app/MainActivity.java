package pl.fotoraport.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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

    // --- lokalizacja: dopisywana do zdjęć z aparatu, gdy aparat sam nie zapisał GPS ---
    private static final int REQ_LOCATION = 103;
    private static final long FIX_MAX_AGE_MS = 10 * 60 * 1000;
    private LocationManager locationManager;
    private volatile Location lastFix;
    // wszystkie cztery metody jawnie: na Androidzie 10 nie mają jeszcze domyślnych implementacji
    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) { keepBetterFix(l); }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        @Override public void onProviderEnabled(String provider) { }
        @Override public void onProviderDisabled(String provider) { }
    };
    private Uri cameraUri;

    // cel kopii zdjęcia z aparatu (ustawia strona przed otwarciem aparatu)
    private volatile String captureSubdir = "";
    private volatile String captureName = "";
    private volatile String lastBackup = "";
    // jakość kopii w galerii: 0 = oryginał, >0 = dłuższy bok w px, -1 = bez kopii
    private volatile int backupMaxSide = 0;
    private final ExecutorService backupExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService gpsExecutor = Executors.newSingleThreadExecutor();

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

        if (!hasLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        }

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

    // --- sprawdzanie aktualizacji: najnowsze wydanie na GitHubie (tag „wersja-N”, N = versionCode) ---
    private static final String UPDATE_API = "https://api.github.com/repos/miki200xd/fotoraport/releases/latest";
    // przy uruchomieniu i przy powrocie do aplikacji, ale nie częściej niż co 4 godziny
    private static final long UPDATE_INTERVAL_MS = 4L * 60 * 60 * 1000;
    private static long lastUpdateCheck = 0;
    private AlertDialog updateDialog;

    @Override
    protected void onResume() {
        super.onResume();
        startLocation();
        // powrót z ustawień „instaluj z tego źródła” → pobieranie rusza samo
        if (pendingUpdateUrl != null && getPackageManager().canRequestPackageInstalls()) {
            String u = pendingUpdateUrl;
            pendingUpdateUrl = null;
            downloadAndInstall(u);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastUpdateCheck == 0 || now - lastUpdateCheck > UPDATE_INTERVAL_MS) {
            lastUpdateCheck = now;
            checkForUpdate();
        }
    }

    private void checkForUpdate() {
        final String apkName = "pl.plusdoc.zdjecia".equals(getPackageName()) ? "CellnexNokia.apk" : "FotoRaport.apk";
        new Thread(() -> {
            try {
                final long installed = getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
                HttpURLConnection c = (HttpURLConnection) new URL(UPDATE_API).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (c.getResponseCode() != 200) return;   // brak internetu / repo prywatne → po cichu nic
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                JSONObject rel = new JSONObject(sb.toString());
                String digits = rel.optString("tag_name").replaceAll("[^0-9]", "");
                if (digits.isEmpty()) return;
                final long latest = Long.parseLong(digits);
                if (latest <= installed) return;
                String url = rel.optString("html_url");
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (apkName.equals(a.optString("name"))) url = a.optString("browser_download_url");
                    }
                }
                final String download = url;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (updateDialog != null && updateDialog.isShowing()) return;
                    updateDialog = new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Dostępna aktualizacja")
                            .setMessage("Jest nowa wersja aplikacji (" + latest + "), masz wersję " + installed + ".\n\n"
                                    + "Aplikacja sama ją pobierze i otworzy instalację. Stacje i zdjęcia zostają.")
                            .setPositiveButton("Aktualizuj", (d, w) -> startUpdate(download))
                            .setNegativeButton("Później", null)
                            .show();
                });
            } catch (Exception ignored) { }
        }).start();
    }

    // --- aktualizacja w aplikacji: pobranie APK do pamięci podręcznej i od razu instalator ---
    private String pendingUpdateUrl;

    private void startUpdate(String url) {
        if (url == null || !url.endsWith(".apk")) {   // brak pliku w wydaniu → strona wydania w przeglądarce
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            // jednorazowo: Android musi pozwolić tej aplikacji instalować aktualizacje
            pendingUpdateUrl = url;
            new AlertDialog.Builder(this)
                    .setTitle("Jedno pozwolenie")
                    .setMessage("Za pierwszym razem Android pyta, czy ta aplikacja może instalować aktualizacje.\n\n"
                            + "Włącz „Zezwalaj z tego źródła” i wróć strzałką – pobieranie ruszy samo.")
                    .setPositiveButton("Dalej", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            pendingUpdateUrl = null;
                            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
                        }
                    })
                    .setNegativeButton("Anuluj", (d, w) -> pendingUpdateUrl = null)
                    .show();
            return;
        }
        downloadAndInstall(url);
    }

    private void downloadAndInstall(String url) {
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        bar.setPadding(pad, pad, pad, pad);
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Pobieram aktualizację…")
                .setView(bar)
                .setCancelable(false)
                .show();
        new Thread(() -> {
            File apk = new File(new File(getCacheDir(), "update"), "aktualizacja.apk");
            try {
                File dir = apk.getParentFile();
                if (dir != null) dir.mkdirs();
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                if (c.getResponseCode() != 200) throw new Exception("serwer odpowiedział " + c.getResponseCode());
                final long total = c.getContentLengthLong();
                long done = 0;
                int lastPct = -1;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            final int pct = (int) (done * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                runOnUiThread(() -> { bar.setIndeterminate(false); bar.setProgress(pct); });
                            }
                        }
                    }
                }
                runOnUiThread(() -> {
                    progress.dismiss();
                    try {
                        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(uri, "application/vnd.android.package-archive");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        toast("Nie udało się otworzyć instalatora: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                apk.delete();
                runOnUiThread(() -> {
                    progress.dismiss();
                    toast("Nie udało się pobrać aktualizacji (" + e.getMessage() + "). Sprawdź internet i spróbuj ponownie.");
                });
            }
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLocation();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LOCATION) startLocation();
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocation() {
        if (!hasLocationPermission()) return;
        try {
            if (locationManager == null) locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (locationManager == null) return;
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (!locationManager.isProviderEnabled(p)) continue;
                keepBetterFix(locationManager.getLastKnownLocation(p));
                locationManager.requestLocationUpdates(p, 5000, 0, locationListener);
            }
        } catch (SecurityException | IllegalArgumentException ignored) { }
    }

    private void stopLocation() {
        try {
            if (locationManager != null) locationManager.removeUpdates(locationListener);
        } catch (Exception ignored) { }
    }

    private void keepBetterFix(Location l) {
        if (l == null) return;
        Location cur = lastFix;
        boolean curFresh = cur != null && System.currentTimeMillis() - cur.getTime() < 2 * 60 * 1000;
        // nowsza pozycja wygrywa, chyba że obecna jest świeża i dużo dokładniejsza
        if (cur == null || l.getTime() > cur.getTime() && !(curFresh && l.getAccuracy() > cur.getAccuracy() * 3)) lastFix = l;
    }

    /** Dopisuje GPS do zdjęcia z aparatu, jeśli aparat sam go nie zapisał (wyłączone tagi lokalizacji). */
    private void addGpsIfMissing(File f) {
        Location l = lastFix;
        if (f == null || l == null || System.currentTimeMillis() - l.getTime() > FIX_MAX_AGE_MS) return;
        try {
            ExifInterface ex = new ExifInterface(f.getAbsolutePath());
            if (ex.getLatLong(new float[2])) return;
            double lat = l.getLatitude(), lon = l.getLongitude();
            ex.setAttribute(ExifInterface.TAG_GPS_LATITUDE, dms(lat));
            ex.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, lat >= 0 ? "N" : "S");
            ex.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, dms(lon));
            ex.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, lon >= 0 ? "E" : "W");
            if (l.hasAltitude()) {
                ex.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, Math.round(Math.abs(l.getAltitude()) * 100) + "/100");
                ex.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, l.getAltitude() >= 0 ? "0" : "1");
            }
            ex.saveAttributes();
        } catch (Exception ignored) { }
    }

    private static String dms(double v) {
        v = Math.abs(v);
        int d = (int) v;
        double mf = (v - d) * 60;
        int m = (int) mf;
        long s = Math.round((mf - m) * 60 * 10000);
        return d + "/1," + m + "/1," + s + "/10000";
    }

    /** Przenosi GPS, datę i aparat z oryginału do pomniejszonej kopii (obrót jest już w pikselach). */
    private static void copyExif(File from, File to) {
        String[] tags = {
                ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
                ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL};
        try {
            ExifInterface a = new ExifInterface(from.getAbsolutePath());
            ExifInterface b = new ExifInterface(to.getAbsolutePath());
            for (String t : tags) {
                String v = a.getAttribute(t);
                if (v != null) b.setAttribute(t, v);
            }
            b.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
            b.saveAttributes();
        } catch (Exception ignored) { }
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
                // dopisanie GPS przepisuje cały plik – w tle, żeby ekran nie stawał; zdjęcie trafia do aplikacji zaraz potem
                final File src = cameraFile;
                final Uri uri = cameraUri;
                final String sd = captureSubdir, nm = captureName;
                final int max = backupMaxSide;
                final ValueCallback<Uri[]> cb = filePathCallback;
                filePathCallback = null;
                gpsExecutor.execute(() -> {
                    addGpsIfMissing(src);
                    runOnUiThread(() -> cb.onReceiveValue(new Uri[]{uri}));
                    if (max >= 0) backupExecutor.execute(() -> backupCameraPhoto(src, sd, nm, max));
                });
                return;
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
                    // pomniejszenie gubi EXIF, więc najpierw plik tymczasowy, do niego GPS i data z oryginału
                    File tmp = File.createTempFile("kopia_", ".jpg", getCacheDir());
                    try {
                        try (OutputStream t = new FileOutputStream(tmp)) { writeScaled(src, t, maxSide); }
                        copyExif(src, tmp);
                        try (InputStream in = new FileInputStream(tmp)) {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        }
                    } finally {
                        tmp.delete();
                    }
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
