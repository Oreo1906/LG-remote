# LG Bluetooth Remote V2

This build is a **minimal Bluetooth HID keyboard diagnostic** for Android 9+.

Why V2 exists: the first build registered a combined keyboard + media + mouse HID descriptor immediately from the profile service callback. Some Android builds reject `BluetoothHidDevice.registerApp()` in that timing/descriptor combination.

V2 changes:

- user presses **Register Bluetooth keyboard** while the Activity is visibly foreground;
- waits ~900 ms after the button press before registering;
- uses `BluetoothHidDevice.SUBCLASS1_KEYBOARD`;
- uses a minimal standard 8-byte keyboard HID descriptor only;
- shows useful diagnostics (Bluetooth state, permission, HID proxy state, window focus, UID importance);
- keeps only arrows, OK/Enter, Back/Esc and Home-key testing.

If V2 registers successfully, pair it from the LG TV as a Bluetooth keyboard. If V2 still reports that Android rejected `registerApp()`, direct Android-as-HID mode is likely unavailable/blocked on that phone/ROM. A practical fallback is a two-phone bridge: one phone stays on the same Wi-Fi as the TV and exposes Bluetooth control to the second phone.
