package com.example.phonecanlogger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small "SavvyCAN on your phone": connect to a GVRET / ESP32RET board by IP address,
 * watch the CAN frames live, and log them to a CSV file in Downloads.
 *
 * Features: auto scroll, overwrite mode (one line per ID), ID filter, and colours that show
 * which bits changed (red = changed in the last 5 seconds, blue = changed earlier).
 * The filter applies to both the screen and the log file.
 */
public class MainActivity extends Activity {

    private static final int GVRET_TCP_PORT = 23;   // GVRET boards listen on this TCP port
    private static final int MAX_ROWS = 5000;       // most lines kept in the list (normal mode)
    private static final int MAX_PENDING = 20000;   // most frames allowed to wait for the screen
    private static final int REFRESH_MS = 100;      // the screen updates 10 times a second
    private static final String PREFS_NAME = "settings";
    private static final String PREF_LAST_IP = "last_ip";
    private static final String CSV_HEADER =
            "Time Stamp,ID,Extended,Dir,Bus,LEN,D1,D2,D3,D4,D5,D6,D7,D8\n";

    // --- screen ---
    private EditText ipInput;
    private Button connectButton;
    private Button filterButton;
    private Button clearButton;
    private Button logButton;
    private CheckBox autoScrollBox;
    private CheckBox overwriteBox;
    private CheckBox bitsBox;
    private ListView frameList;
    private TextView statusText;
    private TextView legendText;
    private FrameAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int tickCount = 0;
    private String lastStatus = "";

    // --- network ---
    private Socket socket;
    private volatile boolean connected = false;
    private volatile long totalFrames = 0;      // every frame received
    private volatile long acceptedFrames = 0;   // frames that passed the filter

    // --- frames travelling from the network thread to the screen ---
    private final ConcurrentLinkedQueue<FrameRow> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger();

    // --- what the list shows (only touched on the UI thread) ---
    private final List<FrameRow> appendRows = new ArrayList<>();                // normal mode
    private final TreeMap<Long, FrameRow> latestByKey = new TreeMap<>();        // overwrite mode

    private final FrameFilter filter = new FrameFilter();

    // --- bit change colours (UI thread only) ---
    private final ChangeTracker tracker = new ChangeTracker();
    private long lastChangeMs = 0;   // when any bit last changed, so we know when to keep repainting

    // --- logging (everything below is protected by logLock) ---
    private final Object logLock = new Object();
    private BufferedWriter logWriter;   // null when we are not logging
    private String logFileName = "";
    private int framesLogged = 0;
    private long bytesWritten = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Keep the screen on so logging doesn't stop while you drive.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ipInput = findViewById(R.id.ipInput);
        connectButton = findViewById(R.id.connectButton);
        filterButton = findViewById(R.id.filterButton);
        clearButton = findViewById(R.id.clearButton);
        logButton = findViewById(R.id.logButton);
        autoScrollBox = findViewById(R.id.autoScrollBox);
        overwriteBox = findViewById(R.id.overwriteBox);
        bitsBox = findViewById(R.id.bitsBox);
        frameList = findViewById(R.id.frameList);
        statusText = findViewById(R.id.statusText);
        legendText = findViewById(R.id.legendText);
        legendText.setText(RowTextBuilder.legend());

        adapter = new FrameAdapter();
        frameList.setAdapter(adapter);

        // Fill in the IP address used last time.
        ipInput.setText(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_LAST_IP, ""));

        connectButton.setOnClickListener(v -> onConnectClicked());
        logButton.setOnClickListener(v -> onLogClicked());
        filterButton.setOnClickListener(v -> showFilterDialog());
        clearButton.setOnClickListener(v -> clearFrames());
        // Switching between normal and overwrite mode starts the list fresh.
        overwriteBox.setOnCheckedChangeListener((box, isChecked) -> clearFrames());
        // Hex <-> bits only changes how lines are drawn.
        bitsBox.setOnCheckedChangeListener((box, isChecked) -> adapter.notifyDataSetChanged());

        refreshScreen();
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

        // Remember it for next time and tuck the keyboard away.
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_LAST_IP, ip).apply();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        keyboard.hideSoftInputFromWindow(ipInput.getWindowToken(), 0);

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
            totalFrames = 0;
            acceptedFrames = 0;
            connected = true;
            runOnUiThread(() -> {
                connectButton.setText(R.string.disconnect);
                connectButton.setEnabled(true);
            });

            GvretParser parser = new GvretParser(this::onFrameReceived);
            InputStream in = s.getInputStream();
            byte[] buffer = new byte[4096];
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
    // Frames coming in (network thread)
    // ------------------------------------------------------------------

    /** Called by the parser for every frame received. */
    private void onFrameReceived(CanFrame frame) {
        totalFrames++;

        // The filter decides for both the screen and the log file.
        if (!filter.accepts(frame.id)) return;
        acceptedFrames++;

        // Hand a copy to the screen. If the screen can't keep up we drop the copy, never the log line.
        if (pendingCount.get() < MAX_PENDING) {
            pending.add(FrameRow.from(frame));
            pendingCount.incrementAndGet();
        }

        writeToLog(frame);
    }

    // ------------------------------------------------------------------
    // The frame list (UI thread)
    // ------------------------------------------------------------------

    /** Runs 10 times a second: moves new frames into the list and updates the labels. */
    private void refreshScreen() {
        addPendingFramesToList();
        updateFilterButton();
        updateStatusText();
        handler.postDelayed(this::refreshScreen, REFRESH_MS);
    }

    private void addPendingFramesToList() {
        boolean overwrite = overwriteBox.isChecked();
        boolean changed = false;
        long now = SystemClock.elapsedRealtime();

        FrameRow row;
        while ((row = pending.poll()) != null) {
            pendingCount.decrementAndGet();
            changed = true;

            // Compare with the previous frame of this ID and remember which bits changed.
            if (tracker.update(row, now)) lastChangeMs = now;

            if (overwrite) {
                // One line per bus + ID: replace the old line and count how often it arrived.
                FrameRow previous = latestByKey.get(row.key());
                if (previous != null) row.count = previous.count + 1;
                latestByKey.put(row.key(), row);
            } else {
                appendRows.add(row);
            }
        }

        // Normal mode keeps only the newest MAX_ROWS lines so memory stays small.
        if (appendRows.size() > MAX_ROWS) {
            appendRows.subList(0, appendRows.size() - MAX_ROWS).clear();
        }

        if (changed) {
            showRows();
        } else if (now < lastChangeMs + FrameRow.RECENT_MS + 2 * REFRESH_MS) {
            // No new frames, but colours are still fading from red to blue, so redraw.
            adapter.notifyDataSetChanged();
        }
    }

    /** Gives the list the current rows and scrolls down if Auto scroll is on. */
    private void showRows() {
        boolean overwrite = overwriteBox.isChecked();
        List<FrameRow> rows = overwrite ? new ArrayList<>(latestByKey.values()) : appendRows;
        adapter.setRows(rows);
        // Overwrite mode shows a fixed set of IDs, so there is nothing to scroll to.
        if (autoScrollBox.isChecked() && !overwrite && !rows.isEmpty()) {
            frameList.setSelection(rows.size() - 1);
        }
    }

    private void clearFrames() {
        pending.clear();
        pendingCount.set(0);
        appendRows.clear();
        latestByKey.clear();
        tracker.clear();
        lastChangeMs = 0;
        showRows();
    }

    // ------------------------------------------------------------------
    // Filter
    // ------------------------------------------------------------------

    private void showFilterDialog() {
        final long[] ids = filter.knownIds();
        if (ids.length == 0) {
            showToast("No frames seen yet. Connect first.");
            return;
        }

        final String[] labels = new String[ids.length];
        final boolean[] checked = new boolean[ids.length];
        for (int i = 0; i < ids.length; i++) {
            labels[i] = idLabel(ids[i]);
            checked[i] = filter.isEnabled(ids[i]);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Show and log these IDs")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("OK", (d, which) -> applyFilter(ids, checked))
                .setNegativeButton("None", null)
                .setNeutralButton("All", null)
                .create();
        dialog.show();

        // "All" and "None" tick or untick the whole list without closing the dialog.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> setAllChecked(dialog, checked, true));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> setAllChecked(dialog, checked, false));
    }

    private static void setAllChecked(AlertDialog dialog, boolean[] checked, boolean value) {
        for (int i = 0; i < checked.length; i++) {
            checked[i] = value;
            dialog.getListView().setItemChecked(i, value);
        }
    }

    private void applyFilter(long[] ids, boolean[] checked) {
        filter.apply(ids, checked);

        // Remove lines of IDs that are now switched off.
        appendRows.removeIf(row -> !filter.isEnabled(row.id));
        latestByKey.values().removeIf(row -> !filter.isEnabled(row.id));
        showRows();
        updateFilterButton();
    }

    private static String idLabel(long id) {
        return id > 0x7FF ? String.format(Locale.US, "0x%08X", id) : String.format(Locale.US, "0x%03X", id);
    }

    private void updateFilterButton() {
        String text = "Filter";
        if (filter.isActive()) {
            text = "Filter (" + filter.enabledCount() + "/" + filter.knownCount() + ")";
        }
        if (!text.contentEquals(filterButton.getText())) {
            filterButton.setText(text);
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
                framesLogged = 0;
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

    /** Writes one frame to the file. Only frames that passed the filter get here. */
    private void writeToLog(CanFrame frame) {
        synchronized (logLock) {
            if (logWriter == null) return; // not logging right now
            String line = frame.toCsvLine();
            try {
                logWriter.write(line);
                framesLogged++;
                bytesWritten += line.length();
            } catch (IOException e) {
                // Ignore a failed write; the counter in the status text will stop going up.
            }
        }
    }

    // ------------------------------------------------------------------
    // Status text
    // ------------------------------------------------------------------

    private void updateStatusText() {
        StringBuilder text = new StringBuilder();
        text.append(connected ? "Connected" : "Not connected");
        text.append("  |  received ").append(totalFrames);
        text.append(", passed filter ").append(acceptedFrames).append('\n');

        synchronized (logLock) {
            if (logWriter != null) {
                // Push buffered data to the file about once a second.
                if (tickCount % (1000 / REFRESH_MS) == 0) {
                    try {
                        logWriter.flush();
                    } catch (IOException e) {
                        // ignore
                    }
                }
                text.append("Logging to Downloads/").append(logFileName).append('\n');
                text.append(framesLogged).append(" frames logged, ").append(bytesWritten).append(" bytes");
            }
        }
        tickCount++;

        String newStatus = text.toString();
        if (!newStatus.equals(lastStatus)) {
            lastStatus = newStatus;
            statusText.setText(newStatus);
        }
    }

    // ------------------------------------------------------------------
    // List adapter: turns a FrameRow into one line of text
    // ------------------------------------------------------------------

    private class FrameAdapter extends BaseAdapter {
        private List<FrameRow> rows = new ArrayList<>();

        void setRows(List<FrameRow> newRows) {
            rows = newRows;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view = (TextView) convertView;
            if (view == null) {
                view = new TextView(MainActivity.this);
                view.setTypeface(Typeface.MONOSPACE);
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                view.setPadding(4, 2, 4, 2);
            }
            view.setText(RowTextBuilder.build(rows.get(position), bitsBox.isChecked(),
                    overwriteBox.isChecked(), SystemClock.elapsedRealtime()));
            return view;
        }
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
