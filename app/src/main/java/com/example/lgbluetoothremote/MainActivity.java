package com.example.lgbluetoothremote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQ_BT_PERMS = 10;
    private static final int REPORT_KEYBOARD = 1;
    private static final int REPORT_CONSUMER = 2;
    private static final int REPORT_MOUSE = 3;

    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice tv;
    private boolean registered = false;
    private boolean connected = false;

    private TextView status;
    private TextView selected;
    private Button connectButton;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Keyboard + Consumer Control + Mouse HID descriptor.
    private static final byte[] HID_DESCRIPTOR = bytes(
            // Keyboard, report 1
            0x05,0x01, 0x09,0x06, 0xA1,0x01, 0x85,0x01,
            0x05,0x07, 0x19,0xE0, 0x29,0xE7, 0x15,0x00, 0x25,0x01,
            0x75,0x01, 0x95,0x08, 0x81,0x02,
            0x95,0x01, 0x75,0x08, 0x81,0x01,
            0x95,0x06, 0x75,0x08, 0x15,0x00, 0x25,0x65,
            0x05,0x07, 0x19,0x00, 0x29,0x65, 0x81,0x00, 0xC0,

            // Consumer control, report 2 (single 16-bit usage)
            0x05,0x0C, 0x09,0x01, 0xA1,0x01, 0x85,0x02,
            0x15,0x00, 0x26,0xFF,0x03, 0x19,0x00, 0x2A,0xFF,0x03,
            0x75,0x10, 0x95,0x01, 0x81,0x00, 0xC0,

            // Mouse, report 3
            0x05,0x01, 0x09,0x02, 0xA1,0x01, 0x85,0x03,
            0x09,0x01, 0xA1,0x00,
            0x05,0x09, 0x19,0x01, 0x29,0x03, 0x15,0x00, 0x25,0x01,
            0x95,0x03, 0x75,0x01, 0x81,0x02,
            0x95,0x01, 0x75,0x05, 0x81,0x01,
            0x05,0x01, 0x09,0x30, 0x09,0x31, 0x09,0x38,
            0x15,0x81, 0x25,0x7F, 0x75,0x08, 0x95,0x03, 0x81,0x06,
            0xC0, 0xC0
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
        if (requestCode == REQ_BT_PERMS) initBluetooth();
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
            return;
        }
        if (!adapter.isEnabled()) {
            try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
            catch (Exception ignored) {}
            setStatus("Turn Bluetooth ON, then return to this app.");
        }
        boolean ok = adapter.getProfileProxy(this, serviceListener, BluetoothProfile.HID_DEVICE);
        setStatus(ok ? "Starting Bluetooth remote service…" : "This phone did not expose the HID Device profile.");
    }

    private final BluetoothProfile.ServiceListener serviceListener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.HID_DEVICE) return;
            hid = (BluetoothHidDevice) proxy;
            registerHidApp();
        }
        @Override public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hid = null; registered = false; connected = false;
                setStatus("Bluetooth HID service disconnected.");
            }
        }
    };

    private void registerHidApp() {
        if (hid == null) return;
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "LG BT Remote",
                "Keyboard, media remote and touchpad",
                "Local Remote",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                HID_DESCRIPTOR
        );
        try {
            boolean sent = hid.registerApp(sdp, null, null, getMainExecutor(), hidCallback);
            setStatus(sent ? "Registering remote. Make the phone discoverable and pair it from the TV." :
                    "Could not register Bluetooth HID on this phone.");
        } catch (SecurityException e) {
            setStatus("Bluetooth permission was denied. Allow Nearby devices permission.");
        }
    }

    private final BluetoothHidDevice.Callback hidCallback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean isRegistered) {
            registered = isRegistered;
            runOnUiThread(() -> setStatus(isRegistered ?
                    "Bluetooth remote ready. Pair/select your LG TV." :
                    "Bluetooth HID registration failed or stopped."));
        }

        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            connected = state == BluetoothProfile.STATE_CONNECTED;
            if (connected) tv = device;
            runOnUiThread(() -> {
                String name = safeName(device);
                if (state == BluetoothProfile.STATE_CONNECTED) setStatus("Connected to " + name + " ✓");
                else if (state == BluetoothProfile.STATE_CONNECTING) setStatus("Connecting to " + name + "…");
                else if (state == BluetoothProfile.STATE_DISCONNECTED) setStatus("Disconnected from " + name);
                updateConnectLabel();
            });
        }
    };

    private String safeName(BluetoothDevice d) {
        if (d == null) return "device";
        if (!hasBtConnectPermission()) return "device";
        try {
            String n = d.getName();
            return n == null || n.trim().isEmpty() ? d.getAddress() : n;
        } catch (SecurityException e) { return "device"; }
    }

    private void makeDiscoverable() {
        if (adapter == null) return;
        try {
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivity(i);
            Toast.makeText(this, "On the TV, search for a Bluetooth keyboard/controller now.", Toast.LENGTH_LONG).show();
        } catch (SecurityException e) {
            requestBluetoothPermissions();
        }
    }

    private void choosePairedDevice() {
        if (adapter == null || !hasBtConnectPermission()) {
            requestBluetoothPermissions(); return;
        }
        Set<BluetoothDevice> bonded;
        try { bonded = adapter.getBondedDevices(); }
        catch (SecurityException e) { requestBluetoothPermissions(); return; }

        if (bonded == null || bonded.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No paired devices")
                    .setMessage("First tap ‘Make phone discoverable’, then use your LG TV’s Bluetooth controller/keyboard menu to pair with this phone. After pairing, return here and tap Select TV again.")
                    .setPositiveButton("Bluetooth settings", (d,w) -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                    .setNegativeButton("OK", null).show();
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
            Toast.makeText(this, "Bluetooth remote service is not ready yet.", Toast.LENGTH_SHORT).show(); return;
        }
        if (tv == null) {
            choosePairedDevice(); return;
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
        byte[] press = new byte[]{0,0,(byte)keyCode,0,0,0,0,0};
        byte[] release = new byte[8];
        try {
            hid.sendReport(tv, REPORT_KEYBOARD, press);
            handler.postDelayed(() -> {
                try { if (hid != null && tv != null) hid.sendReport(tv, REPORT_KEYBOARD, release); }
                catch (Exception ignored) {}
            }, 35);
        } catch (Exception ignored) {}
    }

    private void sendConsumer(int usage) {
        if (!ready()) return;
        byte[] press = new byte[]{(byte)(usage & 0xff), (byte)((usage >> 8) & 0xff)};
        byte[] release = new byte[]{0,0};
        try {
            hid.sendReport(tv, REPORT_CONSUMER, press);
            handler.postDelayed(() -> {
                try { if (hid != null && tv != null) hid.sendReport(tv, REPORT_CONSUMER, release); }
                catch (Exception ignored) {}
            }, 35);
        } catch (Exception ignored) {}
    }

    private void sendMouse(int buttons, int dx, int dy, int wheel) {
        if (hid == null || tv == null || !connected) return;
        dx = Math.max(-127, Math.min(127, dx));
        dy = Math.max(-127, Math.min(127, dy));
        wheel = Math.max(-127, Math.min(127, wheel));
        byte[] report = new byte[]{(byte)buttons, (byte)dx, (byte)dy, (byte)wheel};
        try { hid.sendReport(tv, REPORT_MOUSE, report); } catch (Exception ignored) {}
    }

    private void mouseClick() {
        if (!ready()) return;
        sendMouse(1,0,0,0);
        handler.postDelayed(() -> sendMouse(0,0,0,0), 35);
    }

    private void buildUi() {
        getWindow().setStatusBarColor(Color.rgb(18,18,18));
        getWindow().setNavigationBarColor(Color.rgb(18,18,18));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(30));
        root.setBackgroundColor(Color.rgb(18,18,18));
        scroll.addView(root);

        TextView title = text("LG Bluetooth Remote", 26, true);
        root.addView(title);
        TextView subtitle = text("No IR • No Wi‑Fi • No mobile data after Bluetooth pairing", 14, false);
        subtitle.setTextColor(Color.rgb(175,175,175));
        root.addView(subtitle, lpMatchWrap(0, dp(4),0,dp(16)));

        status = text("Preparing Bluetooth…", 14, false);
        status.setTextColor(Color.rgb(220,220,220));
        status.setPadding(dp(14),dp(12),dp(14),dp(12));
        status.setBackgroundColor(Color.rgb(38,38,38));
        root.addView(status, lpMatchWrap(0,0,0,dp(12)));

        LinearLayout pairingRow = new LinearLayout(this);
        pairingRow.setOrientation(LinearLayout.HORIZONTAL);
        Button discover = button("1. Make discoverable");
        discover.setOnClickListener(v -> makeDiscoverable());
        Button choose = button("2. Select TV");
        choose.setOnClickListener(v -> choosePairedDevice());
        pairingRow.addView(discover, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(52), 1); cp.setMargins(dp(8),0,0,0);
        pairingRow.addView(choose, cp);
        root.addView(pairingRow);

        selected = text("Selected: none", 13, false);
        selected.setTextColor(Color.rgb(170,170,170));
        root.addView(selected, lpMatchWrap(0,dp(8),0,dp(6)));

        connectButton = button("Connect to TV");
        connectButton.setOnClickListener(v -> connectOrDisconnect());
        root.addView(connectButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        TextView padLabel = text("TOUCHPAD / POINTER", 12, true);
        padLabel.setTextColor(Color.rgb(150,150,150));
        root.addView(padLabel, lpMatchWrap(0,dp(20),0,dp(7)));
        TouchpadView touchpad = new TouchpadView();
        root.addView(touchpad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));

        TextView navLabel = text("NAVIGATION", 12, true);
        navLabel.setTextColor(Color.rgb(150,150,150));
        root.addView(navLabel, lpMatchWrap(0,dp(20),0,dp(7)));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        Button up = button("▲"); up.setOnClickListener(v -> sendKey(0x52));
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        Button left = button("◀"); left.setOnClickListener(v -> sendKey(0x50));
        Button ok = button("OK"); ok.setOnClickListener(v -> sendKey(0x28));
        Button right = button("▶"); right.setOnClickListener(v -> sendKey(0x4F));
        Button down = button("▼"); down.setOnClickListener(v -> sendKey(0x51));
        LinearLayout.LayoutParams centerBtn = new LinearLayout.LayoutParams(dp(86),dp(58)); centerBtn.gravity = Gravity.CENTER_HORIZONTAL;
        nav.addView(up, centerBtn);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams small = new LinearLayout.LayoutParams(dp(86),dp(58));
        row.addView(left, small);
        LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(dp(86),dp(58)); mid.setMargins(dp(7),0,dp(7),0);
        row.addView(ok, mid);
        row.addView(right, small);
        nav.addView(row);
        nav.addView(down, centerBtn);
        root.addView(nav);

        LinearLayout quick = new LinearLayout(this); quick.setOrientation(LinearLayout.HORIZONTAL);
        Button back = button("Back"); back.setOnClickListener(v -> sendKey(0x29)); // ESC
        Button home = button("Home*"); home.setOnClickListener(v -> sendKey(0x4A)); // keyboard Home
        Button menu = button("Menu*"); menu.setOnClickListener(v -> sendKey(0x65)); // Application/Menu key
        addWeighted(quick, back, 0); addWeighted(quick, home, dp(7)); addWeighted(quick, menu, dp(7));
        root.addView(quick, lpMatchWrap(0,dp(10),0,0));

        TextView mediaLabel = text("TV / MEDIA", 12, true);
        mediaLabel.setTextColor(Color.rgb(150,150,150));
        root.addView(mediaLabel, lpMatchWrap(0,dp(20),0,dp(7)));

        LinearLayout vol = new LinearLayout(this); vol.setOrientation(LinearLayout.HORIZONTAL);
        Button vdown = button("Vol −"); vdown.setOnClickListener(v -> sendConsumer(0xEA));
        Button mute = button("Mute"); mute.setOnClickListener(v -> sendConsumer(0xE2));
        Button vup = button("Vol +"); vup.setOnClickListener(v -> sendConsumer(0xE9));
        addWeighted(vol,vdown,0); addWeighted(vol,mute,dp(7)); addWeighted(vol,vup,dp(7));
        root.addView(vol);

        LinearLayout media = new LinearLayout(this); media.setOrientation(LinearLayout.HORIZONTAL);
        Button prev = button("⏮"); prev.setOnClickListener(v -> sendConsumer(0xB6));
        Button play = button("⏯"); play.setOnClickListener(v -> sendConsumer(0xCD));
        Button next = button("⏭"); next.setOnClickListener(v -> sendConsumer(0xB5));
        addWeighted(media,prev,0); addWeighted(media,play,dp(7)); addWeighted(media,next,dp(7));
        root.addView(media, lpMatchWrap(0,dp(7),0,0));

        LinearLayout ch = new LinearLayout(this); ch.setOrientation(LinearLayout.HORIZONTAL);
        Button chd = button("Ch −*"); chd.setOnClickListener(v -> sendConsumer(0x9D));
        Button chu = button("Ch +*"); chu.setOnClickListener(v -> sendConsumer(0x9C));
        addWeighted(ch,chd,0); addWeighted(ch,chu,dp(7));
        root.addView(ch, lpMatchWrap(0,dp(7),0,0));

        TextView note = text("* Home/Menu/Channel mappings depend on the LG TV model. The pointer works only if the TV accepts this phone as a Bluetooth mouse. This app never requests Internet access.", 12, false);
        note.setTextColor(Color.rgb(135,135,135));
        root.addView(note, lpMatchWrap(0,dp(18),0,0));

        setContentView(scroll);
    }

    private void addWeighted(LinearLayout row, Button b, int leftMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(52), 1);
        p.setMargins(leftMargin,0,0,0); row.addView(b,p);
    }

    private LinearLayout.LayoutParams lpMatchWrap(int l,int t,int r,int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l,t,r,b); return p;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView v = new TextView(this); v.setText(s); v.setTextColor(Color.WHITE); v.setTextSize(sp);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v;
    }

    private Button button(String s) {
        Button b = new Button(this); b.setText(s); b.setTextColor(Color.WHITE); b.setTextSize(14);
        b.setAllCaps(false); b.setBackgroundColor(Color.rgb(47,47,47)); return b;
    }

    private void setStatus(String s) {
        runOnUiThread(() -> { if (status != null) status.setText(s); });
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        super.onDestroy();
        try {
            if (hid != null && registered) hid.unregisterApp();
            if (adapter != null && hid != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
        } catch (Exception ignored) {}
    }

    private class TouchpadView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float lastX, lastY, downX, downY;
        private long downTime;

        TouchpadView() { super(MainActivity.this); setBackgroundColor(Color.rgb(34,34,34)); }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            paint.setColor(Color.rgb(145,145,145)); paint.setTextSize(dp(14)); paint.setTextAlign(Paint.Align.CENTER);
            c.drawText("Drag to move pointer • tap to click", getWidth()/2f, getHeight()/2f, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = downX = e.getX(); lastY = downY = e.getY(); downTime = System.currentTimeMillis(); return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getX() - lastX, dy = e.getY() - lastY;
                    lastX = e.getX(); lastY = e.getY();
                    // Slight amplification for comfortable cursor movement.
                    sendMouse(0, Math.round(dx * 1.35f), Math.round(dy * 1.35f), 0); return true;
                case MotionEvent.ACTION_UP:
                    float dist = Math.abs(e.getX()-downX) + Math.abs(e.getY()-downY);
                    if (dist < dp(12) && System.currentTimeMillis()-downTime < 350) mouseClick();
                    return true;
            }
            return true;
        }
    }
}
