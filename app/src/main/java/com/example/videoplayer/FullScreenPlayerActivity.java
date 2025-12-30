package com.example.videoplayer;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.text.SimpleDateFormat;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Full-screen player:
 *  - Polls server every 60s
 *  - If download_status == false → download videos into /sdcard/video_new,
 *    atomically swap into /sdcard/video, then POST status=true
 *  - Plays local MP4s in a loop with ExoPlayer
 *  - Sends online heartbeat every 60s
 *  - Connects via BLE (Nordic UART service) to ESP32 "ESP32_PLAYER_CTRL_BLE"
 *    and reacts to PLAY / PAUSE / NEXT commands.
 *  - Reads temperature lines from ESP32 and sends them to
 *    /device/{android_id}/temperature_update on the server.
 *  - On REED: OPEN (door open), reads counts, increments daily & monthly,
 *    and posts them to /daily_update and /monthly_update.
 *  - Auto-reconnects to ESP32 if BLE disconnects or scan fails.
 */
@OptIn(markerClass = UnstableApi.class)
@SuppressLint("MissingPermission")
public class FullScreenPlayerActivity extends AppCompatActivity {

    // ===== Server base & endpoints =====
    private static final String API_BASE = "http://34.248.112.237:8005";

    private static String listDownloadsUrl(String androidId) {
        return API_BASE + "/device/" + androidId + "/videos/downloads";
    }

    private static String readStatusUrl(String androidId) {
        return API_BASE + "/device/" + androidId + "/download_status";
    }

    private static String updateStatusUrl(String androidId) {
        return API_BASE + "/device/" + androidId + "/download_update";
    }

    private static String updateOnlineUrl(String androidId) {
        return API_BASE + "/device/" + androidId + "/online_update";
    }

    private static String updateTemperatureUrl(String androidId) {
        return API_BASE + "/device/" + androidId + "/temperature_update";
    }

    private volatile boolean isWorking = false;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());

    // ADD THIS:
    private volatile boolean downloadInProgress = false;

    private static String countsUrl(String androidId) {
        return API_BASE + "/device/" + androidId + "/counts";
    }

    private static String dailyUpdateUrl(String androidId) {
        return API_BASE + "/device/" + androidId + "/daily_update";
    }

    private static String monthlyUpdateUrl(String androidId) {
        return API_BASE + "/device/" + androidId + "/monthly_update";
    }

    // ===== Local storage =====
    private static final String ROOT_DIR = "video";      // /sdcard/video
    private static final String TEMP_DIR = "video_new";  // /sdcard/video_new
    private static final int MAX_RETRIES = 5;

    // ===== Polling (every 60 seconds) =====
    private static final long POLL_MS = 60_000L;

    private PlayerView playerView;
    private ExoPlayer player;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<String> legacyPermLauncher;



    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            sendOnlineHeartbeat();
            startBackgroundCheckIfNeeded();
            pollHandler.postDelayed(this, POLL_MS);
        }
    };

    // ===== Bluetooth (ESP32 over BLE, Nordic UART Service) =====
    private static final String ESP32_DEVICE_NAME = "ESP32_PLAYER_CTRL_BLE";
    private static final int REQ_BT_PERMS = 2001;

    private static final UUID NUS_SERVICE_UUID =
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NUS_CHAR_RX_UUID =
            UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"); // Android -> ESP32
    private static final UUID NUS_CHAR_TX_UUID =
            UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"); // ESP32 -> Android

    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt btGatt;
    private BluetoothGattCharacteristic nusTxChar; // ESP32 -> Android
    private BluetoothGattCharacteristic nusRxChar; // Android -> ESP32

    private final Handler bleHandler = new Handler(Looper.getMainLooper());

    private volatile boolean btShouldReconnect = true;
    private volatile boolean bleScanning = false;

    // Regex to extract first number from "Temperature: 23.45 °C"
    private static final Pattern TEMP_PATTERN = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");

    // Last temperature we got from ESP32
    private volatile Float lastTemperatureValue = null;

    // Temperature POST timer (every 5 seconds)
    private final Handler tempHandler = new Handler(Looper.getMainLooper());
    private final long TEMP_POST_INTERVAL_MS = 5_000L;

    private final Runnable tempPostRunnable = new Runnable() {
        @Override
        public void run() {
            Float temp = lastTemperatureValue;
            if (temp != null) {
                float currentTemp = temp; // snapshot
                Log.d("BLE_DEBUG", "Timer sending temperature: " + currentTemp);
                // send latest value to server every 5 seconds
                sendTemperatureToServer(currentTemp);
            } else {
                Log.d("BLE_DEBUG", "Timer: no temperature yet, skipping.");
            }

            // schedule next run
            tempHandler.postDelayed(this, TEMP_POST_INTERVAL_MS);
        }
    };

    // Small holder for counts
    private static class DeviceCounts {
        int daily;
        int monthly;
    }

    // ===== Lifecycle =====

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_fullscreen_player);

        playerView = findViewById(R.id.playerView);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        playerView.setUseController(false);
        applyImmersive();

        Toast.makeText(this, "Android ID: " + getAndroidId(), Toast.LENGTH_LONG).show();

        legacyPermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) startEverything();
                    else toast("Storage permission denied.");
                }
        );

        ensureAllFilesAccessThenStart();

        // Start periodic temperature posting (every 5 seconds)
        tempHandler.postDelayed(tempPostRunnable, TEMP_POST_INTERVAL_MS);

        // Init Bluetooth + auto-connect (BLE)
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null) {
            toast("Bluetooth not supported on this device");
        } else {
            ensureBluetoothPermissionAndConnect();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        pollHandler.removeCallbacksAndMessages(null);
        pollHandler.postDelayed(pollRunnable, POLL_MS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pollHandler.removeCallbacksAndMessages(null);

        btShouldReconnect = false;
        stopBleScan();

        if (btGatt != null) {
            try {
                btGatt.close();
            } catch (Exception ignored) {
            }
            btGatt = null;
        }

        if (player != null) {
            player.release();
            player = null;
        }

        // Stop temperature timer
        tempHandler.removeCallbacksAndMessages(null);
    }

    // ===== Permissions (storage) =====

    private void ensureAllFilesAccessThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    toast("Grant 'All files access', then return here.");
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            } else {
                startEverything();
            }
        } else {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                startEverything();
            } else {
                legacyPermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }
    }

    // ===== Initial run =====

    private void startEverything() {
        // ⛔ If already working OR a download is in progress, don't start again
        if (isWorking || downloadInProgress) return;
        isWorking = true;

        new Thread(() -> {
            try {
                File mainDir = ensureMainDir();
                File tmpDir = ensureTempDir();
                final String androidId = getAndroidId();

                try {
                    postOnlineTrue(updateOnlineUrl(androidId));
                } catch (Exception ignored) {
                }

                if (!isOnline()) {
                    ui.post(() -> toast("Offline → playing local videos."));
                    playLocalPlaylistOrToast(mainDir);
                    return;
                }

                boolean status = readDownloadStatus(readStatusUrl(androidId));

                // 🚩 Once we see false → mark downloadInProgress and DO NOT ask status again
                if (!status) {
                    downloadInProgress = true;
                    try {
                        deleteAllInDirectory(tmpDir);
                        List<String> urls = fetchDownloadUrls(listDownloadsUrl(androidId));
                        int ok = downloadAllWithResume(urls, tmpDir);
                        if (ok == urls.size() && ok > 0) {
                            stopPlaybackForRefresh();
                            atomicSwapIntoMain(mainDir, tmpDir);
                            postUpdateStatusTrue(updateStatusUrl(androidId));
                        }
                    } finally {
                        // ✅ Download attempt finished (success or fail)
                        downloadInProgress = false;
                    }
                }

                playLocalPlaylistOrToast(mainDir);
            } catch (Exception e) {
                ui.post(() -> toast("Init error: " + e.getMessage()));
            } finally {
                isWorking = false;
            }
        }).start();
    }


    // ===== Periodic check =====

    private void startBackgroundCheckIfNeeded() {
        // ⛔ If busy or currently downloading, don't do anything
        if (isWorking || downloadInProgress) return;
        isWorking = true;

        new Thread(() -> {
            try {
                File mainDir = ensureMainDir();
                File tmpDir = ensureTempDir();
                final String androidId = getAndroidId();

                if (!isOnline()) return;

                // Double-check: if a download started meanwhile, bail
                if (downloadInProgress) return;

                boolean status = readDownloadStatus(readStatusUrl(androidId));

                if (!status) {
                    downloadInProgress = true;
                    try {
                        deleteAllInDirectory(tmpDir);
                        List<String> urls = fetchDownloadUrls(listDownloadsUrl(androidId));
                        if (!urls.isEmpty()) {
                            int ok = downloadAllWithResume(urls, tmpDir);
                            if (ok == urls.size()) {
                                stopPlaybackForRefresh();
                                atomicSwapIntoMain(mainDir, tmpDir);
                                postUpdateStatusTrue(updateStatusUrl(androidId));
                                ui.post(() -> {
                                    toast("Refreshed videos & set status=true");
                                    playLocalPlaylistOrToast(mainDir);
                                });
                            }
                        }
                    } finally {
                        // ✅ Download attempt has finished
                        downloadInProgress = false;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                isWorking = false;
            }
        }).start();
    }


    // ===== Heartbeat =====

    private void sendOnlineHeartbeat() {
        new Thread(() -> {
            try {
                String androidId = getAndroidId();
                String url = updateOnlineUrl(androidId);
                postOnlineTrue(url);
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void postOnlineTrue(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(30_000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("Accept", "application/json");

        String payload = "{\"is_online\": true}";
        try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
            out.write(payload.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        int code = c.getResponseCode();
        if (code / 100 != 2) {
            String msg = "Online update HTTP " + code + " " + c.getResponseMessage();
            c.disconnect();
            throw new RuntimeException(msg);
        }
        c.disconnect();
    }

    // ===== Atomic swap: tmp -> main =====

    private void atomicSwapIntoMain(File mainDir, File tmpDir) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File backup = new File(mainDir.getParentFile(), ROOT_DIR + "_old_" + ts);

        if (!backup.getParentFile().exists()) backup.getParentFile().mkdirs();

        if (mainDir.exists() && mainDir.listFiles() != null
                && mainDir.listFiles().length > 0) {
            mainDir.renameTo(backup);
        } else {
            deleteAllInDirectory(mainDir);
            mainDir.delete();
        }

        tmpDir.renameTo(mainDir);

        new Thread(() -> {
            try {
                deleteAllInDirectory(backup);
                backup.delete();
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void stopPlaybackForRefresh() {
        ui.post(() -> {
            if (player != null) {
                try {
                    player.stop();
                } catch (Exception ignored) {
                }
                try {
                    player.clearMediaItems();
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void deleteAllInDirectory(File dir) {
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            try {
                if (f.isDirectory()) {
                    deleteAllInDirectory(f);
                    f.delete();
                } else {
                    try {
                        MediaScannerConnection.scanFile(
                                this,
                                new String[]{f.getAbsolutePath()},
                                null,
                                null
                        );
                    } catch (Exception ignored) {
                    }
                    f.delete();
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ===== Connectivity =====

    private boolean isOnline() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(net);
            return nc != null &&
                    nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } else {
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        }
    }

    // ===== Download logic =====

    private int downloadAllWithResume(List<String> urls, File targetDir) {
        if (urls == null) urls = new ArrayList<>();
        final int total = urls.size();
        if (total == 0) return 0;

        ui.post(() -> toast("Downloading " + total + " video(s)…"));
        int success = 0;
        for (String u : urls) {
            try {
                File f = bigFileDownloadWithResume(u, targetDir);
                if (f != null && f.exists() && f.length() > 0) {
                    success++;
                    MediaScannerConnection.scanFile(
                            this,
                            new String[]{f.getAbsolutePath()},
                            new String[]{"video/mp4"},
                            null
                    );
                }
            } catch (Exception ignored) {
            }
        }
        return success;
    }

    private File bigFileDownloadWithResume(String urlStr, File targetDir) throws Exception {

        String baseName = filenameFromContentDispositionOrUrl(null, urlStr);
        File partFile = new File(targetDir, baseName + ".part");
        long downloaded = partFile.exists() ? partFile.length() : 0L;

        String finalUrl = resolveRedirects(urlStr, null);
        int attempts = 0;

        Log.d("DOWNLOAD_FLOW", "Starting download: " + finalUrl +
                " (already have " + downloaded + " bytes)");

        while (attempts < MAX_RETRIES) {
            attempts++;

            HttpURLConnection c = (HttpURLConnection) new URL(finalUrl).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(120_000);      // 120s connect timeout
            c.setReadTimeout(120_000);         // ✅ 120s read timeout (no more 0)
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "Android-VideoPlayer/1.0");
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Connection", "keep-alive");

            if (downloaded > 0) {
                String range = "bytes=" + downloaded + "-";
                Log.d("DOWNLOAD_FLOW", "Attempt " + attempts + " with Range: " + range);
                c.setRequestProperty("Range", range);
            } else {
                Log.d("DOWNLOAD_FLOW", "Attempt " + attempts + " with full GET");
            }

            int code = c.getResponseCode();
            int klass = code / 100;
            Log.d("DOWNLOAD_FLOW", "HTTP " + code + " on attempt " + attempts);

            if (klass == 2 || code == 206) {
                String cd = c.getHeaderField("Content-Disposition");
                String finalName = filenameFromContentDispositionOrUrl(cd, finalUrl);
                File outFile = new File(targetDir, finalName);

                if (code == 200 && downloaded > 0) {
                    // server ignored Range → restart from scratch
                    Log.w("DOWNLOAD_FLOW", "Server returned 200 for ranged request; restarting file");
                    if (partFile.exists()) partFile.delete();
                    downloaded = 0L;
                }

                try (InputStream in = c.getInputStream();
                     FileOutputStream out =
                             new FileOutputStream(partFile, downloaded > 0)) {

                    byte[] buf = new byte[128 * 1024];
                    int n;
                    long totalWritten = downloaded;

                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        totalWritten += n;

                        // optional: log occasionally
                        if (totalWritten % (5 * 1024 * 1024) < buf.length) {
                            Log.d("DOWNLOAD_FLOW", "Downloaded ~" + (totalWritten / (1024 * 1024)) + " MB");
                        }
                    }
                    out.flush();
                    Log.d("DOWNLOAD_FLOW", "Finished download, bytes=" + totalWritten);
                } finally {
                    c.disconnect();
                }

                if (outFile.exists()) outFile.delete();
                if (!partFile.renameTo(outFile)) {
                    Log.w("DOWNLOAD_FLOW", "renameTo failed; copying to final file");
                    try (FileOutputStream out = new FileOutputStream(outFile);
                         InputStream in = new FileInputStream(partFile)) {
                        byte[] b = new byte[128 * 1024];
                        int n;
                        while ((n = in.read(b)) != -1) out.write(b, 0, n);
                    }
                    partFile.delete();
                }

                return outFile;
            }

            // Non-2xx
            Log.w("DOWNLOAD_FLOW", "Non-2xx code: " + code + " msg=" + c.getResponseMessage());
            c.disconnect();

            // small backoff
            Thread.sleep(1_500L * attempts);
            downloaded = partFile.exists() ? partFile.length() : 0L;
            Log.d("DOWNLOAD_FLOW", "Retrying, already have " + downloaded + " bytes");
        }

        throw new RuntimeException("Download failed after " + MAX_RETRIES + " attempts");
    }


    private String resolveRedirects(String start, URL base) throws Exception {
        String current = start;
        URL baseUrl = base;
        for (int hop = 0; hop < 10; hop++) {
            URL u = (baseUrl == null) ? new URL(current) : new URL(baseUrl, current);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(120_000);
            c.setReadTimeout(30_000);
            c.setRequestMethod("HEAD");
            c.setRequestProperty("User-Agent", "Android-VideoPlayer/1.0");
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.connect();

            int code = c.getResponseCode();
            int klass = code / 100;
            String location = c.getHeaderField("Location");
            c.disconnect();

            if (klass == 3 && location != null && !location.trim().isEmpty()) {
                baseUrl = u;
                current = location.trim();
                continue;
            }

            if (code == HttpURLConnection.HTTP_BAD_METHOD
                    || code == HttpURLConnection.HTTP_FORBIDDEN) {
                HttpURLConnection g =
                        (HttpURLConnection) u.openConnection();
                g.setInstanceFollowRedirects(false);
                g.setConnectTimeout(120_000);
                g.setReadTimeout(30_000);
                g.setRequestMethod("GET");
                g.setRequestProperty("Range", "bytes=0-0");
                g.setRequestProperty("User-Agent", "Android-VideoPlayer/1.0");
                g.setRequestProperty("Accept", "*/*");
                g.setRequestProperty("Accept-Encoding", "identity");
                g.connect();
                int gCode = g.getResponseCode();
                String loc2 = g.getHeaderField("Location");
                g.disconnect();
                if ((gCode / 100) == 3 && loc2 != null && !loc2.trim().isEmpty()) {
                    baseUrl = u;
                    current = loc2.trim();
                    continue;
                }
            }
            return u.toString();
        }
        throw new RuntimeException("Too many redirects");
    }

    private String filenameFromContentDispositionOrUrl(String contentDisposition,
                                                       String url) {
        if (contentDisposition != null) {
            String cd = contentDisposition;
            int star = cd.toLowerCase().indexOf("filename*=");
            if (star >= 0) {
                String v = cd.substring(star + 10).trim();
                v = stripSemicolon(v);
                int twoTicks = v.indexOf("''");
                if (twoTicks > 0 && twoTicks + 2 < v.length()) {
                    String enc = v.substring(twoTicks + 2);
                    try {
                        String dec = URLDecoder.decode(stripQuotes(enc), "UTF-8");
                        if (!dec.isEmpty()) return sanitizeFilename(dec);
                    } catch (Exception ignored) {
                    }
                }
            }
            int idx = cd.toLowerCase().indexOf("filename=");
            if (idx >= 0) {
                String v = cd.substring(idx + 9).trim();
                v = stripSemicolon(v);
                v = stripQuotes(v);
                if (!v.isEmpty()) return sanitizeFilename(v);
            }
        }
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int slash = path.lastIndexOf('/');
        String last = (slash >= 0 && slash + 1 < path.length())
                ? path.substring(slash + 1) : "download.bin";
        if (last.isEmpty()) last = "download.bin";
        return sanitizeFilename(last);
    }

    private String stripSemicolon(String s) {
        int i = s.indexOf(';');
        return (i >= 0) ? s.substring(0, i).trim() : s;
    }

    private String stripQuotes(String s) {
        if ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private String sanitizeFilename(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    // ===== Playlist playback =====

    private void playLocalPlaylistOrToast(File dir) {
        List<File> files = listAllMp4SortedByName(dir);
        if (files.isEmpty()) {
            ui.post(() -> toast("No local videos found."));
            return;
        }
        ui.post(() -> {
            initPlayer();
            List<MediaItem> items = new ArrayList<>();
            for (File f : files) {
                if (f.exists() && f.length() > 0) {
                    items.add(MediaItem.fromUri(Uri.fromFile(f)));
                }
            }
            if (items.isEmpty()) {
                toast("No playable videos.");
                return;
            }
            player.setMediaItems(items, true);
            player.setRepeatMode(Player.REPEAT_MODE_ALL);
            player.prepare();
            player.play();
        });
    }

    private List<File> listAllMp4SortedByName(File dir) {
        File[] arr = dir.listFiles((d, name) ->
                name.toLowerCase().endsWith(".mp4"));
        if (arr == null || arr.length == 0) return new ArrayList<>();
        List<File> files = new ArrayList<>();
        Collections.addAll(files, arr);
        files.sort(Comparator.comparing(f -> f.getName().toLowerCase()));
        return files;
    }

    // ===== HTTP helpers for status & URLs =====

    private boolean readDownloadStatus(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(30_000);
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json");
        c.connect();

        int code = c.getResponseCode();
        if (code / 100 != 2) {
            String msg = "Read status HTTP " + code + " "
                    + c.getResponseMessage();
            c.disconnect();
            throw new RuntimeException(msg);
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        } finally {
            c.disconnect();
        }

        JSONObject obj = new JSONObject(sb.toString());
        if (obj.has("download_status")) {
            return obj.optBoolean("download_status", false);
        }
        return obj.optBoolean("status", false);
    }

    private void postUpdateStatusTrue(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(30_000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("Accept", "application/json");

        String payload = "{\"status\": true}";
        try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
            out.write(payload.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        int code = c.getResponseCode();
        if (code / 100 != 2) {
            String msg = "POST update HTTP " + code + " "
                    + c.getResponseMessage();
            try (InputStream es = c.getErrorStream()) {
                if (es != null) {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(es, StandardCharsets.UTF_8));
                    String line;
                    StringBuilder err = new StringBuilder();
                    while ((line = br.readLine()) != null) err.append(line);
                    msg += " | " + err;
                }
            } catch (Exception ignored) {
            }
            c.disconnect();
            throw new RuntimeException(msg);
        }
        c.disconnect();
    }

    private List<String> fetchDownloadUrls(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(30_000);
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json");
        c.connect();

        int code = c.getResponseCode();
        if (code / 100 != 2) {
            String msg = "Downloads HTTP " + code + " "
                    + c.getResponseMessage();
            c.disconnect();
            throw new RuntimeException(msg);
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        } finally {
            c.disconnect();
        }

        List<String> urls = new ArrayList<>();
        JSONObject obj = new JSONObject(sb.toString());
        JSONArray items = obj.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it != null) {
                    String u = it.optString("url", "").trim();
                    if (!u.isEmpty()) urls.add(u);
                }
            }
        }
        return urls;
    }

    // ===== Player init =====

    private void initPlayer() {
        if (player != null) return;
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.setRepeatMode(Player.REPEAT_MODE_ALL);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                int idx = player.getCurrentMediaItemIndex();
                if (player.getMediaItemCount() > 0) {
                    try {
                        player.removeMediaItem(idx);
                        if (player.getMediaItemCount() > 0) {
                            player.seekTo(
                                    Math.min(idx, player.getMediaItemCount() - 1),
                                    0
                            );
                            player.play();
                            return;
                        }
                    } catch (Exception ignored) {
                    }
                }
                toast("Play error: " + error.getMessage());
            }
        });
    }

    // ===== Fullscreen UI helpers =====

    private void applyImmersive() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = decor.getWindowInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    @SuppressLint("HardwareIds")
    private String getAndroidId() {
        return Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ANDROID_ID
        );
    }

    private File ensureMainDir() {
        File d = new File(Environment.getExternalStorageDirectory(), ROOT_DIR);
        if (!d.exists() && !d.mkdirs()) {
            throw new RuntimeException("Failed to create " + d.getAbsolutePath());
        }
        return d;
    }

    private File ensureTempDir() {
        File d = new File(Environment.getExternalStorageDirectory(), TEMP_DIR);
        if (!d.exists() && !d.mkdirs()) {
            throw new RuntimeException("Failed to create " + d.getAbsolutePath());
        }
        return d;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ================= BLUETOOTH (BLE) SECTION =================

    private void ensureBluetoothPermissionAndConnect() {
        if (btAdapter == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean hasConnect = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            boolean hasScan = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;

            if (!hasConnect || !hasScan) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN
                        },
                        REQ_BT_PERMS
                );
                return;
            }
        }
        autoConnectToEsp32();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT_PERMS) {
            boolean granted = true;
            if (grantResults.length == 0) {
                granted = false;
            } else {
                for (int r : grantResults) {
                    if (r != PackageManager.PERMISSION_GRANTED) {
                        granted = false;
                        break;
                    }
                }
            }
            if (granted) {
                autoConnectToEsp32();
            } else {
                toast("Bluetooth permissions denied (Nearby devices).");
            }
        }
    }

    private void autoConnectToEsp32() {
        if (btAdapter == null) return;

        if (!btAdapter.isEnabled()) {
            toast("Please enable Bluetooth");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean hasConnect = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            boolean hasScan = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            if (!hasConnect || !hasScan) {
                ensureBluetoothPermissionAndConnect();
                return;
            }
        }

        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (bm != null) {
            btAdapter = bm.getAdapter();
        }

        if (btAdapter == null) {
            toast("Bluetooth adapter not available");
            return;
        }

        bleScanner = btAdapter.getBluetoothLeScanner();
        if (bleScanner == null) {
            toast("BLE scanner not available");
            return;
        }

        startBleScan();
    }

    private void startBleScan() {
        if (bleScanner == null || bleScanning) return;
        bleScanning = true;

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder()
                .setDeviceName(ESP32_DEVICE_NAME)
                .build());

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        bleScanner.startScan(filters, settings, scanCallback);
        ui.post(() -> toast("Scanning for " + ESP32_DEVICE_NAME + "…"));

        bleHandler.postDelayed(() -> {
            if (bleScanning) {
                stopBleScan();
                ui.post(() -> toast("ESP32 BLE device not found"));
                // 🔁 schedule reconnect after scan timeout
                scheduleBleReconnect();
            }
        }, 15_000L);
    }

    private void stopBleScan() {
        if (!bleScanning || bleScanner == null) return;
        try {
            bleScanner.stopScan(scanCallback);
        } catch (Exception ignored) {
        }
        bleScanning = false;
    }

    // 🔁 Central helper: keep trying to reconnect as long as btShouldReconnect is true
    private void scheduleBleReconnect() {
        if (!btShouldReconnect) return;
        bleHandler.postDelayed(() -> {
            if (!btShouldReconnect) return;
            Log.d("BLE_DEBUG", "Reconnecting to ESP32…");
            autoConnectToEsp32();
        }, 5_000L);  // retry after 5 seconds
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) return;

            String name = device.getName();
            if (name == null) return;

            if (ESP32_DEVICE_NAME.equals(name)) {
                stopBleScan();
                ui.post(() -> toast("Found " + ESP32_DEVICE_NAME + ", connecting…"));

                try {
                    btGatt = device.connectGatt(
                            FullScreenPlayerActivity.this,
                            false,
                            gattCallback
                    );
                } catch (SecurityException e) {
                    ui.post(() -> toast("BLE connect permission error: " + e.getMessage()));
                    scheduleBleReconnect();
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            ui.post(() -> toast("BLE scan failed: " + errorCode));
            // 🔁 retry scanning after delay
            scheduleBleReconnect();
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);

            if (newState == BluetoothGatt.STATE_CONNECTED) {
                ui.post(() -> toast("Connected to ESP32 (BLE)"));
                try {
                    gatt.discoverServices();
                } catch (SecurityException e) {
                    ui.post(() -> toast("discoverServices permission error: " + e.getMessage()));
                }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                ui.post(() -> toast("ESP32 BLE disconnected"));
                try {
                    gatt.close();
                } catch (Exception ignored) {
                }

                // 🔁 Keep trying while btShouldReconnect is true
                scheduleBleReconnect();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                ui.post(() -> toast("Service discovery failed: " + status));
                // try again later
                scheduleBleReconnect();
                return;
            }

            BluetoothGattService service = gatt.getService(NUS_SERVICE_UUID);
            if (service == null) {
                ui.post(() -> toast("NUS service not found on ESP32"));
                scheduleBleReconnect();
                return;
            }

            nusTxChar = service.getCharacteristic(NUS_CHAR_TX_UUID);
            nusRxChar = service.getCharacteristic(NUS_CHAR_RX_UUID);

            if (nusTxChar == null || nusRxChar == null) {
                ui.post(() -> toast("NUS characteristics missing"));
                scheduleBleReconnect();
                return;
            }

            try {
                gatt.setCharacteristicNotification(nusTxChar, true);
                BluetoothGattDescriptor cccd = nusTxChar.getDescriptor(CCCD_UUID);
                if (cccd != null) {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(cccd);
                }
            } catch (SecurityException e) {
                ui.post(() -> toast("Enable notifications error: " + e.getMessage()));
                scheduleBleReconnect();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);

            if (!NUS_CHAR_TX_UUID.equals(characteristic.getUuid())) return;

            byte[] value = characteristic.getValue();
            if (value == null || value.length == 0) return;

            // Convert full notification to a string
            String msg = new String(value, StandardCharsets.UTF_8).trim();

            Log.d("BLE_DEBUG_RAW", "RX: [" + msg + "]");

            // Treat every notify as one line
            if (!msg.isEmpty()) {
                handleBtCommand(msg);
            }
        }

    };

    /**
     * Called for every line received from ESP32 over BLE.
     * We:
     *  - handle PLAY / PAUSE / NEXT,
     *  - if it contains "Temperature", parse the value and store in lastTemperatureValue,
     *  - if it is "REED" + "OPEN", increment daily & monthly counts.
     *  - actual temperature POST is done by the 5s timer (tempPostRunnable)
     */
    private void handleBtCommand(String cmdRaw) {
        final String cmd = cmdRaw.trim();
        final String lower = cmd.toLowerCase(Locale.US);

        Log.d("BLE_DEBUG", "From ESP32: [" + cmd + "]");

        // ------------ 1) TEMPERATURE ------------
        boolean hasTemp = lower.contains("temperature");
        if (hasTemp) {
            // Very simple parse: find the first number
            Matcher m = TEMP_PATTERN.matcher(cmd);
            if (m.find()) {
                try {
                    float tempC = Float.parseFloat(m.group(1));
                    lastTemperatureValue = tempC;

                    Log.d("BLE_DEBUG", "Updated last temperature from ESP32: " + tempC);

                } catch (NumberFormatException e) {
                    Log.e("BLE_DEBUG", "Temp parse error: " + e.getMessage());
                }
            } else {
                Log.w("BLE_DEBUG", "No number found in temp line: " + cmd);
            }
        }

        // ------------ 2) REED OPEN ------------
        boolean hasReedOpen = lower.contains("reed") && lower.contains("open");
        if (hasReedOpen) {
            Log.d("BLE_DEBUG", "REED OPEN → increment counts");
            incrementDailyAndMonthly();
        }

        final boolean isTempLine = hasTemp;
        final boolean isReedLine = lower.contains("reed");

        // ------------ 3) Player commands & other toasts ------------
        ui.post(() -> {
            // Don’t spam toast for REED and temp
            if (!isTempLine && !isReedLine) {
                toast("From ESP32: " + cmd);
            }

            String upper = cmd.toUpperCase(Locale.US);
            switch (upper) {
                case "PLAY":
                    if (player != null) player.play();
                    break;
                case "PAUSE":
                    if (player != null) player.pause();
                    break;
                case "NEXT":
                    if (player != null && player.getMediaItemCount() > 0) {
                        int next = (player.getCurrentMediaItemIndex() + 1)
                                % player.getMediaItemCount();
                        player.seekTo(next, 0);
                        player.play();
                    }
                    break;
                default:
                    break;
            }
        });
    }

    /**
     * Sends the temperature value to:
     *   POST /device/{android_id}/temperature_update
     *   { "temperature": <value> }
     */
    private void sendTemperatureToServer(float tempC) {
        final String androidId = getAndroidId();
        final String url = updateTemperatureUrl(androidId);

        new Thread(() -> {
            try {
                postTemperature(url, tempC);
            } catch (Exception e) {
                ui.post(() ->
                        toast("Failed to send temperature: " + e.getMessage()));
            }
        }).start();
    }

    private void postTemperature(String urlStr, float tempC) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(30_000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("Accept", "application/json");

        String payload = String.format(Locale.US, "{\"temperature\": %.2f}", tempC);

        try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
            out.write(payload.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        int code = c.getResponseCode();
        if (code / 100 != 2) {
            String msg = "Temp update HTTP " + code + " " + c.getResponseMessage();
            c.disconnect();
            throw new RuntimeException(msg);
        }
        c.disconnect();
    }

    // ===== daily + monthly increment logic =====

    private void incrementDailyAndMonthly() {
        new Thread(() -> {
            try {
                String androidId = getAndroidId();

                DeviceCounts counts = fetchDeviceCounts(countsUrl(androidId));
                int newDaily = counts.daily + 1;
                int newMonthly = counts.monthly + 1;

                postDailyCount(dailyUpdateUrl(androidId), newDaily);
                postMonthlyCount(monthlyUpdateUrl(androidId), newMonthly);

            } catch (Exception e) {
                ui.post(() -> toast("Failed to update counts: " + e.getMessage()));
            }
        }).start();
    }

    private DeviceCounts fetchDeviceCounts(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(30_000);
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json");
        c.connect();

        int code = c.getResponseCode();
        if (code / 100 != 2) {
            String msg = "Counts HTTP " + code + " " + c.getResponseMessage();
            c.disconnect();
            throw new RuntimeException(msg);
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        } finally {
            c.disconnect();
        }

        JSONObject obj = new JSONObject(sb.toString());
        DeviceCounts dc = new DeviceCounts();
        dc.daily = obj.optInt("daily_count", 0);
        dc.monthly = obj.optInt("monthly_count", 0);
        return dc;
    }

    private void postDailyCount(String urlStr, int value) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(30_000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("Accept", "application/json");

        String payload = "{\"daily_count\": " + value + "}";

        try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
            out.write(payload.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        int code = c.getResponseCode();
        if (code / 100 != 2) {
            String msg = "Daily update HTTP " + code + " " + c.getResponseMessage();
            c.disconnect();
            throw new RuntimeException(msg);
        }
        c.disconnect();
    }

    private void postMonthlyCount(String urlStr, int value) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(30_000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("Accept", "application/json");

        String payload = "{\"monthly_count\": " + value + "}";

        try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
            out.write(payload.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        int code = c.getResponseCode();
        if (code / 100 != 2) {
            String msg = "Monthly update HTTP " + code + " " + c.getResponseMessage();
            c.disconnect();
            throw new RuntimeException(msg);
        }
        c.disconnect();
    }

    // Write text to ESP32 via BLE (NUS RX characteristic)
    private void sendToEsp32(String text) {
        final BluetoothGatt gattLocal = btGatt;
        final BluetoothGattCharacteristic rxLocal = nusRxChar;

        if (gattLocal == null || rxLocal == null) {
            return;
        }

        new Thread(() -> {
            try {
                String msg = text + "\n";
                rxLocal.setValue(msg.getBytes(StandardCharsets.UTF_8));
                rxLocal.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gattLocal.writeCharacteristic(
                            rxLocal,
                            rxLocal.getValue(),
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    );
                } else {
                    gattLocal.writeCharacteristic(rxLocal);
                }
            } catch (SecurityException ignored) {
                // Missing BLUETOOTH_CONNECT permission, ignore for now
            }
        }).start();
    }
}