package mt.vault;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_IMPORT = 101;

    private static final int BG = Color.parseColor("#0B0B0F");
    private static final int CARD = Color.parseColor("#141419");
    private static final int LINE = Color.parseColor("#26262E");
    private static final int TEXT = Color.parseColor("#EDEDF2");
    private static final int MUTED = Color.parseColor("#8B8B98");
    private static final int ACCENT = Color.parseColor("#9B8CFF");
    private static final int DANGER = Color.parseColor("#FF7A7A");

    private PinManager pins;
    private VaultStore store;
    private SharedPreferences ui;
    private ExecutorService io;
    private Handler main;
    private LruCache<String, Bitmap> thumbs;

    private FrameLayout root;
    private LinearLayout setupView, lockView, galleryView;
    private TextView lockError, lockDots, galleryCount, emptyText;
    private GridView grid;
    private Button addBtn, lockBtn;
    private View moveRow;

    private final StringBuilder pinBuffer = new StringBuilder();
    private boolean decoyMode = false;
    private GalleryAdapter adapter;
    private boolean lockedOut = false;
    private boolean pickerOpen = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        pins = new PinManager(this);
        store = new VaultStore(this);
        ui = getSharedPreferences("vault_ui", MODE_PRIVATE);
        io = Executors.newFixedThreadPool(3);
        main = new Handler(Looper.getMainLooper());
        thumbs = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
            @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
        };

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);

        buildSetup();
        buildLock();
        buildGallery();

        if (!pins.hasPin()) {
            showOnly(setupView);
        } else {
            showLock();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        /* never leave the gallery on screen when the app goes to the background */
        if (!pickerOpen && pins.hasPin() && galleryView != null && galleryView.getVisibility() == View.VISIBLE) {
            decoyMode = false;
            pinBuffer.setLength(0);
            showLock();
        }
    }

    /* ---------------- shared styles ---------------- */

    private int dp(int v) { return Math.round(getResources().getDisplayMetrics().density * v); }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), LINE);
        box.setBackground(bg);
        return box;
    }

    private Button primary(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(Color.parseColor("#12121A"));
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT);
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        return b;
    }

    private Button ghost(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(TEXT);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1B1B22"));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), LINE);
        b.setBackground(bg);
        b.setPadding(dp(14), dp(11), dp(14), dp(11));
        return b;
    }

    private void showOnly(View v) {
        setupView.setVisibility(v == setupView ? View.VISIBLE : View.GONE);
        lockView.setVisibility(v == lockView ? View.VISIBLE : View.GONE);
        galleryView.setVisibility(v == galleryView ? View.VISIBLE : View.GONE);
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    /* ---------------- setup ---------------- */

    private void buildSetup() {
        setupView = new LinearLayout(this);
        setupView.setOrientation(LinearLayout.VERTICAL);
        setupView.setGravity(Gravity.CENTER);
        setupView.setPadding(dp(22), dp(30), dp(22), dp(30));

        LinearLayout box = card();
        box.addView(label("Set up your vault", 24, TEXT, true));
        TextView sub = label("Two PINs. The real one shows your photos. The decoy opens an empty vault.", 13, MUTED, false);
        sub.setPadding(0, dp(8), 0, dp(18));
        box.addView(sub);

        box.addView(label("REAL PIN (4-8 digits)", 10, MUTED, true));
        final EditText real = pinField("");
        box.addView(real);

        TextView dl = label("DECOY PIN — opens nothing", 10, MUTED, true);
        dl.setPadding(0, dp(14), 0, 0);
        box.addView(dl);
        final EditText decoy = pinField("2580");
        box.addView(decoy);

        TextView warn = label("Photos are encrypted into this app and their originals are removed from the gallery. " +
                "They come back with \"Move back to gallery\". If you uninstall the app or forget both PINs, " +
                "the photos cannot be recovered.", 11, Color.parseColor("#B9A06B"), false);
        warn.setPadding(0, dp(16), 0, dp(6));
        box.addView(warn);

        Button save = primary("Save PINs");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(12);
        save.setLayoutParams(slp);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String r = real.getText().toString().trim();
                String d = decoy.getText().toString().trim();
                if (r.length() < 4 || r.length() > 8) { toast("Real PIN must be 4-8 digits"); return; }
                if (d.length() < 4 || d.length() > 8) { toast("Decoy PIN must be 4-8 digits"); return; }
                if (r.equals(d)) { toast("The two PINs must be different"); return; }
                try {
                    pins.setPins(r, d);
                    toast("Saved. Remember the real PIN.");
                    showLock();
                } catch (Exception e) {
                    toast("Could not save: " + e.getMessage());
                }
            }
        });
        box.addView(save);

        setupView.addView(box);
        root.addView(setupView);
    }

    private EditText pinField(String initial) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setTextColor(TEXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        e.setLetterSpacing(0.35f);
        e.setText(initial);
        e.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#0F0F14"));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), LINE);
        e.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        e.setLayoutParams(lp);
        return e;
    }

    /* ---------------- lock ---------------- */

    private void buildLock() {
        lockView = new LinearLayout(this);
        lockView.setOrientation(LinearLayout.VERTICAL);
        lockView.setGravity(Gravity.CENTER);
        lockView.setPadding(dp(24), dp(30), dp(24), dp(30));

        LinearLayout box = card();
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = label("Vault", 22, TEXT, true);
        box.addView(title);

        lockDots = label("", 26, ACCENT, false);
        lockDots.setPadding(0, dp(14), 0, dp(4));
        lockDots.setLetterSpacing(0.4f);
        box.addView(lockDots);

        lockError = label(" ", 12, DANGER, false);
        lockError.setPadding(0, dp(2), 0, dp(10));
        box.addView(lockError);

        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        String[][] keys = {
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {"\u232B", "0", "\u2713"}
        };
        for (final String[] rowKeys : keys) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (final String k : rowKeys) {
                Button kb = new Button(this);
                kb.setText(k);
                kb.setTextSize(TypedValue.COMPLEX_UNIT_SP, k.equals("\u2713") ? 20 : 22);
                kb.setTextColor(k.equals("\u2713") ? Color.parseColor("#12121A") : TEXT);
                kb.setTypeface(Typeface.DEFAULT_BOLD);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(k.equals("\u2713") ? ACCENT : Color.parseColor("#1A1A21"));
                bg.setCornerRadius(dp(14));
                bg.setStroke(dp(1), LINE);
                kb.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(72), dp(58));
                lp.setMargins(dp(5), dp(5), dp(5), dp(5));
                kb.setLayoutParams(lp);
                kb.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { onKey(k); }
                });
                row.addView(kb);
            }
            pad.addView(row);
        }
        box.addView(pad);

        lockView.addView(box);
        root.addView(lockView);
    }

    private void onKey(String k) {
        if (lockedOut) { toast("Too many tries. Wait a moment."); return; }
        if (k.equals("\u232B")) {
            if (pinBuffer.length() > 0) pinBuffer.setLength(pinBuffer.length() - 1);
            drawDots();
            return;
        }
        if (k.equals("\u2713")) { submitPin(); return; }
        if (pinBuffer.length() >= 8) return;
        pinBuffer.append(k);
        drawDots();
        if (pinBuffer.length() == 8) submitPin();

    }

    private void drawDots() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pinBuffer.length(); i++) sb.append("\u25CF ");
        lockDots.setText(sb.length() == 0 ? "\u25CB \u25CB \u25CB \u25CB" : sb.toString());
    }

    private void showLock() {
        pinBuffer.setLength(0);
        lockError.setText(" ");
        decoyMode = false;
        drawDots();
        long left = pins.lockRemainingMs();
        lockedOut = left > 0;
        if (lockedOut) lockError.setText("Locked for " + (left / 1000 + 1) + "s");
        showOnly(lockView);
    }

    private void submitPin() {
        final String pin = pinBuffer.toString();
        if (pin.length() < 4) { lockError.setText("At least 4 digits"); return; }
        final int kind = pins.check(pin);
        pinBuffer.setLength(0);
        drawDots();

        if (kind == PinManager.WRONG) {
            long lockUntil = pins.registerFailure();
            if (lockUntil > 0) {
                lockedOut = true;
                lockError.setText("Locked for 30s after 5 wrong tries");
                main.postDelayed(new Runnable() {
                    public void run() { lockedOut = false; lockError.setText(" "); }
                }, 30000L);
            } else {
                lockError.setText("Wrong PIN");
            }
            return;
        }

        pins.clearFailures();
        lockError.setText(" ");
        decoyMode = (kind == PinManager.DECOY);
        openGallery();
    }

    /* ---------------- gallery ---------------- */

    private void buildGallery() {
        galleryView = new LinearLayout(this);
        galleryView.setOrientation(LinearLayout.VERTICAL);
        galleryView.setPadding(dp(18), dp(22), dp(18), dp(12));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(label("Vault", 22, TEXT, true));
        galleryCount = label("0 photos", 12, MUTED, false);
        titles.addView(galleryCount);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button lockNow = ghost("Lock");
        lockNow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showLock(); }
        });
        header.addView(lockNow);
        galleryView.addView(header);

        addBtn = primary("Add photos");
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(16);
        addBtn.setLayoutParams(alp);
        addBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickPhotos(); }
        });
        galleryView.addView(addBtn);

        emptyText = label("No photos.", 14, MUTED, false);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(0, dp(60), 0, 0);
        galleryView.addView(emptyText);

        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setVerticalSpacing(dp(6));
        grid.setHorizontalSpacing(dp(6));
        grid.setPadding(0, dp(14), 0, 0);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        grid.setLayoutParams(glp);
        adapter = new GalleryAdapter();
        grid.setAdapter(adapter);
        galleryView.addView(grid);

        root.addView(galleryView);
    }

    private void openGallery() {
        refreshGallery();
        addBtn.setVisibility(decoyMode ? View.GONE : View.VISIBLE);
        showOnly(galleryView);
    }

    private void refreshGallery() {
        List<VaultStore.Item> items = decoyMode ? new ArrayList<VaultStore.Item>() : store.items();
        adapter.setData(items);
        galleryCount.setText(items.size() + (items.size() == 1 ? " photo" : " photos"));
        boolean empty = items.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            emptyText.setText(decoyMode
                    ? "No photos."
                    : "No photos yet.\nTap \"Add photos\" and pick what you want hidden.");
        }
        grid.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void pickPhotos() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        pickerOpen = true;
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            toast("No file picker available");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_IMPORT) pickerOpen = false;
        if (req != REQ_IMPORT || res != RESULT_OK || data == null) {
            refreshGallery();
            return;
        }

        final List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            int n = data.getClipData().getItemCount();
            for (int i = 0; i < n; i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;

        for (Uri u : uris) {
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) { }
        }

        addBtn.setEnabled(false);
        addBtn.setText("Working…");
        io.execute(new Runnable() {
            public void run() {
                int ok = 0, kept = 0, failed = 0;
                for (Uri u : uris) {
                    VaultStore.Result r = store.importDocument(MainActivity.this, u);
                    if (r.ok) {
                        ok++;
                        if (r.message != null && r.message.startsWith("Copied in")) kept++;
                    } else {
                        failed++;
                    }
                }
                final String msg = ok + " moved in" + (kept > 0 ? ", " + kept + " needed delete permission" : "")
                        + (failed > 0 ? ", " + failed + " failed" : "");
                main.post(new Runnable() {
                    public void run() {
                        addBtn.setEnabled(true);
                        addBtn.setText("Add photos");
                        refreshGallery();
                        toast(msg);
                    }
                });
            }
        });
    }

    /* ---------------- grid adapter ---------------- */

    private class GalleryAdapter extends BaseAdapter {
        private List<VaultStore.Item> data = new ArrayList<>();

        void setData(List<VaultStore.Item> d) {
            data = d;
            notifyDataSetChanged();
        }

        public int getCount() { return data.size(); }
        public Object getItem(int i) { return data.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int pos, View convert, ViewGroup parent) {
            ImageView iv;
            if (convert instanceof ImageView) {
                iv = (ImageView) convert;
            } else {
                iv = new ImageView(MainActivity.this);
                iv.setLayoutParams(new GridView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(118)));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(Color.parseColor("#101016"));
            }
            final VaultStore.Item it = data.get(pos);
            iv.setTag(it.id);
            Bitmap cached = thumbs.get(it.id);
            if (cached != null) {
                iv.setImageBitmap(cached);
            } else {
                iv.setImageBitmap(null);
                iv.setBackgroundColor(Color.parseColor("#141419"));
                loadThumb(it, iv);
            }
            iv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showActions(it); }
            });
            return iv;
        }
    }

    private void loadThumb(final VaultStore.Item it, final ImageView iv) {
        io.execute(new Runnable() {
            public void run() {
                final Bitmap bmp = decodeThumb(it.id);
                if (bmp == null) return;
                thumbs.put(it.id, bmp);
                main.post(new Runnable() {
                    public void run() {
                        if (it.id.equals(iv.getTag())) iv.setImageBitmap(bmp);
                    }
                });
            }
        });
    }

    private Bitmap decodeThumb(String id) {
        try {
            byte[] data = VaultCrypto.readDecrypted(store.fileFor(id));
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, probe);
            int scale = 1;
            while (probe.outWidth / (scale * 2) >= 360) scale *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = scale;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable t) {
            return null;
        }
    }

    /* ---------------- item actions ---------------- */

    private void showActions(final VaultStore.Item it) {
        new AlertDialog.Builder(this)
                .setTitle(it.name)
                .setItems(new String[]{"Move back to gallery", "Delete permanently"}, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) moveOut(it);
                        else confirmDelete(it);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void moveOut(final VaultStore.Item it) {
        io.execute(new Runnable() {
            public void run() {
                final VaultStore.Result r = store.exportToGallery(MainActivity.this, it.id);
                thumbs.remove(it.id);
                main.post(new Runnable() {
                    public void run() {
                        refreshGallery();
                        toast(r.message);
                    }
                });
            }
        });
    }

    private void confirmDelete(final VaultStore.Item it) {
        new AlertDialog.Builder(this)
                .setTitle("Delete permanently?")
                .setMessage("This removes the only copy. It cannot be undone.")
                .setPositiveButton("Delete", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        io.execute(new Runnable() {
                            public void run() {
                                final VaultStore.Result r = store.deletePermanent(it.id);
                                thumbs.remove(it.id);
                                main.post(new Runnable() {
                                    public void run() {
                                        refreshGallery();
                                        toast(r.message);
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
