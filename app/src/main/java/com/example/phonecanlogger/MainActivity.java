package com.example.phonecanlogger;

import android.app.Activity;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A small "SavvyCAN helper" for your phone: type in the IP address of a
 * GVRET / ESP32RET board, connect, and log every CAN frame to a CSV in Downloads.
 */
public class MainActivity extends Activity {

    private static final int GVRET_TCP_PORT = 23;    // GVRET boards listen on this TCP port
    private static final String PREFS_NAME = "settings";
    private static final String PREF_LAST_IP = "last_ip";
    private static final String CSV_HEADER =
            "Time Stamp,ID,Extended,Dir,Bus,LEN,D1,D2,D3,D4,D5,D6,D7,D8\n";

    // --- screen ---
    private EditText ipInput;
    private Button connectButton;
    private Button logButton;
    private TextView statusText;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // --- network ---
    private Socket socket;
    private volatile boolean connected = false;

    // --- logging (everything below is protected by logLock) ---
    private final Object logLock = new Object();
    private BufferedWriter logWriter;   // null when we are not logging
    private String logFileName = "";
    private int framesCaptured = 0;
    private long bytesWritten = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Keep the screen on so logging doesn't stop while you drive.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ipInput = findViewById(R.id.ipInput);
        connectButton = findViewById(R.id.connectButton);
        logButton = findViewById(R.id.logButton);
        statusText = findViewById(R.id.statusText);

        // Fill in the IP address used last time.
        ipInput.setText(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_LAST_IP, ""));

        connectButton.setOnClickListener(v -> onConnectClicked());
        logButton.setOnClickListener(v -> onLogClicked());

        updateStatusEverySecond();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        stopLogging();
        // Close the socket on a background thread to keep network calls off the UI thread.
        new Thread(() -> closeQuietly(socket)).start();
    }

    // ------------------------------------------------------------------
    // Connecting
    // ------------------------------------------------------------------

    private void onConnectClicked() {
        if (connected) {
            new Thread(() -> closeQuietly(socket)).start(); // this ends the read loop
            return;
        }

        String ip = ipInput.getText().toString().trim();
        if (ip.isEmpty()) {
            showToast("Type the IP address of the board first");
            return;
        }

        // Remember it for next time.
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_LAST_IP, ip).apply();

        connectButton.setEnabled(false);
        ipInput.setEnabled(false);
        new Thread(() -> connectAndRead(ip)).start();
    }

    /** Runs on a background thread: connects, then reads until the connection ends. */
    private void connectAndRead(String ip) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, GVRET_TCP_PORT), 5000);

            // 0xE7 0xE7 puts the board in binary mode, 0xF1 0x09 asks it to confirm.
            OutputStream out = s.getOutputStream();
            out.write(new byte[]{(byte) 0xE7, (byte) 0xE7, (byte) 0xF1, 0x09});
            out.flush();

            socket = s;
            connected = true;
            runOnUiThread(() -> {
                connectButton.setText(R.string.disconnect);
                connectButton.setEnabled(true);
            });

            GvretParser parser = new GvretParser(this::saveFrame);
            InputStream in = s.getInputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                for (int i = 0; i < count; i++) {
                    parser.processByte(buffer[i] & 0xFF);
                }
            }
        } catch (IOException e) {
            // If we closed the socket ourselves (Disconnect button) there is nothing to report.
            if (!s.isClosed()) {
                showToast("Could not connect or connection lost: " + e.getMessage());
            }
        } finally {
            closeQuietly(s);
            connected = false;
            runOnUiThread(() -> {
                connectButton.setText(R.string.connect);
                connectButton.setEnabled(true);
                ipInput.setEnabled(true);
            });
        }
    }

    // ------------------------------------------------------------------
    // Logging
    // ------------------------------------------------------------------

    private void onLogClicked() {
        boolean isLogging;
        synchronized (logLock) {
            isLogging = (logWriter != null);
        }
        if (isLogging) {
            stopLogging();
            logButton.setText(R.string.start_logging);
        } else if (startLogging()) {
            logButton.setText(R.string.stop_logging);
        }
    }

    /** Creates a new CSV file in the Downloads folder. Returns false if that failed. */
    private boolean startLogging() {
        String name = new SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.US).format(new Date()) + ".csv";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/csv");

        try {
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("could not create file");
            OutputStream out = getContentResolver().openOutputStream(uri);
            if (out == null) throw new IOException("could not open file");

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            writer.write(CSV_HEADER);

            synchronized (logLock) {
                logWriter = writer;
                logFileName = name;
                framesCaptured = 0;
                bytesWritten = CSV_HEADER.length();
            }
            return true;
        } catch (IOException e) {
            showToast("Could not start logging: " + e.getMessage());
            return false;
        }
    }

    private void stopLogging() {
        synchronized (logLock) {
            if (logWriter == null) return;
            closeQuietly(logWriter);
            logWriter = null;
        }
    }

    /** Called by the parser (on the network thread) for every frame received. */
    private void saveFrame(CanFrame frame) {
        synchronized (logLock) {
            if (logWriter == null) return; // not logging right now
            String line = frame.toCsvLine();
            try {
                logWriter.write(line);
                framesCaptured++;
                bytesWritten += line.length();
            } catch (IOException e) {
                // Ignore a failed write; the status text will stop counting up.
            }
        }
    }

    // ------------------------------------------------------------------
    // Status text
    // ------------------------------------------------------------------

    /** Once a second: update the status box and push buffered data to the file. */
    private void updateStatusEverySecond() {
        StringBuilder text = new StringBuilder();
        if (connected) {
            text.append("Connected to a capture device.\n");
        }
        synchronized (logLock) {
            if (logWriter != null) {
                try {
                    logWriter.flush();
                } catch (IOException e) {
                    // ignore
                }
                text.append("Writing to file: Downloads/").append(logFileName).append('\n');
                text.append("Number of frames captured: ").append(framesCaptured).append('\n');
                text.append("File size: ").append(bytesWritten).append('\n');
            }
        }
        statusText.setText(text);
        handler.postDelayed(this::updateStatusEverySecond, 1000);
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private static void closeQuietly(Closeable thing) {
        if (thing == null) return;
        try {
            thing.close();
        } catch (IOException e) {
            // nothing useful to do
        }
    }
}
