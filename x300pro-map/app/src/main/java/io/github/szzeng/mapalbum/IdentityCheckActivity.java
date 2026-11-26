package io.github.szzeng.mapalbum;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.util.Size;
import android.widget.*;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicitly selected documents only. This diagnostic never enables the production data path. */
public final class IdentityCheckActivity extends Activity {
    private static final Uri VIVO = Uri.parse("content://com.android.gallery3d.dataprovider/mediaextra");
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Uri> selected = new ArrayList<>();
    private TextView detail;
    private ImageView image;
    private Button open;
    private Bitmap bitmap;
    private CancellationSignal pending;
    private int index, generation;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(24, 24, 24, 24); root.setOrientation(LinearLayout.VERTICAL);
        TextView instructions = new TextView(this);
        instructions.setText("身份核验（仅所选照片，数据不保存）\n请选择 30～40 张主卷照片，逐张在原生相册核对照片、位置和时间。无 GPS、不同来源和不同城市也要覆盖。结果不能自动证明全图库安全。");
        root.addView(instructions);
        Button pick = new Button(this); pick.setText("选择核验照片"); root.addView(pick);
        image = new ImageView(this); image.setAdjustViewBounds(true);
        root.addView(image, new LinearLayout.LayoutParams(-1, 300));
        detail = new TextView(this); root.addView(detail);
        LinearLayout navigation = new LinearLayout(this);
        Button previous = new Button(this); previous.setText("上一张"); navigation.addView(previous);
        Button next = new Button(this); next.setText("下一张"); navigation.addView(next);
        root.addView(navigation);
        open = new Button(this); open.setText("打开系统图片查看器"); open.setEnabled(false); root.addView(open);
        ScrollView scroll = new ScrollView(this); scroll.addView(root); setContentView(scroll);
        pick.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try { startActivityForResult(intent, 201); }
            catch (Exception e) { detail.setText("无法打开系统文件选择器"); }
        });
        previous.setOnClickListener(v -> { if (index > 0) { index--; load(); } });
        next.setOnClickListener(v -> { if (index + 1 < selected.size()) { index++; load(); } });
        open.setOnClickListener(v -> {
            if (selected.isEmpty()) return;
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(selected.get(index), "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(intent); }
            catch (Exception e) { Toast.makeText(this, "没有可用的图片查看器", Toast.LENGTH_LONG).show(); }
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 201 || result != RESULT_OK || data == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty() || uris.size() > 40) { detail.setText("请选择 1～40 张照片；完整核验至少需要 30 张。"); return; }
        selected.clear(); selected.addAll(uris); index = 0; load();
    }

    private void load() {
        if (pending != null) pending.cancel();
        CancellationSignal signal = new CancellationSignal(); pending = signal;
        int ticket = ++generation; Uri document = selected.get(index); int number = index + 1, total = selected.size();
        image.setImageDrawable(null); if (bitmap != null) { bitmap.recycle(); bitmap = null; }
        detail.setText("正在读取 " + number + "/" + total); open.setEnabled(false);
        worker.execute(() -> {
            Bitmap thumb = null; String message;
            try {
                Set<String> volumes = MediaStore.getExternalVolumeNames(this);
                if (volumes.size() != 1 || !volumes.contains(MediaStore.VOLUME_EXTERNAL_PRIMARY)) throw new IllegalStateException("检测到非主卷，已停止");
                Uri media = MediaStore.getMediaUri(this, document);
                if (media == null || !"media".equals(media.getAuthority())) throw new IllegalStateException("无法转换为媒体 URI");
                long id = ContentUris.parseId(media);
                if (id < 0) throw new IllegalStateException("无效媒体 ID");
                String date, mime;
                try (Cursor c = getContentResolver().query(media, new String[]{"_id", "volume_name", "datetaken", "mime_type"}, null, null, null, signal)) {
                    if (c == null || !c.moveToFirst() || c.getLong(0) != id || !MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(c.getString(1))) throw new IllegalStateException("媒体 ID 或主卷无法核实");
                    date = c.isNull(2) || c.getLong(2) <= 0 ? "未知" : java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(c.getLong(2)));
                    mime = c.isNull(3) ? "未知" : c.getString(3);
                    if (c.moveToNext()) throw new IllegalStateException("媒体记录重复");
                }
                String gps;
                try (Cursor c = getContentResolver().query(VIVO, new String[]{"_id", "latitude", "longitude"}, "_id=?", new String[]{Long.toString(id)}, null, signal)) {
                    if (c == null) throw new IllegalStateException("图库 Provider 不可用");
                    if (!c.moveToFirst()) gps = "Provider 无对应记录（不能认定无 GPS）";
                    else {
                        if (c.getLong(0) != id) throw new IllegalStateException("Provider ID 不匹配");
                        if (c.isNull(1) || c.isNull(2)) gps = "坐标为空";
                        else {
                            double lat = c.getDouble(1), lon = c.getDouble(2);
                            gps = !Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180 || (lat == 0 && lon == 0)
                                ? "坐标无效或为零" : "原始纬度 " + lat + "\n原始经度 " + lon + "\n坐标系尚未普遍验证";
                        }
                        if (c.moveToNext()) throw new IllegalStateException("Provider ID 重复");
                    }
                }
                thumb = getContentResolver().loadThumbnail(media, new Size(256, 256), signal);
                signal.throwIfCanceled();
                message = number + "/" + total + " · 主卷 ID " + id + "\n类型 " + mime + "\n拍摄时间 " + date + "\n" + gps + "\n请与原生相册中同一照片的详情人工对照。";
            } catch (Exception e) { message = number + "/" + total + " · 无法核验：" + e.getClass().getSimpleName() + " " + (e.getMessage() == null ? "" : e.getMessage()); }
            final Bitmap ready = thumb; final String text = message;
            runOnUiThread(() -> {
                if (ticket != generation || signal.isCanceled() || isFinishing() || isDestroyed()) { if (ready != null) ready.recycle(); return; }
                bitmap = ready; image.setImageBitmap(bitmap); detail.setText(text); open.setEnabled(true);
            });
        });
    }

    @Override protected void onDestroy() {
        generation++; if (pending != null) pending.cancel(); worker.shutdownNow();
        image.setImageDrawable(null); if (bitmap != null) bitmap.recycle();
        selected.clear(); super.onDestroy();
    }
}
