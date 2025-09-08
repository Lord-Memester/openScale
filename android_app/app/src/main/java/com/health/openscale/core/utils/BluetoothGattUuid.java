package com.health.openscale.core.utils;

import java.util.UUID;

public class BluetoothGattUuid {
    public static String prettyPrint(UUID uuid) {
        if (uuid == null) {
            return "null";
        }
        // This is a basic implementation. A more robust one might map common UUIDs to names.
        return uuid.toString();
    }
}
