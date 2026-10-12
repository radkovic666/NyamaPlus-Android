package fun.nyama.tv;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** ID and bearer token form one encrypted, atomic, non-backed-up installation record. */
final class ActivationStore implements ActivationController.Credentials {
    private static final String ALIAS = "nyama_plus_activation_v1";
    private static final Object LOCK = new Object();
    private final Context context;
    private final AtomicFile file;

    ActivationStore(Context context) {
        this.context = context.getApplicationContext();
        file = new AtomicFile(new File(this.context.getNoBackupFilesDir(), "activation.enc"));
    }

    static boolean managed(Context context) {
        return context.getSharedPreferences("nyama_activation_mode", Context.MODE_PRIVATE).getBoolean("managed", false);
    }

    private void markManaged() throws Exception {
        if (!context.getSharedPreferences("nyama_activation_mode", Context.MODE_PRIVATE).edit()
                .putBoolean("managed", true).commit()) throw new java.io.IOException("storage_unavailable");
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        if (!create) return null;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }

    private JSONObject read() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists())
            return new JSONObject();
        SecretKey key = key(false);
        if (key == null) { file.delete(); return new JSONObject(); }
        byte[] data = file.readFully(); // IO failure must not silently discard an existing activation.
        try {
            if (data.length < 29) throw new java.security.GeneralSecurityException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, data, 0, 12));
            return new JSONObject(new String(cipher.doFinal(data, 12, data.length - 12), java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException | org.json.JSONException lostSecureData) {
            file.delete();
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            store.deleteEntry(ALIAS);
            return new JSONObject(); // Lost/unusable secure state requires email verification again.
        }
    }

    private void write(JSONObject record) throws Exception {
        markManaged(); // Persist mode first: interrupted writes can never fall back to legacy access.
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        byte[] encrypted = cipher.doFinal(record.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(cipher.getIV());
            out.write(encrypted);
            file.finishWrite(out);
        } catch (Exception error) { if (out != null) file.failWrite(out); throw error; }
    }

    @Override public String deviceId() throws Exception {
        synchronized (LOCK) {
            JSONObject record = read();
            String id = record.optString("device_id");
            if (!id.matches("[0-9a-f]{64}")) {
                id = ActivationController.newInstallationId();
                record = new JSONObject().put("device_id", id);
                write(record);
            }
            return id;
        }
    }

    @Override public String token() throws Exception {
        synchronized (LOCK) { return read().optString("activation_token"); }
    }

    @Override public void saveToken(String id, String token) throws Exception {
        synchronized (LOCK) {
            if (!token.matches("[0-9a-f]{64}") || !id.equals(deviceId())) throw new java.security.GeneralSecurityException();
            write(read().put("activation_token", token));
        }
    }

    @Override public void clearToken(String expectedToken) throws Exception {
        synchronized (LOCK) {
            JSONObject record = read();
            // A delayed rejection for an old request must not erase a newly verified credential.
            if (!expectedToken.equals(record.optString("activation_token"))) return;
            record.remove("activation_token");
            write(record); // Keep installation ID, preferences, favorites and all unrelated configuration.
        }
    }
}
