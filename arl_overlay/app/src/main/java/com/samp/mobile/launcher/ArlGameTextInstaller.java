package com.samp.mobile.launcher;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Makes the APK's bundled GTA text available to the native file hook. */
public final class ArlGameTextInstaller {
    private static final String[] LANGUAGES = {
            "american", "french", "german", "italian", "japanese", "russian", "spanish"
    };

    private ArlGameTextInstaller() {}

    public static void prepare(Context context) throws IOException {
        File root = context.getExternalFilesDir(null);
        if (root == null) throw new IOException("Armazenamento do jogo indisponível");
        File textDir = new File(root, "Text");
        if (!textDir.isDirectory() && !textDir.mkdirs()) {
            throw new IOException("Não foi possível preparar a pasta Text");
        }
        for (String language : LANGUAGES) {
            String name = language + ".gxt";
            File target = new File(textDir, name);
            if (target.isFile() && target.length() > 0L) continue;
            File pending = new File(textDir, name + ".arl-part");
            try (InputStream input = context.getAssets().open("Text/" + name);
                 FileOutputStream output = new FileOutputStream(pending)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.getFD().sync();
            } catch (IOException error) {
                pending.delete();
                throw error;
            }
            if (pending.length() == 0L || !pending.renameTo(target)) {
                pending.delete();
                throw new IOException("Falha ao instalar " + name);
            }
        }
    }
}
