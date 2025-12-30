package com.example.videoplayer;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ProgressBar;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

public class VideoViewActivity extends AppCompatActivity {

    private VideoView videoView;
    private ProgressBar progressBar;
    private int lastPosMs = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_videoview);

        videoView  = findViewById(R.id.videoView);
        progressBar = findViewById(R.id.progress);

        // Use bundled resource: app/src/main/res/raw/my_video.mp4
        Uri uri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.output_480x1920);

        // IMPORTANT: no MediaController attached -> no play/pause UI ever shown
        progressBar.setVisibility(View.VISIBLE);
        videoView.setVideoURI(uri);

        videoView.setOnPreparedListener(mp -> {
            progressBar.setVisibility(View.GONE);
            mp.setLooping(true);
            // Fill screen by cropping edges (good for 480x1920 portrait)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            }
            videoView.start();
            enterImmersiveMode();
        });

        videoView.setOnErrorListener((mp, what, extra) -> {
            progressBar.setVisibility(View.GONE);
            return false;
        });

        // Block taps from pausing/doing anything (consumes touches)
        videoView.setOnTouchListener((v, e) -> true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        videoView.seekTo(lastPosMs);
        videoView.start();
        enterImmersiveMode();
    }

    @Override
    protected void onPause() {
        super.onPause();
        lastPosMs = videoView.getCurrentPosition();
        videoView.pause();
    }

    // Keep immersive full-screen even after transient system bars show
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    private void enterImmersiveMode() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            final WindowInsetsController c = decor.getWindowInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            // Legacy immersive sticky
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
}
