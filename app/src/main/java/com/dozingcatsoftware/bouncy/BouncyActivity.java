package com.dozingcatsoftware.bouncy;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.badlogic.gdx.physics.box2d.Box2D;
import com.dozingcatsoftware.vectorpinball.model.IStringResolver;
import com.dozingcatsoftware.vectorpinball.util.IOUtils;
import com.dozingcatsoftware.vectorpinball.model.Field;
import com.dozingcatsoftware.vectorpinball.model.FieldDriver;
import com.dozingcatsoftware.vectorpinball.model.GameState;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;

public class BouncyActivity extends Activity {

    static {
        Box2D.init();
    }

    CanvasFieldView canvasFieldView;
    ScoreView scoreView;

    GLFieldView glFieldView;
    GL10Renderer gl10Renderer;
    GL20Renderer gl20Renderer;
    boolean useOpenGL20;

    View buttonPanel;
    Button switchTableButton;
    Button endGameButton;
    CheckBox unlimitedBallsToggle;
    final static int ACTIVITY_PREFERENCES = 1;

    Handler handler = new Handler();

    IStringResolver stringLookupFn = (key, params) -> {
        int stringId = getResources().getIdentifier(key, "string", getPackageName());
        return getString(stringId, params);
    };
    Field field = new Field(System::currentTimeMillis, stringLookupFn, new VPSoundpool.Player());

    int numberOfLevels;
    int currentLevel = 1;
    List<Long> highScores;
    static int MAX_NUM_HIGH_SCORES = 5;
    static String HIGHSCORES_PREFS_KEY = "highScores";
    static String OLD_HIGHSCORE_PREFS_KEY = "highScore";
    static String INITIAL_LEVEL_PREFS_KEY = "initialLevel";

    boolean useZoom = true;
    static final float ZOOM_FACTOR = 1.5f;

    // Delay after ending a game, before a touch will start a new game.
    static final long END_GAME_DELAY_MS = 1000;
    Long endGameTime = System.currentTimeMillis() - END_GAME_DELAY_MS;

    FieldDriver fieldDriver = new FieldDriver();
    FieldViewManager fieldViewManager = new FieldViewManager();
    OrientationListener orientationListener;

    private static final String TAG = "BouncyActivity";

    /** Được gọi khi activity khởi tạo ban đầu. */
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String arch = System.getProperty("os.arch");
        Log.i(TAG, "App started, os.arch=" + arch);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.main);

        this.numberOfLevels = FieldLayoutReader.getNumberOfLevels(this);
        this.currentLevel = getInitialLevel();
        resetFieldForCurrentLevel();

        canvasFieldView = findViewById(R.id.canvasFieldView);
        canvasFieldView.setManager(fieldViewManager);

        glFieldView = findViewById(R.id.glFieldView);


        useOpenGL20 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
        if (useOpenGL20) {
            gl20Renderer = new GL20Renderer(glFieldView, (shaderPath) -> {
                try {
                    InputStream input = getAssets().open(shaderPath);
                    return IOUtils.utf8FromStream(input);
                }
                catch(IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
            gl20Renderer.setManager(fieldViewManager);
        }
        else {
            gl10Renderer = new GL10Renderer(glFieldView);
            gl10Renderer.setManager(fieldViewManager);
        }

        fieldViewManager.setField(field);
        fieldViewManager.setStartGameAction(() -> doStartGame(null));

        scoreView = findViewById(R.id.scoreView);
        scoreView.setField(field);

        fieldDriver.setField(field);
        fieldDriver.setDrawFunction(fieldViewManager::draw);

        highScores = this.highScoresFromPreferencesForCurrentLevel();
        scoreView.setHighScores(highScores);

        buttonPanel = findViewById(R.id.buttonPanel);
        switchTableButton = findViewById(R.id.switchTableButton);
        endGameButton = findViewById(R.id.endGameButton);
        unlimitedBallsToggle = findViewById(R.id.unlimitedBallsToggle);

        // TODO: allow field configuration to specify whether tilting is allowed

        updateFromPreferences();

        // Khao báo audio, loading resources trong separate thread.
        VPSoundpool.initSounds(this);
        (new Thread(VPSoundpool::loadSounds)).start();
        this.setVolumeControlStream(AudioManager.STREAM_MUSIC);
    }

    @Override public void onResume() {
        super.onResume();

        try {
            Method setUiMethod = View.class.getMethod("setSystemUiVisibility", int.class);
            setUiMethod.invoke(scoreView, 1);
        }
        catch (Exception ignored) {
        }

        fieldDriver.resetFrameRate();
    }

    @Override public void onPause() {
        pauseGame();
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        // Hành động main activity pausing and resuming.
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            pauseGame();
        }
        else {
            // Nếu đang trong game sẽ về màn hình paused.
            if (field.getGameState().isGameInProgress()) {
                if (glFieldView != null) {
                    // Gọi đến onResume,
                    glFieldView.onResume();
                }
                fieldViewManager.draw();
                showPausedButtons();
            }
            else {
                unpauseGame();
            }
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        //Khi game đang chạy, sẽ tạm dừng thay vì thoát game khi ấn nút back
        // Sẽ thoát game khi đang ở màn hình chờ bắt đầu
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (field.getGameState().isGameInProgress() && !field.getGameState().isPaused()) {
                pauseGame();
                showPausedButtons();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    public void pauseGame() {
        VPSoundpool.pauseMusic();
        if (field.getGameState().isPaused()) return;
        field.getGameState().setPaused(true);

        if (orientationListener != null) orientationListener.stop();
        fieldDriver.stop();
        if (glFieldView != null) glFieldView.onPause();
    }

    public void unpauseGame() {
        if (!field.getGameState().isPaused()) return;
        field.getGameState().setPaused(false);

        handler.postDelayed(this::tick, 75);
        if (orientationListener != null) orientationListener.start();

        fieldDriver.start();
        if (glFieldView != null) glFieldView.onResume();

        if (field.getGameState().isGameInProgress()) {
            buttonPanel.setVisibility(View.GONE);
        }
    }

    void showPausedButtons() {
        endGameButton.setVisibility(View.VISIBLE);
        switchTableButton.setVisibility(View.GONE);
        unlimitedBallsToggle.setVisibility(View.GONE);
        buttonPanel.setVisibility(View.VISIBLE);
    }

    @Override public void onDestroy() {
        VPSoundpool.cleanup();
        super.onDestroy();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);

        switch (requestCode) {
            case ACTIVITY_PREFERENCES:
                updateFromPreferences();
                break;
        }
    }

    void gotoPreferences() {
        Intent settingsActivity = new Intent(getBaseContext(), BouncyPreferences.class);
        startActivityForResult(settingsActivity, ACTIVITY_PREFERENCES);
    }

    void gotoAbout() {
        AboutActivity.startForLevel(this, this.currentLevel);
    }

    // Cập nhật tùy chọn, Gọi khi khởi chạy và sau khi hoạt động tùy chọn kết thúc.
    void updateFromPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        fieldViewManager.setIndependentFlippers(prefs.getBoolean("independentFlippers", true));
        scoreView.setShowFPS(prefs.getBoolean("showFPS", false));

        // tùy chọn OpenGL/Canvas,
        // Thay đổi tốc độ khung hình
        int lineWidth = 0;
        try {
            lineWidth = Integer.parseInt(prefs.getString("lineWidth", "0"));
        }
        catch (NumberFormatException ignored) {}
        if (lineWidth != fieldViewManager.getCustomLineWidth()) {
            fieldViewManager.setCustomLineWidth(lineWidth);
        }

        boolean useOpenGL = prefs.getBoolean("useOpenGL", false);
        if (useOpenGL) {
            if (glFieldView.getVisibility() != View.VISIBLE) {
                canvasFieldView.setVisibility(View.GONE);
                glFieldView.setVisibility(View.VISIBLE);
                fieldViewManager.setFieldRenderer(useOpenGL20 ? gl20Renderer : gl10Renderer);
            }
        }
        else {
            if (canvasFieldView.getVisibility() != View.VISIBLE) {
                glFieldView.setVisibility(View.GONE);
                canvasFieldView.setVisibility(View.VISIBLE);
                fieldViewManager.setFieldRenderer(canvasFieldView);
            }
        }

        useZoom = prefs.getBoolean("zoom", true);
        fieldViewManager.setZoom(useZoom ? ZOOM_FACTOR : 1.0f);

        VPSoundpool.setSoundEnabled(prefs.getBoolean("sound", true));
        VPSoundpool.setMusicEnabled(prefs.getBoolean("music", true));
    }

    // Chạy mỗi 100 miligiay để chậy nhật điểm số và điểm cao
    void tick() {
        scoreView.invalidate();
        scoreView.setFPS(fieldDriver.getAverageFPS());
        updateHighScoreAndButtonPanel();
        handler.postDelayed(this::tick, 100);
    }

    /**
     * Nếu điểm của trận hiện tại hoặc trận trước lớn hơn điểm cao trước đó,
     * cập nhật điểm cao trong sở thích và ScoreView. Đồng thời hiển thị bảng nút nếu trò chơi đã kết thúc..
     */
    void updateHighScoreAndButtonPanel() {
        // Ta kiểm ta một lần khi trò chơi kết thúc, trước khi kiển thị  bảng nút.
        if (buttonPanel.getVisibility() == View.VISIBLE) return;
        synchronized (field) {
            GameState state = field.getGameState();
            if (!field.getGameState().isGameInProgress()) {
                //trò chơi vừa kết thúc, hiển thị bảng điều khiển nút và đặt dấu thời gian kết thúc trò chơi
                this.endGameTime = System.currentTimeMillis();
                endGameButton.setVisibility(View.GONE);
                switchTableButton.setVisibility(View.VISIBLE);
                unlimitedBallsToggle.setVisibility(View.VISIBLE);
                buttonPanel.setVisibility(View.VISIBLE);

                //Không có điểm số cao cho các quả bóng không giới hạn.
                if (!state.hasUnlimitedBalls()) {
                    long score = field.getGameState().getScore();
                    //Thêm vào danh sách điểm cao nếu điểm vượt qua điểm cao nhất hiện có,
                    //hoặc nếu tất cả các vị trí điểm cao không được sử dụng.
                    if (score > highScores.get(this.highScores.size() - 1) ||
                            highScores.size() < MAX_NUM_HIGH_SCORES) {
                        this.updateHighScoreForCurrentLevel(score);
                    }
                }
            }
        }
    }

    // Lưu trữ điểm cao riêng biệt cho từng trường, sử dụng unique suffix in prefs key.
    String highScorePrefsKeyForLevel(int theLevel) {
        return HIGHSCORES_PREFS_KEY + "." + theLevel;
    }

    /**
     * Trả về danh sách điểm cao SharedPreferences. Luôn trả về giá trị 0
     * danh sách, sẽ là [0] nếu không có điểm cao nào được lưu trữ.
     */
    List<Long> highScoresFromPreferences(int theLevel) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        String scoresAsString = prefs.getString(highScorePrefsKeyForLevel(theLevel), "");
        if (scoresAsString.length() > 0) {
            try {
                String[] fields = scoresAsString.split(",");
                List<Long> scores = new ArrayList<>();
                for (String f : fields) {
                    scores.add(Long.valueOf(f));
                }
                return scores;
            }
            catch (NumberFormatException ex) {
                return Collections.singletonList(0L);
            }
        }
        else {
            long oldPrefsScore = prefs.getLong(OLD_HIGHSCORE_PREFS_KEY + "." + currentLevel, 0);
            return Collections.singletonList(oldPrefsScore);
        }
    }

    void writeHighScoresToPreferences(int level, List<Long> scores) {
        StringBuilder scoresAsString = new StringBuilder();
        scoresAsString.append(scores.get(0));
        for (int i = 1; i < scores.size(); i++) {
            scoresAsString.append(",").append(scores.get(i));
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(highScorePrefsKeyForLevel(level), scoresAsString.toString());
        editor.commit();
    }

    List<Long> highScoresFromPreferencesForCurrentLevel() {
        return highScoresFromPreferences(currentLevel);
    }

    /** Cập nhật điểm số cao trong màn hình ScoreView và duy trì nó với SharedPreferences.. */
    void updateHighScore(int theLevel, long score) {
        List<Long> newHighScores = new ArrayList<>(this.highScores);
        newHighScores.add(score);
        Collections.sort(newHighScores);
        Collections.reverse(newHighScores);
        if (newHighScores.size() > MAX_NUM_HIGH_SCORES) {
            newHighScores = newHighScores.subList(0, MAX_NUM_HIGH_SCORES);
        }
        this.highScores = newHighScores;
        writeHighScoresToPreferences(theLevel, this.highScores);
        scoreView.setHighScores(this.highScores);
    }

    void updateHighScoreForCurrentLevel(long score) {
        updateHighScore(currentLevel, score);
    }

    int getInitialLevel() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        int startLevel = prefs.getInt(INITIAL_LEVEL_PREFS_KEY, 1);
        if (startLevel < 1 || startLevel > numberOfLevels) startLevel = 1;
        return startLevel;
    }

    void setInitialLevel(int level) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(INITIAL_LEVEL_PREFS_KEY, level);
        editor.commit();
    }

    // Các thương thức hành động của nút được định nghĩa android:onClick values in main.xml.
    public void doStartGame(View view) {
        if (field.getGameState().isPaused()) {
            unpauseGame();
            return;
        }
        //Tránh bắt đầu ngẫu nhiên do chạm ngay sau khi trò chơi kết thúc
        if (endGameTime == null || (System.currentTimeMillis() < endGameTime + END_GAME_DELAY_MS)) {
            return;
        }
        if (!field.getGameState().isGameInProgress()) {
            buttonPanel.setVisibility(View.GONE);
            resetFieldForCurrentLevel();

            if (unlimitedBallsToggle.isChecked()) {
                field.startGameWithUnlimitedBalls();
            }
            else {
                field.startGame();
            }
            VPSoundpool.playStart();
            endGameTime = null;
        }
    }

    public void doEndGame(View view) {
        //Trò chơi có thể bị tạm dừng nếu kết thúc thủ công từ nút.
        unpauseGame();
        field.endGame();
    }

    public void doPreferences(View view) {
        gotoPreferences();
    }

    public void doAbout(View view) {
        gotoAbout();
    }

    public void scoreViewClicked(View view) {
        if (field.getGameState().isGameInProgress()) {
            if (field.getGameState().isPaused()) {
                unpauseGame();
            }
            else {
                pauseGame();
                showPausedButtons();
            }
        }
        else {
            doStartGame(null);
        }
    }

    public void doSwitchTable(View view) {
        currentLevel = (currentLevel == numberOfLevels) ? 1 : currentLevel + 1;
        synchronized (field) {
            resetFieldForCurrentLevel();
        }
        this.setInitialLevel(currentLevel);
        this.highScores = this.highScoresFromPreferencesForCurrentLevel();
        scoreView.setHighScores(highScores);
        //hiệu suất có thể khác nhau trên các bảng khác nhau.
        fieldDriver.resetFrameRate();
    }

    void resetFieldForCurrentLevel() {
        field.resetForLayoutMap(FieldLayoutReader.layoutMapForLevel(this, currentLevel));
        //Để trường chạy trong một phần giây trước khi nó được vẽ.
        synchronized (field) {
            field.tick((long) (250_000_000 * field.getTargetTimeRatio()), 4);
        }
    }
}