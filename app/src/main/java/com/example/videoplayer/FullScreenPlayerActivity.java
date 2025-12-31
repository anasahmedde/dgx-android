package com.example.videoplayer;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
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
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
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
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Video player using TextureView for rotation support.
 * TextureView (unlike SurfaceView) properly supports rotation transforms.
 */
@OptIn(markerClass = UnstableApi.class)
@SuppressLint("MissingPermission")
public class FullScreenPlayerActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener {

    private static final String TAG = "FullScreenPlayer";
    private static final String API_BASE = "http://34.248.112.237:8005";

    private static String listDownloadsUrl(String id) { return API_BASE + "/device/" + id + "/videos/downloads"; }
    private static String readStatusUrl(String id) { return API_BASE + "/device/" + id + "/download_status"; }
    private static String updateStatusUrl(String id) { return API_BASE + "/device/" + id + "/download_update"; }
    private static String updateOnlineUrl(String id) { return API_BASE + "/device/" + id + "/online_update"; }
    private static String updateTemperatureUrl(String id) { return API_BASE + "/device/" + id + "/temperature_update"; }
    private static String countsUrl(String id) { return API_BASE + "/device/" + id + "/counts"; }
    private static String dailyUpdateUrl(String id) { return API_BASE + "/device/" + id + "/daily_update"; }
    private static String monthlyUpdateUrl(String id) { return API_BASE + "/device/" + id + "/monthly_update"; }

    // Video metadata
    private static class VideoMetadata {
        String filename = "";
        String videoName = "";
        int rotation = 0;
        String fitMode = "cover";
    }

    // Metadata storage
    private volatile Map<String, VideoMetadata> metadataByVideoName = new HashMap<>();
    private volatile Map<String, VideoMetadata> metadataByFilename = new HashMap<>();
    private int lastAppliedRotation = -9999;
    private String lastAppliedFitMode = "";
    private List<File> currentPlaylistFiles = new ArrayList<>();

    private volatile boolean isWorking = false;
    private volatile boolean downloadInProgress = false;

    // Screen dimensions
    private int screenWidth = 0;
    private int screenHeight = 0;

    // Video dimensions
    private int videoWidth = 0;
    private int videoHeight = 0;

    // Rotation polling every 10 seconds
    private static final long ROTATION_POLL_MS = 10_000L;
    private final Handler rotationPollHandler = new Handler(Looper.getMainLooper());
    private final Runnable rotationPollRunnable = new Runnable() {
        @Override
        public void run() {
            pollRotationMetadata();
            rotationPollHandler.postDelayed(this, ROTATION_POLL_MS);
        }
    };

    // Video download polling every 60 seconds
    private static final long POLL_MS = 60_000L;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            sendOnlineHeartbeat();
            startBackgroundCheckIfNeeded();
            pollHandler.postDelayed(this, POLL_MS);
        }
    };

    private static final String ROOT_DIR = "video";
    private static final String TEMP_DIR = "video_new";
    private static final int MAX_RETRIES = 5;

    // Views - using TextureView instead of PlayerView
    private FrameLayout rootContainer;
    private TextureView textureView;
    private ExoPlayer player;
    private Surface surface;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<String> legacyPermLauncher;

    // BLE
    private static final String ESP32_DEVICE_NAME = "ESP32_PLAYER_CTRL_BLE";
    private static final int REQ_BT_PERMS = 2001;
    private static final UUID NUS_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NUS_CHAR_RX_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NUS_CHAR_TX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt btGatt;
    private BluetoothGattCharacteristic nusTxChar, nusRxChar;
    private final Handler bleHandler = new Handler(Looper.getMainLooper());
    private volatile boolean btShouldReconnect = true;
    private volatile boolean bleScanning = false;

    private static final Pattern TEMP_PATTERN = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");
    private volatile Float lastTemperatureValue = null;

    private final Handler tempHandler = new Handler(Looper.getMainLooper());
    private final long TEMP_POST_INTERVAL_MS = 5_000L;
    private final Runnable tempPostRunnable = new Runnable() {
        @Override
        public void run() {
            if (lastTemperatureValue != null) sendTemperatureToServer(lastTemperatureValue);
            tempHandler.postDelayed(this, TEMP_POST_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_fullscreen_player);

        // Get screen dimensions
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;
        Log.d(TAG, "Screen size: " + screenWidth + "x" + screenHeight);

        // Find views
        rootContainer = findViewById(R.id.rootContainer);
        textureView = findViewById(R.id.textureView);
        textureView.setSurfaceTextureListener(this);

        applyImmersive();

        Toast.makeText(this, "Android ID: " + getAndroidId(), Toast.LENGTH_LONG).show();

        legacyPermLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                granted -> { if (granted) startEverything(); else toast("Storage permission denied."); });

        ensureAllFilesAccessThenStart();

        tempHandler.postDelayed(tempPostRunnable, TEMP_POST_INTERVAL_MS);
        rotationPollHandler.postDelayed(rotationPollRunnable, ROTATION_POLL_MS);

        btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null) ensureBluetoothPermissionAndConnect();
    }

    // ===== TextureView.SurfaceTextureListener =====

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        Log.d(TAG, "SurfaceTexture available: " + width + "x" + height);
        surface = new Surface(surfaceTexture);
        if (player != null) {
            player.setVideoSurface(surface);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        Log.d(TAG, "SurfaceTexture size changed: " + width + "x" + height);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        Log.d(TAG, "SurfaceTexture destroyed");
        if (player != null) {
            player.setVideoSurface(null);
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        // Called every frame - no logging needed
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        pollHandler.removeCallbacksAndMessages(null);
        pollHandler.postDelayed(pollRunnable, POLL_MS);
        rotationPollHandler.removeCallbacksAndMessages(null);
        rotationPollHandler.postDelayed(rotationPollRunnable, 1000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pollHandler.removeCallbacksAndMessages(null);
        rotationPollHandler.removeCallbacksAndMessages(null);
        tempHandler.removeCallbacksAndMessages(null);
        btShouldReconnect = false;
        stopBleScan();
        if (btGatt != null) { try { btGatt.close(); } catch (Exception ignored) {} }
        if (player != null) {
            player.setVideoSurface(null);
            player.release();
            player = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    // ===== ROTATION POLLING =====
    private void pollRotationMetadata() {
        new Thread(() -> {
            try {
                if (!isOnline()) return;
                String urlStr = listDownloadsUrl(getAndroidId());
                Log.d(TAG, "Polling rotation from: " + urlStr);

                HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(10_000);
                c.setRequestMethod("GET");
                c.connect();

                if (c.getResponseCode() / 100 != 2) { c.disconnect(); return; }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = br.readLine()) != null) sb.append(line);
                } finally { c.disconnect(); }

                Map<String, VideoMetadata> byName = new HashMap<>();
                Map<String, VideoMetadata> byFile = new HashMap<>();

                JSONObject obj = new JSONObject(sb.toString());
                JSONArray items = obj.optJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.optJSONObject(i);
                        if (it != null) {
                            VideoMetadata vm = new VideoMetadata();
                            vm.videoName = it.optString("video_name", "").trim();
                            vm.filename = it.optString("filename", "").trim();
                            vm.rotation = it.optInt("rotation", 0);
                            vm.fitMode = it.optString("fit_mode", "cover");

                            if (!vm.videoName.isEmpty()) byName.put(vm.videoName.toLowerCase(), vm);
                            if (!vm.filename.isEmpty()) byFile.put(vm.filename.toLowerCase(), vm);
                            Log.d(TAG, "Polled: " + vm.videoName + "/" + vm.filename + " rot=" + vm.rotation + " fit=" + vm.fitMode);
                        }
                    }
                }

                metadataByVideoName = byName;
                metadataByFilename = byFile;
                ui.post(this::applyRotationForCurrentVideo);
            } catch (Exception e) {
                Log.e(TAG, "Poll error: " + e.getMessage());
            }
        }).start();
    }

    private VideoMetadata getMetadataForFile(File file) {
        if (file == null) return null;
        String filename = file.getName().toLowerCase();

        VideoMetadata vm = metadataByFilename.get(filename);
        if (vm != null) return vm;

        String base = filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) : filename;
        vm = metadataByVideoName.get(base);
        if (vm != null) return vm;

        for (Map.Entry<String, VideoMetadata> e : metadataByVideoName.entrySet()) {
            if (filename.contains(e.getKey()) || e.getKey().contains(base)) return e.getValue();
        }
        for (Map.Entry<String, VideoMetadata> e : metadataByFilename.entrySet()) {
            String k = e.getKey().replace(".mp4", "");
            if (filename.contains(k) || k.contains(base)) return e.getValue();
        }
        return null;
    }

    private void applyRotationForCurrentVideo() {
        if (player == null || currentPlaylistFiles.isEmpty()) return;
        int idx = player.getCurrentMediaItemIndex();
        if (idx < 0 || idx >= currentPlaylistFiles.size()) return;

        File f = currentPlaylistFiles.get(idx);
        VideoMetadata vm = getMetadataForFile(f);
        int targetRotation = vm != null ? vm.rotation : 0;
        String fitMode = vm != null ? vm.fitMode : "cover";

        // Only apply if rotation or fit mode changed
        if (targetRotation == lastAppliedRotation && fitMode.equals(lastAppliedFitMode)) {
            Log.d(TAG, "Rotation unchanged (" + targetRotation + "°), skipping");
            return;
        }

        Log.d(TAG, "ROTATION CHANGED: " + lastAppliedRotation + " -> " + targetRotation + "° fitMode=" + fitMode + " for " + f.getName());
        lastAppliedRotation = targetRotation;
        lastAppliedFitMode = fitMode;

        applyTextureViewTransform(targetRotation, fitMode);
        toast("Rotation: " + targetRotation + "°");
    }

    /**
     * Apply rotation using TextureView's Matrix transform.
     * This is the proper way to rotate video content.
     */
    private void applyTextureViewTransform(int rotation, String fitMode) {
        if (textureView == null) return;

        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();

        if (viewWidth == 0 || viewHeight == 0) {
            Log.d(TAG, "TextureView not measured yet, retrying...");
            textureView.post(() -> applyTextureViewTransform(rotation, fitMode));
            return;
        }

        // Use video dimensions if known, otherwise use view dimensions
        int vw = videoWidth > 0 ? videoWidth : viewWidth;
        int vh = videoHeight > 0 ? videoHeight : viewHeight;

        Log.d(TAG, "Applying transform: rotation=" + rotation + " fitMode=" + fitMode +
                " view=" + viewWidth + "x" + viewHeight + " video=" + vw + "x" + vh);

        Matrix matrix = new Matrix();

        // Center of the view
        float centerX = viewWidth / 2f;
        float centerY = viewHeight / 2f;

        // For 90/270 rotation, the video dimensions are swapped after rotation
        boolean isRotated90or270 = (rotation == 90 || rotation == 270);

        // Calculate scale based on fit mode
        float scaleX, scaleY;

        if (isRotated90or270) {
            // After rotation, video width becomes height and vice versa
            // So we compare rotated video dimensions to view dimensions
            float rotatedVideoWidth = vh;  // video height becomes width after rotation
            float rotatedVideoHeight = vw; // video width becomes height after rotation

            if ("contain".equals(fitMode)) {
                // Fit inside - show entire video, may have black bars
                float scale = Math.min((float) viewWidth / rotatedVideoWidth, (float) viewHeight / rotatedVideoHeight);
                scaleX = scale * vw / viewWidth;
                scaleY = scale * vh / viewHeight;
            } else if ("fill".equals(fitMode)) {
                // Stretch to fill exactly (may distort aspect ratio)
                scaleX = (float) viewHeight / vw;  // video width fills view height
                scaleY = (float) viewWidth / vh;   // video height fills view width
            } else {
                // Cover (default) - fill screen, may crop
                float scale = Math.max((float) viewWidth / rotatedVideoWidth, (float) viewHeight / rotatedVideoHeight);
                scaleX = scale * vw / viewWidth;
                scaleY = scale * vh / viewHeight;
            }
        } else {
            // 0 or 180 rotation - no dimension swap
            if ("contain".equals(fitMode)) {
                float scale = Math.min((float) viewWidth / vw, (float) viewHeight / vh);
                scaleX = scale * vw / viewWidth;
                scaleY = scale * vh / viewHeight;
            } else if ("fill".equals(fitMode)) {
                // Stretch to fill exactly
                scaleX = 1f;
                scaleY = 1f;
            } else {
                // Cover
                float scale = Math.max((float) viewWidth / vw, (float) viewHeight / vh);
                scaleX = scale * vw / viewWidth;
                scaleY = scale * vh / viewHeight;
            }
        }

        // Apply transformations: scale first, then rotate around center
        matrix.setScale(scaleX, scaleY, centerX, centerY);
        matrix.postRotate(rotation, centerX, centerY);

        textureView.setTransform(matrix);
        Log.d(TAG, "Transform applied: rotation=" + rotation + " scaleX=" + scaleX + " scaleY=" + scaleY);
    }

    // ===== STORAGE =====
    private void ensureAllFilesAccessThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            } else startEverything();
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
                startEverything();
            else legacyPermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
    }

    private void startEverything() {
        if (isWorking || downloadInProgress) return;
        isWorking = true;
        new Thread(() -> {
            try {
                File mainDir = ensureMainDir(), tmpDir = ensureTempDir();
                String id = getAndroidId();
                try { postOnlineTrue(updateOnlineUrl(id)); } catch (Exception ignored) {}

                if (!isOnline()) {
                    ui.post(() -> toast("Offline - playing local videos"));
                    playLocalPlaylistOrToast(mainDir);
                    return;
                }

                if (!readDownloadStatus(readStatusUrl(id))) {
                    downloadInProgress = true;
                    try {
                        // SMART SYNC: Only download new, delete unassigned
                        smartSyncVideos(mainDir, id);
                        postUpdateStatusTrue(updateStatusUrl(id));
                    } finally { downloadInProgress = false; }
                }
                playLocalPlaylistOrToast(mainDir);
                pollRotationMetadata();
            } catch (Exception e) {
                ui.post(() -> toast("Error: " + e.getMessage()));
                // On error, try to play local videos anyway
                try {
                    File mainDir = ensureMainDir();
                    playLocalPlaylistOrToast(mainDir);
                } catch (Exception ignored) {}
            }
            finally { isWorking = false; }
        }).start();
    }

    // Smart sync: only download new videos, delete unassigned ones
    private void smartSyncVideos(File mainDir, String deviceId) throws Exception {
        String url = listDownloadsUrl(deviceId);

        // Get expected filenames from server
        List<String> expectedFilenames = fetchExpectedFilenames(url);
        List<String> downloadUrls = fetchDownloadUrls(url);

        if (expectedFilenames.isEmpty() && downloadUrls.isEmpty()) {
            Log.d(TAG, "No videos assigned to this device");
            return;
        }

        // Get current local files
        File[] localFiles = mainDir.listFiles((d, n) -> n.toLowerCase().endsWith(".mp4"));
        Set<String> localFilenames = new HashSet<>();
        if (localFiles != null) {
            for (File f : localFiles) {
                localFilenames.add(f.getName().toLowerCase());
            }
        }

        // Determine which files to download (new ones)
        List<String> urlsToDownload = new ArrayList<>();
        Set<String> expectedSet = new HashSet<>();
        for (int i = 0; i < downloadUrls.size(); i++) {
            String urlStr = downloadUrls.get(i);
            String filename = i < expectedFilenames.size() ? expectedFilenames.get(i) : filenameFromUrl(urlStr);
            expectedSet.add(filename.toLowerCase());

            if (!localFilenames.contains(filename.toLowerCase())) {
                urlsToDownload.add(urlStr);
                Log.d(TAG, "Will download new video: " + filename);
            } else {
                Log.d(TAG, "Video already exists locally: " + filename);
            }
        }

        // Determine which local files to delete (unassigned)
        List<File> filesToDelete = new ArrayList<>();
        if (localFiles != null) {
            for (File f : localFiles) {
                if (!expectedSet.contains(f.getName().toLowerCase())) {
                    filesToDelete.add(f);
                    Log.d(TAG, "Will delete unassigned video: " + f.getName());
                }
            }
        }

        // Delete unassigned videos
        for (File f : filesToDelete) {
            if (f.delete()) {
                Log.d(TAG, "Deleted: " + f.getName());
            }
        }

        // Download new videos directly to main directory
        if (!urlsToDownload.isEmpty()) {
            ui.post(() -> toast("Downloading " + urlsToDownload.size() + " new video(s)…"));
            int downloaded = 0;
            for (String urlStr : urlsToDownload) {
                try {
                    File f = bigFileDownloadWithResume(urlStr, mainDir);
                    if (f != null && f.exists() && f.length() > 0) {
                        downloaded++;
                        Log.d(TAG, "Downloaded: " + f.getName());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Download failed: " + e.getMessage());
                }
            }
            final int finalDownloaded = downloaded;
            ui.post(() -> toast("Downloaded " + finalDownloaded + " video(s)"));

            // Refresh playlist if we downloaded something or deleted something
            if (downloaded > 0 || !filesToDelete.isEmpty()) {
                stopPlaybackForRefresh();
            }
        } else if (!filesToDelete.isEmpty()) {
            // Just deleted some files, refresh playlist
            stopPlaybackForRefresh();
            ui.post(() -> toast("Removed " + filesToDelete.size() + " unassigned video(s)"));
        } else {
            Log.d(TAG, "All videos are up to date");
        }
    }

    private void startBackgroundCheckIfNeeded() {
        if (isWorking || downloadInProgress) return;
        isWorking = true;
        new Thread(() -> {
            try {
                File mainDir = ensureMainDir();
                if (!isOnline()) {
                    // Offline: just continue playing local videos
                    return;
                }
                String id = getAndroidId();
                if (!readDownloadStatus(readStatusUrl(id))) {
                    downloadInProgress = true;
                    try {
                        // Use smart sync instead of full re-download
                        smartSyncVideos(mainDir, id);
                        postUpdateStatusTrue(updateStatusUrl(id));
                        ui.post(() -> playLocalPlaylistOrToast(mainDir));
                    } finally { downloadInProgress = false; }
                }
            } catch (Exception ignored) {}
            finally { isWorking = false; }
        }).start();
    }

    private void sendOnlineHeartbeat() {
        new Thread(() -> { try { postOnlineTrue(updateOnlineUrl(getAndroidId())); } catch (Exception ignored) {} }).start();
    }

    private void postOnlineTrue(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20_000); c.setReadTimeout(30_000);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
            out.write("{\"is_online\": true}".getBytes(StandardCharsets.UTF_8));
        }
        c.getResponseCode(); c.disconnect();
    }

    private void atomicSwapIntoMain(File main, File tmp) {
        File backup = new File(main.getParent(), main.getName() + "_old");
        try {
            if (backup.exists()) { deleteAllInDirectory(backup); backup.delete(); }
            if (main.exists()) main.renameTo(backup);
            tmp.renameTo(main);
            if (backup.exists()) { deleteAllInDirectory(backup); backup.delete(); }
        } catch (Exception ignored) {}
    }

    private void stopPlaybackForRefresh() {
        ui.post(() -> { if (player != null) { player.stop(); player.clearMediaItems(); } });
    }

    private void deleteAllInDirectory(File dir) {
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) { deleteAllInDirectory(f); f.delete(); }
            else f.delete();
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(net);
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    private List<String> fetchDownloadUrls(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20_000); c.setReadTimeout(30_000); c.setRequestMethod("GET"); c.connect();
        if (c.getResponseCode() / 100 != 2) { c.disconnect(); throw new RuntimeException("HTTP error"); }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line; while ((line = br.readLine()) != null) sb.append(line);
        } finally { c.disconnect(); }
        List<String> urls = new ArrayList<>();
        JSONArray items = new JSONObject(sb.toString()).optJSONArray("items");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            String u = items.optJSONObject(i).optString("url", "").trim();
            if (!u.isEmpty()) urls.add(u);
        }
        return urls;
    }

    // Returns list of expected filenames from server
    private List<String> fetchExpectedFilenames(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20_000); c.setReadTimeout(30_000); c.setRequestMethod("GET"); c.connect();
        if (c.getResponseCode() / 100 != 2) { c.disconnect(); return new ArrayList<>(); }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line; while ((line = br.readLine()) != null) sb.append(line);
        } finally { c.disconnect(); }
        List<String> filenames = new ArrayList<>();
        JSONArray items = new JSONObject(sb.toString()).optJSONArray("items");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            String filename = item.optString("filename", "").trim();
            if (filename.isEmpty()) {
                // Extract filename from URL if not provided
                String urlStr = item.optString("url", "").trim();
                if (!urlStr.isEmpty()) {
                    filename = filenameFromUrl(urlStr);
                }
            }
            if (!filename.isEmpty()) filenames.add(filename);
        }
        return filenames;
    }

    private int downloadAllWithResume(List<String> urls, File dir) {
        if (urls == null || urls.isEmpty()) return 0;
        ui.post(() -> toast("Downloading " + urls.size() + " video(s)…"));
        int ok = 0;
        for (String url : urls) {
            try {
                File f = bigFileDownloadWithResume(url, dir);
                if (f != null && f.exists() && f.length() > 0) ok++;
            } catch (Exception e) { Log.e(TAG, "DL fail: " + e.getMessage()); }
        }
        return ok;
    }

    private File bigFileDownloadWithResume(String urlStr, File dir) throws Exception {
        String name = filenameFromUrl(urlStr);
        File part = new File(dir, name + ".part");
        long have = part.exists() ? part.length() : 0;
        String finalUrl = resolveRedirects(urlStr);

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            HttpURLConnection c = (HttpURLConnection) new URL(finalUrl).openConnection();
            c.setConnectTimeout(120_000); c.setReadTimeout(120_000);
            if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
            int code = c.getResponseCode();
            if (code == 200 || code == 206) {
                String cd = c.getHeaderField("Content-Disposition");
                String fn = cd != null && cd.contains("filename=") ? cd.substring(cd.indexOf("filename=") + 9).replace("\"", "").trim() : name;
                File out = new File(dir, fn.isEmpty() ? name : fn);
                if (code == 200 && have > 0) { part.delete(); have = 0; }
                try (InputStream in = c.getInputStream(); FileOutputStream fos = new FileOutputStream(part, have > 0)) {
                    byte[] buf = new byte[131072]; int n;
                    while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                } finally { c.disconnect(); }
                if (out.exists()) out.delete();
                part.renameTo(out);
                return out;
            }
            c.disconnect();
            Thread.sleep(1500L * (attempt + 1));
            have = part.exists() ? part.length() : 0;
        }
        throw new RuntimeException("Download failed");
    }

    private String resolveRedirects(String url) throws Exception {
        for (int i = 0; i < 10; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false); c.setRequestMethod("HEAD"); c.connect();
            int code = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            c.disconnect();
            if (code / 100 == 3 && loc != null) { url = loc; continue; }
            return url;
        }
        return url;
    }

    private String filenameFromUrl(String url) {
        String p = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        int s = p.lastIndexOf('/');
        String n = s >= 0 ? p.substring(s + 1) : "video.mp4";
        return n.isEmpty() ? "video.mp4" : n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void playLocalPlaylistOrToast(File dir) {
        List<File> files = listMp4(dir);
        if (files.isEmpty()) { ui.post(() -> toast("No videos found")); return; }
        currentPlaylistFiles = new ArrayList<>(files);

        // Pre-apply rotation for first video BEFORE resetting
        VideoMetadata firstVm = getMetadataForFile(files.get(0));
        int firstRotation = firstVm != null ? firstVm.rotation : 0;
        String firstFitMode = firstVm != null ? firstVm.fitMode : "cover";
        lastAppliedRotation = firstRotation;
        lastAppliedFitMode = firstFitMode;

        ui.post(() -> {
            initPlayer();
            List<MediaItem> items = new ArrayList<>();
            for (File f : files) if (f.exists() && f.length() > 0) items.add(MediaItem.fromUri(Uri.fromFile(f)));
            if (items.isEmpty()) { toast("No playable videos"); return; }

            // Apply transform BEFORE starting playback to prevent glitch
            applyTextureViewTransform(firstRotation, firstFitMode);

            player.setMediaItems(items, true);
            player.setRepeatMode(Player.REPEAT_MODE_ALL);
            player.addListener(new Player.Listener() {
                @Override
                public void onMediaItemTransition(MediaItem m, int r) {
                    // Get next video's rotation BEFORE resetting
                    int nextIdx = player.getCurrentMediaItemIndex();
                    if (nextIdx >= 0 && nextIdx < currentPlaylistFiles.size()) {
                        File nextFile = currentPlaylistFiles.get(nextIdx);
                        VideoMetadata nextVm = getMetadataForFile(nextFile);
                        int nextRotation = nextVm != null ? nextVm.rotation : 0;
                        String nextFitMode = nextVm != null ? nextVm.fitMode : "cover";

                        // Apply immediately if different
                        if (nextRotation != lastAppliedRotation || !nextFitMode.equals(lastAppliedFitMode)) {
                            lastAppliedRotation = nextRotation;
                            lastAppliedFitMode = nextFitMode;
                            applyTextureViewTransform(nextRotation, nextFitMode);
                        }
                    }
                }

                @Override
                public void onVideoSizeChanged(VideoSize size) {
                    videoWidth = size.width;
                    videoHeight = size.height;
                    Log.d(TAG, "Video size: " + videoWidth + "x" + videoHeight);
                    // Re-apply transform with correct video dimensions
                    applyTextureViewTransform(lastAppliedRotation, lastAppliedFitMode);
                }
            });
            player.prepare();
            player.play();
        });
    }

    private List<File> listMp4(File dir) {
        File[] arr = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".mp4"));
        if (arr == null) return new ArrayList<>();
        List<File> list = new ArrayList<>();
        Collections.addAll(list, arr);
        list.sort(Comparator.comparing(f -> f.getName().toLowerCase()));
        return list;
    }

    private boolean readDownloadStatus(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20_000); c.setReadTimeout(30_000); c.connect();
        if (c.getResponseCode() / 100 != 2) { c.disconnect(); return false; }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line; while ((line = br.readLine()) != null) sb.append(line);
        } finally { c.disconnect(); }
        JSONObject o = new JSONObject(sb.toString());
        return o.optBoolean("download_status", false) || o.optBoolean("status", false);
    }

    private void postUpdateStatusTrue(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
            out.write("{\"status\": true}".getBytes(StandardCharsets.UTF_8));
        }
        c.getResponseCode(); c.disconnect();
    }

    private void initPlayer() {
        if (player != null) return;
        player = new ExoPlayer.Builder(this).build();

        // Connect player to TextureView surface
        if (surface != null) {
            player.setVideoSurface(surface);
        }

        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException e) {
                Log.e(TAG, "Player error: " + e.getMessage());
                int idx = player.getCurrentMediaItemIndex();
                if (player.getMediaItemCount() > 0) {
                    player.removeMediaItem(idx);
                    if (player.getMediaItemCount() > 0) {
                        player.seekTo(Math.min(idx, player.getMediaItemCount()-1), 0);
                        player.play();
                    }
                }
            }
        });
    }

    private void applyImmersive() {
        View d = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = d.getWindowInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @SuppressLint("HardwareIds")
    private String getAndroidId() { return Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID); }

    private File ensureMainDir() { File d = new File(Environment.getExternalStorageDirectory(), ROOT_DIR); if (!d.exists()) d.mkdirs(); return d; }
    private File ensureTempDir() { File d = new File(Environment.getExternalStorageDirectory(), TEMP_DIR); if (!d.exists()) d.mkdirs(); return d; }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ===== BLE =====
    private void ensureBluetoothPermissionAndConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, REQ_BT_PERMS);
                return;
            }
        }
        autoConnectToEsp32();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_BT_PERMS) {
            boolean ok = res.length > 0;
            for (int r : res) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) autoConnectToEsp32();
        }
    }

    private void autoConnectToEsp32() {
        if (btAdapter == null || !btAdapter.isEnabled()) return;
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (bm != null) btAdapter = bm.getAdapter();
        bleScanner = btAdapter.getBluetoothLeScanner();
        if (bleScanner != null) startBleScan();
    }

    private void startBleScan() {
        if (bleScanner == null || bleScanning) return;
        bleScanning = true;
        List<ScanFilter> f = new ArrayList<>(); f.add(new ScanFilter.Builder().setDeviceName(ESP32_DEVICE_NAME).build());
        bleScanner.startScan(f, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
        bleHandler.postDelayed(() -> { if (bleScanning) { stopBleScan(); scheduleBleReconnect(); } }, 15_000L);
    }

    private void stopBleScan() { if (bleScanning && bleScanner != null) { try { bleScanner.stopScan(scanCallback); } catch (Exception ignored) {} bleScanning = false; } }
    private void scheduleBleReconnect() { if (btShouldReconnect) bleHandler.postDelayed(this::autoConnectToEsp32, 5_000L); }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int t, ScanResult r) {
            BluetoothDevice dev = r.getDevice();
            if (dev != null && ESP32_DEVICE_NAME.equals(dev.getName())) {
                stopBleScan();
                try { btGatt = dev.connectGatt(FullScreenPlayerActivity.this, false, gattCallback); } catch (Exception e) { scheduleBleReconnect(); }
            }
        }
        @Override public void onScanFailed(int e) { scheduleBleReconnect(); }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int s, int n) {
            if (n == BluetoothGatt.STATE_CONNECTED) { try { g.discoverServices(); } catch (Exception ignored) {} }
            else if (n == BluetoothGatt.STATE_DISCONNECTED) { try { g.close(); } catch (Exception ignored) {} scheduleBleReconnect(); }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int s) {
            if (s != BluetoothGatt.GATT_SUCCESS) { scheduleBleReconnect(); return; }
            BluetoothGattService svc = g.getService(NUS_SERVICE_UUID);
            if (svc == null) { scheduleBleReconnect(); return; }
            nusTxChar = svc.getCharacteristic(NUS_CHAR_TX_UUID);
            nusRxChar = svc.getCharacteristic(NUS_CHAR_RX_UUID);
            if (nusTxChar == null || nusRxChar == null) { scheduleBleReconnect(); return; }
            try {
                g.setCharacteristicNotification(nusTxChar, true);
                BluetoothGattDescriptor d = nusTxChar.getDescriptor(CCCD_UUID);
                if (d != null) { d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(d); }
            } catch (Exception e) { scheduleBleReconnect(); }
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (!NUS_CHAR_TX_UUID.equals(c.getUuid())) return;
            byte[] v = c.getValue(); if (v == null) return;
            String msg = new String(v, StandardCharsets.UTF_8).trim();
            if (!msg.isEmpty()) handleBtCommand(msg);
        }
    };

    private void handleBtCommand(String cmd) {
        String lower = cmd.toLowerCase(Locale.US);
        if (lower.contains("temperature")) {
            Matcher m = TEMP_PATTERN.matcher(cmd);
            if (m.find()) try { lastTemperatureValue = Float.parseFloat(m.group(1)); } catch (Exception ignored) {}
        }
        if (lower.contains("reed") && lower.contains("open")) incrementCounts();
        ui.post(() -> {
            switch (cmd.toUpperCase(Locale.US)) {
                case "PLAY": if (player != null) player.play(); break;
                case "PAUSE": if (player != null) player.pause(); break;
                case "NEXT": if (player != null && player.getMediaItemCount() > 0) { player.seekTo((player.getCurrentMediaItemIndex()+1) % player.getMediaItemCount(), 0); player.play(); } break;
            }
        });
    }

    private void sendTemperatureToServer(float t) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(updateTemperatureUrl(getAndroidId())).openConnection();
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
                    out.write(String.format(Locale.US, "{\"temperature\": %.2f}", t).getBytes(StandardCharsets.UTF_8));
                }
                c.getResponseCode(); c.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    private void incrementCounts() {
        new Thread(() -> {
            try {
                String id = getAndroidId();
                HttpURLConnection c = (HttpURLConnection) new URL(countsUrl(id)).openConnection();
                c.connect();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = br.readLine()) != null) sb.append(line);
                } finally { c.disconnect(); }
                JSONObject o = new JSONObject(sb.toString());
                postCount(dailyUpdateUrl(id), "daily_count", o.optInt("daily_count", 0) + 1);
                postCount(monthlyUpdateUrl(id), "monthly_count", o.optInt("monthly_count", 0) + 1);
            } catch (Exception ignored) {}
        }).start();
    }

    private void postCount(String url, String key, int val) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
            out.write(("{\"" + key + "\": " + val + "}").getBytes(StandardCharsets.UTF_8));
        }
        c.getResponseCode(); c.disconnect();
    }
}