package mt.vault;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM with a hardware-backed key. Files are IV || ciphertext. */
public final class VaultCrypto {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "vault_file_key";
    private static final int GCM_TAG_BITS = 128;

    private VaultCrypto() { }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return (SecretKey) ks.getKey(ALIAS, null);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    public static byte[] encrypt(byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    public static byte[] decrypt(byte[] blob) throws Exception {
        if (blob == null || blob.length < 13) throw new IllegalArgumentException("blob too small");
        byte[] iv = new byte[12];
        System.arraycopy(blob, 0, iv, 0, 12);
        byte[] ct = new byte[blob.length - 12];
        System.arraycopy(blob, 12, ct, 0, ct.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return c.doFinal(ct);
    }

    public static void writeEncrypted(File out, byte[] plain) throws Exception {
        byte[] blob = encrypt(plain);
        FileOutputStream fos = new FileOutputStream(out);
        try {
            fos.write(blob);
            fos.getFD().sync();
        } finally {
            fos.close();
        }
    }

    public static byte[] readDecrypted(File in) throws Exception {
        FileInputStream fis = new FileInputStream(in);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            fis.close();
        }
        return decrypt(bos.toByteArray());
    }
}
