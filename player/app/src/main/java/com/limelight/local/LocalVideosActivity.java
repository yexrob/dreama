package com.limelight.local;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * depth3d's front door: every video MediaStore knows about, newest first.
 * Picking one starts {@link LocalPlayerXR}, which plays it on a virtual screen
 * with the headset-side 2D to 3D conversion on.
 */
public class LocalVideosActivity extends Activity {
    private static final int PERMISSION_REQUEST = 1;

    private static final class Entry {
        final Uri uri;
        final String label;
        final Class<?> target;  // a home entry rather than a video

        Entry(Uri uri, String label) {
            this(uri, label, null);
        }

        Entry(Uri uri, String label, Class<?> target) {
            this.uri = uri;
            this.label = label;
            this.target = target;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private ListView list;
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        list = new ListView(this);
        empty = new TextView(this);
        empty.setPadding(48, 48, 48, 48);
        empty.setTextSize(20);
        list.setEmptyView(empty);
        ViewGroup root = new android.widget.FrameLayout(this);
        root.addView(list);
        root.addView(empty);
        setContentView(root);
        setTitle("depth3d");

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Entry e = (Entry) parent.getItemAtPosition(position);
                if (e.target != null) {
                    startActivity(new Intent(LocalVideosActivity.this, e.target));
                    return;
                }
                Intent play = new Intent(LocalVideosActivity.this, LocalPlayerXR.class);
                play.setData(e.uri);
                play.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(play);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    PERMISSION_REQUEST);
        }
        load();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == PERMISSION_REQUEST && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            load();
        }
    }

    private void load() {
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(null, "🖥  Mac 串流（Moonlight）\n串流电脑桌面或游戏，同样实时转 3D", com.limelight.PcView.class));
        entries.add(new Entry(null, "☁  Google Drive\n经 Mac 上的 rclone（scripts/serve-gdrive.sh）", RemoteBrowserActivity.class));
        String[] cols = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.RELATIVE_PATH,
        };
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, entries));
            return;
        }
        try (Cursor c = getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                cols, null, null, MediaStore.Video.Media.DATE_MODIFIED + " DESC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String name = c.getString(1);
                    long ms = c.getLong(2);
                    int w = c.getInt(3), h = c.getInt(4);
                    String dir = c.getString(5);
                    String label = String.format(Locale.US, "%s\n%s  ·  %d×%d  ·  %d:%02d",
                            name, dir != null ? dir : "", w, h, ms / 60000, (ms / 1000) % 60);
                    entries.add(new Entry(ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id), label));
                }
            }
        }
        
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, entries));
    }
}
