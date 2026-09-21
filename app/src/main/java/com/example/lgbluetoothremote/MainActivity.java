package com.example.lgbluetoothremote;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * V2 diagnostic/minimal Bluetooth HID remote.
 *
 * The first version registered a combined keyboard + media + mouse descriptor immediately from
 * the HID service callback. Some Android builds reject registerApp() in that situation. V2 keeps
 * the HID descriptor deliberately small (keyboard only) and lets the user explicitly register it
 * while this Activity is visibly in the foreground.
 */
public class MainActivity extends Activity {
    private static final int REQ_BT_PERMS = 10;
    private static final int REPORT_KEYBOARD = 0; // No Report-ID item in the minimal descriptor.

    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice tv;

    private boolean hidProxyReady = false;
    private boolean registered = false;
    private boolean connected = false;
    private boolean windowFocused = false;

    private TextView status;
    private TextView diagnostics;
    private TextView selected;
    private Button registerButton;
    private Button connectButton;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Standard boot-style 8-byte keyboard report: modifiers, reserved, six key usages.
    // Deliberately contains NO mouse, consumer-control or report IDs.
    private static final byte[] KEYBOARD_DESCRIPTOR = bytes(
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x06,       // Usage (Keyboard)
            0xA1, 0x01,       // Collection (Application)
            0x05, 0x07,       // Usage Page (Keyboard/Keypad)
            0x19, 0xE0,       // Usage Minimum (Keyboard LeftControl)
            0x29, 0xE7,       // Usage Maximum (Keyboard Right GUI)
            0x15, 0x00,       // Logical Minimum (0)
            0x25, 0x01,       // Logical Maximum (1)
            0x75, 0x01,       // Report Size (1)
            0x95, 0x08,       // Report Count (8)
            0x81, 0x02,       // Input (Data,Var,Abs) modifier byte
            0x95, 0x01,       // Report Count (1)
            0x75, 0x08,       // Report Size (8)
            0x81, 0x01,       // Input (Const,Array,Abs) reserved byte
            0x95, 0x06,       // Report Count (6)
            0x75, 0x08,       // Report Size (8)
            0x15, 0x00,       // Logical Minimum (0)
            0x25, 0x65,       // Logical Maximum (101)
            0x05, 0x07,       // Usage Page (Keyboard/Keypad)
            0x19, 0x00,       // Usage Minimum (Reserved)
            0x29, 0x65,       // Usage Maximum (Keyboard Application)
            0x81, 0x00,       // Input (Data,Array,Abs)
            0xC0              // End Collection
    );

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestBluetoothPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh diagnostics after returning from permission/Bluetooth dialogs.
        handler.postDelayed(this::updateDiagnostics, 250);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        windowFocused = hasFocus;
        updateDiagnostics();
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<String> missing = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            if (!missing.isEmpty()) {
                requestPermissions(missing.toArray(new String[0]), REQ_BT_PERMS);
                return;
            }
        }
        initBluetooth();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT_PERMS) {
            boolean allGranted = true;
            for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;
            if (!allGranted) {
                setStatus("Nearby devices permission is required.");
                updateDiagnostics();
                return;
            }
            initBluetooth();
        }
    }

    private boolean hasBtConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void initBluetooth() {
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            setStatus("Bluetooth is not available on this phone.");
            updateDiagnostics();
            return;
        }
        if (!adapter.isEnabled()) {
            setStatus("Turn Bluetooth ON, then return to this app.");
            try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
            catch (Exception ignored) {}
        }

        boolean requested = adapter.getProfileProxy(this, serviceListener, BluetoothProfile.HID_DEVICE);
        if (!requested) {
            setStatus("Android did not expose the Bluetooth HID Device service.");
        } else {
            setStatus("Bluetooth service requested. Wait for 'HID service ready', then tap Register Keyboard.");
        }
        updateDiagnostics();
    }

    private final BluetoothProfile.ServiceListener serviceListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.HID_DEVICE) return;
            hid = (BluetoothHidDevice) proxy;
            hidProxyReady = true;
            runOnUiThread(() -> {
                setStatus("HID service ready. Keep this screen open and tap Register Keyboard.");
                registerButton.setEnabled(true);
                updateDiagnostics();
            });
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile != BluetoothProfile.HID_DEVICE) return;
            hid = null;
            hidProxyReady = false;
            registered = false;
            connected = false;
            runOnUiThread(() -> {
                registerButton.setEnabled(false);
                setStatus("Bluetooth HID service disconnected.");
                updateDiagnostics();
            });
        }
    };

    private void registerKeyboard() {
        if (hid == null || !hidProxyReady) {
            setStatus("HID service is not ready yet.");
            return;
        }
        if (!hasBtConnectPermission()) {
            requestBluetoothPermissions();
            return;
        }

        // User initiated this while the activity is visible. A short delay also lets Android settle
        // after focus/permission transitions before checking UID importance in HidDeviceService.
        registerButton.setEnabled(false);
        setStatus("Registering minimal Bluetooth keyboard…");
        handler.postDelayed(() -> {
            if (!windowFocused) {
                setStatus("This window is not focused. Return to the app, then tap Register Keyboard again.");
                registerButton.setEnabled(true);
                updateDiagnostics();
                return;
            }

            BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                    "LG Remote Keyboard",
                    "Minimal Bluetooth keyboard for LG TV",
                    "LG Remote V2",
                    BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                    KEYBOARD_DESCRIPTOR
            );

            try {
                boolean commandAccepted = hid.registerApp(sdp, null, null, getMainExecutor(), hidCallback);
                if (commandAccepted) {
                    setStatus("Registration command accepted. Waiting for Android callback…");
                } else {
                    setStatus("Android rejected registerApp(). This ROM may block Bluetooth HID-device mode.");
                    registerButton.setEnabled(true);
                }
            } catch (SecurityException e) {
                setStatus("Bluetooth permission error: " + e.getClass().getSimpleName());
                registerButton.setEnabled(true);
            } catch (Throwable t) {
                setStatus("HID registration error: " + t.getClass().getSimpleName() + ": " + safeMessage(t));
                registerButton.setEnabled(true);
            }
            updateDiagnostics();
        }, 900);
    }

    private final BluetoothHidDevice.Callback hidCallback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean isRegistered) {
            registered = isRegistered;
            if (isRegistered && pluggedDevice != null) tv = pluggedDevice;
            runOnUiThread(() -> {
                registerButton.setEnabled(!isRegistered);
                setStatus(isRegistered
                        ? "SUCCESS: Bluetooth keyboard registered. Now make the phone discoverable and pair it from the LG TV."
                        : "Android callback says HID registration stopped/failed.");
                updateDiagnostics();
            });
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            connected = state == BluetoothProfile.STATE_CONNECTED;
            if (connected) tv = device;
            runOnUiThread(() -> {
                String name = safeName(device);
                if (state == BluetoothProfile.STATE_CONNECTED) setStatus("Connected to " + name + " ✓");
                else if (state == BluetoothProfile.STATE_CONNECTING) setStatus("Connecting to " + name + "…");
                else if (state == BluetoothProfile.STATE_DISCONNECTED) setStatus("Disconnected from " + name);
                updateConnectLabel();
                updateDiagnostics();
            });
        }
    };

    private String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? "no message" : m;
    }

    private String safeName(BluetoothDevice d) {
        if (d == null || !hasBtConnectPermission()) return "device";
        try {
            String n = d.getName();
            return n == null || n.trim().isEmpty() ? d.getAddress() : n;
        } catch (SecurityException e) {
            return "device";
        }
    }

    private void makeDiscoverable() {
        if (!registered) {
            Toast.makeText(this, "Register the Bluetooth keyboard first.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivity(i);
            Toast.makeText(this, "On the LG TV, search for a Bluetooth keyboard now.", Toast.LENGTH_LONG).show();
        } catch (SecurityException e) {
            requestBluetoothPermissions();
        }
    }

    private void choosePairedDevice() {
        if (adapter == null || !hasBtConnectPermission()) {
            requestBluetoothPermissions();
            return;
        }

        Set<BluetoothDevice> bonded;
        try { bonded = adapter.getBondedDevices(); }
        catch (SecurityException e) { requestBluetoothPermissions(); return; }

        if (bonded == null || bonded.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No paired devices")
                    .setMessage("After successful registration, tap Make discoverable and pair the phone from the LG TV's Bluetooth keyboard/controller menu.")
                    .setPositiveButton("Bluetooth settings", (d, w) -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                    .setNegativeButton("OK", null)
                    .show();
            return;
        }

        List<BluetoothDevice> devices = new ArrayList<>(bonded);
        String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) names[i] = safeName(devices.get(i));

        new AlertDialog.Builder(this)
                .setTitle("Select paired LG TV")
                .setItems(names, (dialog, which) -> {
                    tv = devices.get(which);
                    selected.setText("Selected: " + safeName(tv));
                    updateConnectLabel();
                })
                .show();
    }

    private void connectOrDisconnect() {
        if (hid == null || !registered) {
            Toast.makeText(this, "Register the Bluetooth keyboard first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (tv == null) {
            choosePairedDevice();
            return;
        }
        try {
            if (connected) hid.disconnect(tv); else hid.connect(tv);
        } catch (SecurityException e) {
            requestBluetoothPermissions();
        }
    }

    private void updateConnectLabel() {
        if (connectButton != null) connectButton.setText(connected ? "Disconnect" : "Connect to TV");
    }

    private boolean ready() {
        if (hid == null || tv == null || !connected) {
            Toast.makeText(this, "Connect the TV first.", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void sendKey(int keyCode) {
        if (!ready()) return;
        byte[] press = new byte[]{0, 0, (byte) keyCode, 0, 0, 0, 0, 0};
        byte[] release = new byte[8];
        try {
            hid.sendReport(tv, REPORT_KEYBOARD, press);
            handler.postDelayed(() -> {
                try {
                    if (hid != null && tv != null) hid.sendReport(tv, REPORT_KEYBOARD, release);
                } catch (Exception ignored) {}
            }, 45);
        } catch (Exception e) {
            Toast.makeText(this, "Send failed: " + e.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateDiagnostics() {
        if (diagnostics == null) return;
        boolean btOn = adapter != null && adapter.isEnabled();
        boolean permission = hasBtConnectPermission();
        int importance = -1;
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) importance = am.getUidImportance(android.os.Process.myUid());
        } catch (Throwable ignored) {}

        String text = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + "\nBluetooth: " + (btOn ? "ON" : "OFF")
                + " • Nearby permission: " + (permission ? "granted" : "missing")
                + "\nHID proxy: " + (hidProxyReady ? "ready" : "not ready")
                + " • Window focus: " + (windowFocused ? "yes" : "no")
                + "\nUID importance: " + importance
                + " • Registered: " + (registered ? "yes" : "no")
                + " • Connected: " + (connected ? "yes" : "no");
        diagnostics.setText(text);
    }

    private void buildUi() {
        getWindow().setStatusBarColor(Color.rgb(18, 18, 18));
        getWindow().setNavigationBarColor(Color.rgb(18, 18, 18));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(30));
        root.setBackgroundColor(Color.rgb(18, 18, 18));
        scroll.addView(root);

        root.addView(text("LG Bluetooth Remote V2", 25, true));
        TextView subtitle = text("Minimal HID test first — no Wi‑Fi, hotspot or mobile data", 14, false);
        subtitle.setTextColor(Color.rgb(175, 175, 175));
        root.addView(subtitle, lpMatchWrap(0, dp(4), 0, dp(16)));

        status = text("Preparing Bluetooth…", 14, false);
        status.setTextColor(Color.rgb(230, 230, 230));
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackgroundColor(Color.rgb(38, 38, 38));
        root.addView(status, lpMatchWrap(0, 0, 0, dp(10)));

        diagnostics = text("Diagnostics loading…", 12, false);
        diagnostics.setTextColor(Color.rgb(165, 165, 165));
        diagnostics.setPadding(dp(12), dp(10), dp(12), dp(10));
        diagnostics.setBackgroundColor(Color.rgb(27, 27, 27));
        root.addView(diagnostics, lpMatchWrap(0, 0, 0, dp(14)));

        registerButton = button("1. Register Bluetooth keyboard");
        registerButton.setEnabled(false);
        registerButton.setOnClickListener(v -> registerKeyboard());
        root.addView(registerButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        Button discover = button("2. Make phone discoverable");
        discover.setOnClickListener(v -> makeDiscoverable());
        root.addView(discover, lpMatchFixed(dp(56), 0, dp(8), 0, 0));

        LinearLayout pairRow = new LinearLayout(this);
        pairRow.setOrientation(LinearLayout.HORIZONTAL);
        Button choose = button("3. Select TV");
        choose.setOnClickListener(v -> choosePairedDevice());
        connectButton = button("4. Connect to TV");
        connectButton.setOnClickListener(v -> connectOrDisconnect());
        addWeighted(pairRow, choose, 0);
        addWeighted(pairRow, connectButton, dp(8));
        root.addView(pairRow, lpMatchWrap(0, dp(8), 0, 0));

        selected = text("Selected: none", 13, false);
        selected.setTextColor(Color.rgb(165, 165, 165));
        root.addView(selected, lpMatchWrap(0, dp(8), 0, dp(18)));

        TextView navLabel = text("BASIC TV NAVIGATION", 12, true);
        navLabel.setTextColor(Color.rgb(150, 150, 150));
        root.addView(navLabel, lpMatchWrap(0, 0, 0, dp(8)));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        Button up = button("▲"); up.setOnClickListener(v -> sendKey(0x52));
        Button down = button("▼"); down.setOnClickListener(v -> sendKey(0x51));
        Button left = button("◀"); left.setOnClickListener(v -> sendKey(0x50));
        Button right = button("▶"); right.setOnClickListener(v -> sendKey(0x4F));
        Button ok = button("OK"); ok.setOnClickListener(v -> sendKey(0x28));

        LinearLayout.LayoutParams center = new LinearLayout.LayoutParams(dp(88), dp(58));
        center.gravity = Gravity.CENTER_HORIZONTAL;
        nav.addView(up, center);

        LinearLayout mid = new LinearLayout(this);
        mid.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams side = new LinearLayout.LayoutParams(dp(88), dp(58));
        mid.addView(left, side);
        LinearLayout.LayoutParams centerOk = new LinearLayout.LayoutParams(dp(88), dp(58));
        centerOk.setMargins(dp(8), 0, dp(8), 0);
        mid.addView(ok, centerOk);
        mid.addView(right, side);
        nav.addView(mid);
        nav.addView(down, center);
        root.addView(nav);

        LinearLayout quick = new LinearLayout(this);
        Button back = button("Back / Esc"); back.setOnClickListener(v -> sendKey(0x29));
        Button home = button("Home key*"); home.setOnClickListener(v -> sendKey(0x4A));
        addWeighted(quick, back, 0);
        addWeighted(quick, home, dp(8));
        root.addView(quick, lpMatchWrap(0, dp(10), 0, 0));

        TextView note = text(
                "V2 intentionally registers only a standard Bluetooth keyboard. If registration succeeds on your phone, we can add volume/media/touchpad back in the next build. If Android still rejects registerApp() here, direct phone→TV Bluetooth HID is blocked by the phone/ROM and retrying the old combined descriptor will not help.",
                12, false);
        note.setTextColor(Color.rgb(140, 140, 140));
        root.addView(note, lpMatchWrap(0, dp(18), 0, 0));

        setContentView(scroll);
    }

    private void addWeighted(LinearLayout row, Button b, int leftMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(54), 1);
        p.setMargins(leftMargin, 0, 0, 0);
        row.addView(b, p);
    }

    private LinearLayout.LayoutParams lpMatchWrap(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    private LinearLayout.LayoutParams lpMatchFixed(int height, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        p.setMargins(l, t, r, b);
        return p;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextColor(Color.WHITE);
        v.setTextSize(sp);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.rgb(47, 47, 47));
        return b;
    }

    private void setStatus(String s) {
        runOnUiThread(() -> {
            if (status != null) status.setText(s);
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (hid != null && registered) hid.unregisterApp();
            if (adapter != null && hid != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
        } catch (Exception ignored) {}
    }
}
