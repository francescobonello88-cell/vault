package mt.vault;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Two PINs: the real one opens the vault, the decoy opens an identical but empty view. */
public class PinManager {

    public static final int REAL = 0, DECOY = 1, WRONG = -1;

    private final SharedPreferences sp;

    public PinManager(Context c) {
        sp = c.getSharedPreferences("vault_pin", Context.MODE_PRIVATE);
    }

    public boolean hasPin() {
        return sp.contains("real_h") && sp.contains("decoy_h");
    }

    public boolean isDecoySet() {
        return sp.contains("decoy_h");
    }

    private static String hash(String pin, byte[] salt) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, 12000, 256);
        return Base64.encodeToString(f.generateSecret(spec).getEncoded(), Base64.NO_WRAP);
    }

    public void setPins(String real, String decoy) throws Exception {
        SecureRandom r = new SecureRandom();
        byte[] s1 = new byte[16], s2 = new byte[16];
        r.nextBytes(s1); r.nextBytes(s2);
        sp.edit()
          .putString("real_h", hash(real, s1))
          .putString("real_s", Base64.encodeToString(s1, Base64.NO_WRAP))
          .putString("decoy_h", hash(decoy, s2))
          .putString("decoy_s", Base64.encodeToString(s2, Base64.NO_WRAP))
          .putInt("fails", 0)
          .putLong("lock_until", 0L)
          .apply();
    }

    public void setDecoyOnly(String decoy) throws Exception {
        SecureRandom r = new SecureRandom();
        byte[] s2 = new byte[16];
        r.nextBytes(s2);
        sp.edit()
          .putString("decoy_h", hash(decoy, s2))
          .putString("decoy_s", Base64.encodeToString(s2, Base64.NO_WRAP))
          .apply();
    }

    public int check(String pin) {
        if (pin == null || pin.length() < 4) return WRONG;
        try {
            if (matches(pin, "real_h", "real_s")) return REAL;
            if (matches(pin, "decoy_h", "decoy_s")) return DECOY;
        } catch (Exception ignored) { }
        return WRONG;
    }

    private boolean matches(String pin, String hashKey, String saltKey) throws Exception {
        String stored = sp.getString(hashKey, null);
        String salt = sp.getString(saltKey, null);
        if (stored == null || salt == null) return false;
        String attempt = hash(pin, Base64.decode(salt, Base64.NO_WRAP));
        return MessageDigest.isEqual(attempt.getBytes("UTF-8"), stored.getBytes("UTF-8"));
    }

    /** Five wrong tries locks entry for 30 seconds. */
    public long registerFailure() {
        int fails = sp.getInt("fails", 0) + 1;
        long lockUntil = 0L;
        if (fails >= 5) {
            lockUntil = System.currentTimeMillis() + 30000L;
            fails = 0;
        }
        sp.edit().putInt("fails", fails).putLong("lock_until", lockUntil).apply();
        return lockUntil;
    }

    public void clearFailures() {
        sp.edit().putInt("fails", 0).putLong("lock_until", 0L).apply();
    }

    public long lockRemainingMs() {
        long until = sp.getLong("lock_until", 0L);
        long left = until - System.currentTimeMillis();
        return Math.max(0L, left);
    }
}
