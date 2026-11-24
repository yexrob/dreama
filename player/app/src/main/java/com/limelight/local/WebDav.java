package com.limelight.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.util.Base64;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Just enough WebDAV to browse a folder and hand a file's URL to the player:
 * one PROPFIND at depth 1 per folder. The Mac side is rclone serving Google
 * Drive (scripts/serve-gdrive.sh), which also announces itself as
 * _webdav._tcp so the headset can find it without an address typed in.
 */
final class WebDav {
    static final String PREFS = "depth3d_webdav";

    static final class Server {
        final String base;  // http://host:port, no trailing slash
        final String user;
        final String pass;

        Server(String base, String user, String pass) {
            this.base = base.replaceAll("/+$", "");
            this.user = user;
            this.pass = pass;
        }

        String auth() {
            String raw = user + ":" + pass;
            return "Basic " + Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        }
    }

    static final class Item implements Comparable<Item> {
        final String href;   // as the server sent it, percent-encoded
        final String name;
        final boolean dir;
        final long size;

        Item(String href, String name, boolean dir, long size) {
            this.href = href;
            this.name = name;
            this.dir = dir;
            this.size = size;
        }

        @Override
        public int compareTo(Item o) {
            if (dir != o.dir) {
                return dir ? -1 : 1;
            }
            return name.compareToIgnoreCase(o.name);
        }
    }

    private static final String[] VIDEO_EXT = {
            ".mp4", ".m4v", ".mkv", ".mov", ".webm", ".ts", ".m2ts", ".3gp", ".avi"
    };

    static boolean isVideo(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String e : VIDEO_EXT) {
            if (n.endsWith(e)) {
                return true;
            }
        }
        return false;
    }

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
            + "<d:resourcetype/><d:getcontentlength/><d:displayname/>"
            + "</d:prop></d:propfind>";

    static Server load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String base = p.getString("base", null);
        return base == null ? null
                : new Server(base, p.getString("user", "depth3d"), p.getString("pass", ""));
    }

    static void save(Context c, Server s) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("base", s.base).putString("user", s.user).putString("pass", s.pass)
                .apply();
    }

    /** Files and folders directly under path (which starts with "/"). Network, not on main. */
    static List<Item> list(Server s, String path) throws IOException {
        Request req = new Request.Builder()
                .url(s.base + path)
                .header("Authorization", s.auth())
                .header("Depth", "1")
                .method("PROPFIND", RequestBody.create(PROPFIND_BODY,
                        MediaType.get("application/xml; charset=utf-8")))
                .build();
        try (Response r = HTTP.newCall(req).execute()) {
            if (r.code() == 401) {
                throw new IOException("用户名或密码不对");
            }
            if (r.code() != 207) {
                throw new IOException("服务器返回 " + r.code());
            }
            List<Item> items = parse(r.body().byteStream(), path);
            Collections.sort(items);
            return items;
        } catch (org.xmlpull.v1.XmlPullParserException e) {
            throw new IOException("目录解析失败: " + e.getMessage());
        }
    }

    private static List<Item> parse(InputStream in, String self)
            throws org.xmlpull.v1.XmlPullParserException, IOException {
        XmlPullParser x = Xml.newPullParser();
        x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        x.setInput(in, "UTF-8");
        List<Item> out = new ArrayList<>();
        String href = null, name = null, field = null;
        boolean dir = false;
        long size = -1;
        String selfDecoded = decode(self).replaceAll("/+$", "");
        for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
            if (ev == XmlPullParser.START_TAG && "DAV:".equals(x.getNamespace())) {
                switch (x.getName()) {
                    case "response":
                        href = null; name = null; dir = false; size = -1;
                        break;
                    case "collection":
                        dir = true;
                        break;
                    case "href": case "displayname": case "getcontentlength":
                        field = x.getName();
                        break;
                }
            } else if (ev == XmlPullParser.TEXT && field != null) {
                String t = x.getText().trim();
                if ("href".equals(field)) href = t;
                else if ("displayname".equals(field)) name = t;
                else if ("getcontentlength".equals(field)) {
                    try { size = Long.parseLong(t); } catch (NumberFormatException ignored) { }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                field = null;
                if ("response".equals(x.getName()) && "DAV:".equals(x.getNamespace()) && href != null) {
                    String path = pathOf(href);
                    if (decode(path).replaceAll("/+$", "").equals(selfDecoded)) {
                        continue;  // the folder itself
                    }
                    if (name == null || name.isEmpty()) {
                        String d = decode(path).replaceAll("/+$", "");
                        name = d.substring(d.lastIndexOf('/') + 1);
                    }
                    if (name.startsWith(".")) {
                        continue;
                    }
                    if (dir || isVideo(name)) {
                        out.add(new Item(path, name, dir, size));
                    }
                }
            }
        }
        return out;
    }

    // Some servers send a full URL in href, some only the path
    private static String pathOf(String href) {
        int scheme = href.indexOf("://");
        if (scheme < 0) {
            return href;
        }
        int slash = href.indexOf('/', scheme + 3);
        return slash < 0 ? "/" : href.substring(slash);
    }

    static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * Looks for a _webdav._tcp service on the Wi-Fi for up to timeoutMs and
     * returns its http://host:port, or null. Network, not on main.
     */
    static String discover(Context c, int timeoutMs) {
        WifiManager wifi = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = wifi.createMulticastLock("depth3d-dav");
        lock.setReferenceCounted(false);
        lock.acquire();
        JmDNS dns = null;
        try {
            dns = JmDNS.create(localAddress());
            ServiceInfo[] found = dns.list("_webdav._tcp.local.", timeoutMs);
            ServiceInfo best = null;
            for (ServiceInfo si : found) {
                if (best == null || si.getName().startsWith("depth3d")) {
                    best = si;
                }
            }
            if (best != null) {
                for (Inet4Address a : best.getInet4Addresses()) {
                    return "http://" + a.getHostAddress() + ":" + best.getPort();
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        } finally {
            if (dns != null) {
                try { dns.close(); } catch (IOException ignored) { }
            }
            lock.release();
        }
    }

    private static InetAddress localAddress() throws IOException {
        Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
        while (ifs != null && ifs.hasMoreElements()) {
            NetworkInterface ni = ifs.nextElement();
            if (!ni.isUp() || ni.isLoopback()) {
                continue;
            }
            Enumeration<InetAddress> as = ni.getInetAddresses();
            while (as.hasMoreElements()) {
                InetAddress a = as.nextElement();
                if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                    return a;
                }
            }
        }
        return InetAddress.getLocalHost();
    }

    private WebDav() {
    }
}
