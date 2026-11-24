package com.limelight.local;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One folder of the Mac's WebDAV share (Google Drive through rclone). Folders
 * open another of these, videos go to {@link LocalPlayerXR} with the login as
 * a header. With no server saved it looks for one on the Wi-Fi first.
 *
 * Extras: "path" (percent-encoded, default "/"); "base", "user", "pass" save a
 * server, so adb can set it up without typing in the headset.
 */
public class RemoteBrowserActivity extends Activity {
    private ListView list;
    private TextView status;
    private String path;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        path = in.getStringExtra("path");
        if (path == null) {
            path = "/";
        }
        if (in.getStringExtra("base") != null) {
            WebDav.save(this, new WebDav.Server(in.getStringExtra("base"),
                    in.getStringExtra("user") != null ? in.getStringExtra("user") : "depth3d",
                    in.getStringExtra("pass") != null ? in.getStringExtra("pass") : ""));
        }

        list = new ListView(this);
        status = new TextView(this);
        status.setPadding(48, 48, 48, 48);
        status.setTextSize(20);
        list.setEmptyView(status);
        FrameLayout root = new FrameLayout(this);
        root.addView(list);
        root.addView(status);
        setContentView(root);
        String shown = WebDav.decode(path);
        setTitle("Google Drive" + ("/".equals(shown) ? "" : " — " + shown));

        list.setOnItemClickListener((parent, view, position, id) -> {
            Row row = (Row) parent.getItemAtPosition(position);
            open(row.item);
        });
        refresh();
    }

    private static final class Row {
        final WebDav.Item item;

        Row(WebDav.Item item) {
            this.item = item;
        }

        @Override
        public String toString() {
            if (item.dir) {
                return "📁  " + item.name;
            }
            return item.name + (item.size > 0
                    ? String.format(Locale.US, "\n%.1f GB", item.size / 1e9) : "");
        }
    }

    private void refresh() {
        list.setAdapter(null);
        status.setText("正在连接 Mac…");
        new Thread(() -> {
            WebDav.Server s = WebDav.load(this);
            if (s == null) {
                runOnUiThread(() -> status.setText("正在局域网里找 Mac…"));
                String found = WebDav.discover(this, 4000);
                com.limelight.LimeLog.info("depth3d: webdav discovery -> " + found);
                if (found == null) {
                    runOnUiThread(() -> {
                        status.setText("没找到。确认 Mac 上在跑 scripts/serve-gdrive.sh，"
                                + "并且和头显在同一个 Wi-Fi；或者点右上角菜单手动填地址。");
                        askServer(null);
                    });
                    return;
                }
                runOnUiThread(() -> askServer(found));
                return;
            }
            try {
                List<WebDav.Item> items = WebDav.list(s, path);
                com.limelight.LimeLog.info("depth3d: webdav " + s.base + path + " -> " + items.size() + " items");
                List<Row> rows = new ArrayList<>();
                for (WebDav.Item i : items) {
                    rows.add(new Row(i));
                }
                runOnUiThread(() -> {
                    status.setText("这个文件夹里没有视频。");
                    list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows));
                });
            } catch (Exception e) {
                com.limelight.LimeLog.warning("depth3d: webdav " + s.base + path + " failed: " + e);
                runOnUiThread(() -> status.setText("连不上 " + s.base + "：" + e.getMessage()
                        + "\n\n点右上角菜单可以改地址和密码。"));
            }
        }, "depth3d-dav").start();
    }

    private void open(WebDav.Item item) {
        if (item.dir) {
            Intent next = new Intent(this, RemoteBrowserActivity.class);
            next.putExtra("path", item.href.endsWith("/") ? item.href : item.href + "/");
            startActivity(next);
            return;
        }
        WebDav.Server s = WebDav.load(this);
        if (s == null) {
            return;
        }
        Intent play = new Intent(this, LocalPlayerXR.class);
        play.setData(Uri.parse(s.base + item.href));
        play.putExtra(LocalPlayerXR.EXTRA_AUTH, s.auth());
        play.putExtra(LocalPlayerXR.EXTRA_TITLE, item.name);
        startActivity(play);
    }

    private void askServer(String suggested) {
        WebDav.Server cur = WebDav.load(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(48, 24, 48, 0);
        EditText base = field(form, "地址，如 http://192.168.1.10:8890",
                suggested != null ? suggested : cur != null ? cur.base : "http://");
        EditText user = field(form, "用户名", cur != null ? cur.user : "depth3d");
        EditText pass = field(form, "密码（Mac 上 ~/.config/depth3d/webdav-pass）",
                cur != null ? cur.pass : "");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle(suggested != null ? "找到 Mac 了，填一下密码" : "WebDAV 服务器")
                .setView(form)
                .setPositiveButton("连接", (d, w) -> {
                    WebDav.save(this, new WebDav.Server(base.getText().toString().trim(),
                            user.getText().toString().trim(), pass.getText().toString().trim()));
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private EditText field(LinearLayout form, String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        form.addView(e);
        return e;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "服务器设置");
        menu.add(0, 2, 0, "刷新");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            askServer(null);
            return true;
        }
        if (item.getItemId() == 2) {
            refresh();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
