package com.samp.mobile.launcher;

import android.content.Context;

import java.io.File;

/**
 * Strong validator for a real GTA San Andreas Android data tree.
 *
 * ARL overlays may contain folders such as data/, models/ or SAMP/, so folder
 * existence alone is not enough to decide that the Rockstar base is present.
 * We require independent markers from the mobile texture databases, audio and
 * game data before the bootstrap is allowed to continue.
 */
public final class ArlGtaBaseValidator {
    private ArlGtaBaseValidator() {}

    public static boolean isValid(Context context) {
        if (context == null) return false;
        return isValid(context.getExternalFilesDir(null));
    }

    public static boolean isValid(File root) {
        if (root == null || !root.isDirectory()) return false;

        boolean gta3 = anyFile(root,
                "texdb/gta3/gta3.etc",
                "texdb/gta3/gta3.pvr",
                "texdb/gta3/gta3.dxt");

        boolean interiors = anyFile(root,
                "texdb/gta_int/gta_int.etc",
                "texdb/gta_int/gta_int.pvr",
                "texdb/gta_int/gta_int.dxt");

        boolean audio = anyFile(root,
                "audio/SFX/FEET",
                "audio/SFX/GENRL",
                "audio/STREAMS/AA",
                "audio/STREAMS/CH");

        boolean gameData = anyFile(root,
                "data/handling.cfg",
                "data/gta.dat",
                "data/default.dat");

        return gta3 && interiors && audio && gameData;
    }

    private static boolean anyFile(File root, String... relativePaths) {
        for (String relativePath : relativePaths) {
            File file = resolveIgnoreCase(root, relativePath);
            if (file != null && file.isFile() && file.length() > 0L) return true;
        }
        return false;
    }

    private static File resolveIgnoreCase(File root, String relativePath) {
        File current = root;
        String[] parts = relativePath.replace('\\', '/').split("/");
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (current == null || !current.isDirectory()) return null;
            File[] children = current.listFiles();
            if (children == null) return null;
            File next = null;
            for (File child : children) {
                if (child.getName().equalsIgnoreCase(part)) {
                    next = child;
                    break;
                }
            }
            if (next == null) return null;
            current = next;
        }
        return current;
    }
}
