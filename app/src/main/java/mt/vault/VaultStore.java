package mt.vault;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Encrypted photo store inside the app's private storage, with a JSON index. */
public class VaultStore {

    public static class Item {
        public String id, name, mime;
        public long size, addedAt;
        Item(String id, String name, String mime, long size, long addedAt) {
            this.id = id; this.name = name; this.mime = mime; this.size = size; this.addedAt = addedAt;
        }
    }

    public static class Result {
        public final boolean ok;
        public final String message;
        Result(boolean ok, String message) { this.ok = ok; this.message = message; }
    }

    private static final long MAX_BYTES = 120L * 1024L * 1024L;

    private final File dir;
    private final File indexFile;
    private final List<Item> items = new ArrayList<>();

    public VaultStore(Context c) {
        dir = new File(c.getFilesDir(), "vault");
        if (!dir.exists()) dir.mkdirs();
        indexFile = new File(dir, "index.json");
        load();
    }

    public File fileFor(String id) { return new File(dir, id + ".bin"); }

    public List<Item> items() {
        List<Item> copy;
        synchronized (items) { copy = new ArrayList<>(items); }
        Collections.sort(copy, new Comparator<Item>() {
            public int compare(Item a, Item b) { return Long.compare(b.addedAt, a.addedAt); }
        });
        return copy;
    }

    public int count() { synchronized (items) { return items.size(); } }

    private Item find(String id) {
        synchronized (items) {
            for (Item i : items) if (i.id.equals(id)) return i;
        }
        return null;
    }

    /* ---------------- import ---------------- */

    public Result importDocument(Context ctx, Uri uri) {
        ContentResolver cr = ctx.getContentResolver();
        String name = queryName(ctx, uri);
        String mime = cr.getType(uri);
        if (name == null) name = "photo_" + System.currentTimeMillis() + ".jpg";

        byte[] data;
        try {
            data = readAll(cr, uri);
        } catch (Exception e) {
            return new Result(false, "Could not read " + name);
        }
        if (data == null || data.length == 0) return new Result(false, "Empty file: " + name);
        if (data.length > MAX_BYTES) return new Result(false, "Too large (over 120 MB): " + name);

        String id = UUID.randomUUID().toString();
        File target = fileFor(id);

        try {
            VaultCrypto.writeEncrypted(target, data);
            byte[] back = VaultCrypto.readDecrypted(target);
            if (!sha256(back).equals(sha256(data))) {
                target.delete();
                return new Result(false, "Verification failed, original kept: " + name);
            }
        } catch (Exception e) {
            target.delete();
            return new Result(false, "Encryption failed, original kept: " + name);
        }

        /* encrypted copy is verified; now clear the original from the gallery */
        boolean removed = false;
        try {
            removed = DocumentsContract.deleteDocument(cr, uri);
        } catch (Exception ignored) { }

        synchronized (items) {
            items.add(new Item(id, name, mime, data.length, System.currentTimeMillis()));
        }
        save();

        return new Result(true, removed
                ? "Moved in: " + name
                : "Copied in, original kept (no delete permission): " + name);
    }

    /* ---------------- export ---------------- */

    public Result exportToGallery(Context ctx, String id) {
        Item it = find(id);
        if (it == null) return new Result(false, "Missing entry");

        byte[] data;
        try {
            data = VaultCrypto.readDecrypted(fileFor(id));
        } catch (Exception e) {
            return new Result(false, "Could not decrypt " + it.name);
        }

        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, it.name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, it.mime == null ? "image/jpeg" : it.mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Vault");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri out = ctx.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (out == null) return new Result(false, "Could not create file in the gallery");

        try {
            OutputStream os = ctx.getContentResolver().openOutputStream(out);
            if (os == null) throw new Exception("no stream");
            os.write(data);
            os.close();
        } catch (Exception e) {
            ctx.getContentResolver().delete(out, null, null);
            return new Result(false, "Write failed: " + e.getMessage());
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        ctx.getContentResolver().update(out, done, null, null);

        fileFor(id).delete();
        synchronized (items) { items.remove(it); }
        save();
        return new Result(true, "Back in gallery: " + it.name);
    }

    /* ---------------- delete ---------------- */

    public Result deletePermanent(String id) {
        Item it = find(id);
        if (it == null) return new Result(false, "Missing entry");
        fileFor(id).delete();
        synchronized (items) { items.remove(it); }
        save();
        return new Result(true, "Deleted: " + it.name);
    }

    /* ---------------- helpers ---------------- */

    private static byte[] readAll(ContentResolver cr, Uri uri) throws Exception {
        InputStream in = cr.openInputStream(uri);
        if (in == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_BYTES) throw new Exception("too large");
            }
        } finally {
            in.close();
        }
        return bos.toByteArray();
    }

    private static String queryName(Context ctx, Uri uri) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void load() {
        try {
            if (!indexFile.exists()) return;
            java.io.FileInputStream fis = new java.io.FileInputStream(indexFile);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            fis.close();
            JSONArray arr = new JSONArray(new String(bos.toByteArray(), "UTF-8"));
            synchronized (items) {
                items.clear();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    items.add(new Item(o.getString("id"), o.optString("name"),
                            o.optString("mime"), o.optLong("size"), o.optLong("addedAt")));
                }
            }
        } catch (Exception ignored) { }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (items) {
                for (Item i : items) {
                    JSONObject o = new JSONObject();
                    o.put("id", i.id);
                    o.put("name", i.name);
                    o.put("mime", i.mime);
                    o.put("size", i.size);
                    o.put("addedAt", i.addedAt);
                    arr.put(o);
                }
            }
            FileOutputStream fos = new FileOutputStream(indexFile);
            try {
                fos.write(arr.toString().getBytes("UTF-8"));
                fos.getFD().sync();
            } finally {
                fos.close();
            }
        } catch (Exception ignored) { }
    }
}
