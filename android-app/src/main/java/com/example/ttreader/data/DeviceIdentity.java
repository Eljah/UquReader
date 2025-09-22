package com.example.ttreader.data;

import android.os.Build;
import android.text.TextUtils;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * Extracts a stable identity for input devices that trigger media events.
 */
public final class DeviceIdentity {
    private static final DeviceIdentity UNKNOWN = new DeviceIdentity(-1, "", "", 0, 0, 0, false, false);

    public final int deviceId;
    public final String descriptor;
    public final String displayName;
    public final int vendorId;
    public final int productId;
    public final int sourceFlags;
    public final boolean external;
    public final boolean bluetoothLikely;

    private DeviceIdentity(int deviceId, String descriptor, String displayName,
                           int vendorId, int productId, int sourceFlags,
                           boolean external, boolean bluetoothLikely) {
        this.deviceId = deviceId;
        this.descriptor = descriptor == null ? "" : descriptor;
        this.displayName = displayName == null ? "" : displayName;
        this.vendorId = vendorId;
        this.productId = productId;
        this.sourceFlags = sourceFlags;
        this.external = external;
        this.bluetoothLikely = bluetoothLikely;
    }

    public static DeviceIdentity from(KeyEvent event) {
        if (event == null) {
            return UNKNOWN;
        }
        int deviceId = event.getDeviceId();
        InputDevice device = event.getDevice();
        if (device == null && deviceId != InputDevice.SOURCE_ANY) {
            device = InputDevice.getDevice(deviceId);
        }
        if (device == null) {
            return new DeviceIdentity(deviceId, "", "", 0, 0, 0, false, false);
        }
        int sources = device.getSources();
        boolean external = isExternal(device);
        boolean bluetooth = (sources & InputDevice.SOURCE_BLUETOOTH_STYLUS) != 0
                || (sources & InputDevice.SOURCE_CLASS_JOYSTICK) != 0
                || (sources & InputDevice.SOURCE_CLASS_BUTTON) != 0;
        String descriptor = device.getDescriptor();
        String name = device.getName();
        if (!bluetooth) {
            bluetooth = (!TextUtils.isEmpty(descriptor) && descriptor.toLowerCase().contains("bluetooth"))
                    || (!TextUtils.isEmpty(name) && name.toLowerCase().contains("bluetooth"));
        }
        return new DeviceIdentity(deviceId,
                descriptor,
                name,
                device.getVendorId(),
                device.getProductId(),
                sources,
                external,
                bluetooth);
    }

    public boolean isValid() {
        return deviceId != -1 || !TextUtils.isEmpty(descriptor) || !TextUtils.isEmpty(displayName);
    }

    public String stableKey() {
        if (!TextUtils.isEmpty(descriptor)) {
            return descriptor;
        }
        if (!TextUtils.isEmpty(displayName)) {
            return displayName + "#" + vendorId + ":" + productId;
        }
        return "device:" + deviceId;
    }

    public boolean shouldTrack() {
        return external || bluetoothLikely;
    }

    private static boolean isExternal(InputDevice device) {
        if (device == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                // InputDevice#isExternal was added in API 29. Use reflection so that the
                // code still compiles against older SDKs that do not have the symbol.
                java.lang.reflect.Method method = InputDevice.class.getMethod("isExternal");
                Object value = method.invoke(device);
                if (value instanceof Boolean && (Boolean) value) {
                    return true;
                }
            } catch (NoSuchMethodException ignored) {
                // Method not present on this platform level.
            } catch (Exception ignored) {
                // Invocation failed, fall back to heuristics below.
            }
        }
        int sources = device.getSources();
        if ((sources & InputDevice.SOURCE_CLASS_BUTTON) != 0
                || (sources & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            return true;
        }
        if ((sources & InputDevice.SOURCE_KEYBOARD) != 0
                && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            return true;
        }
        return device.getVendorId() != 0 || device.getProductId() != 0;
    }
}
