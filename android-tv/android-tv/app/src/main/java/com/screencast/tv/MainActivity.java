package com.screencast.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Locale;

/**
 * Приёмник трансляции экрана.
 *
 * TCP 8765. Протокол v2: "SCR2", затем кадры [int32 длина][int32 номер][JPEG];
 * после показа кадра ТВ отвечает int32 номером показанного кадра (управление очередью).
 * Протокол v1 ("SCRN", кадры [int32 длина][JPEG]) тоже поддерживается.
 *
 * UDP 8766: автопоиск. На "SCREENCAST_DISCOVER" отвечает "SCREENCAST_TV|имя".
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    static final int PORT = 8765;
    static final int DISCOVERY_PORT = 8766;
    static final int MAX_FRAME = 32 * 1024 * 1024;

    private volatile SurfaceHolder holder;
    private volatile boolean useHwCanvas = Build.VERSION.SDK_INT >= 23;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private View waitingPanel;
    private TextView ipText;
    private TextView statsText;
    private TextView toastText;

    private ServerThread server;
    private DiscoveryThread discovery;
    private WifiManager.MulticastLock multicastLock;

    // статистика
    private volatile boolean streaming;
    private volatile int frameW, frameH;
    private volatile float statFps, statDecodeMs, statKbps;
    private volatile int statDropped;
    private volatile String clientAddr = "";

    // ------------------------------------------------------------ жизненный цикл

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        SurfaceView surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, matchParent());

        waitingPanel = buildWaitingPanel();
        root.addView(waitingPanel, matchParent());

        statsText = new TextView(this);
        statsText.setTextColor(Color.WHITE);
        statsText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statsText.setTypeface(Typeface.MONOSPACE);
        statsText.setPadding(dp(14), dp(10), dp(14), dp(10));
        statsText.setBackground(rounded(0xB0000000, dp(10)));
        statsText.setVisibility(View.GONE);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        slp.setMargins(dp(24), dp(24), dp(24), dp(24));
        root.addView(statsText, slp);

        toastText = new TextView(this);
        toastText.setTextColor(Color.WHITE);
        toastText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        toastText.setPadding(dp(22), dp(12), dp(22), dp(12));
        toastText.setBackground(rounded(0xD0111827, dp(24)));
        toastText.setVisibility(View.GONE);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tlp.setMargins(0, 0, 0, dp(48));
        root.addView(toastText, tlp);

        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("screencast");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception ignored) { }

        showWaiting();
        server = new ServerThread();
        server.start();
        discovery = new DiscoveryThread();
        discovery.start();
        ui.post(statsUpdater);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(statsUpdater);
        if (server != null) { server.shutdown(); server = null; }
        if (discovery != null) { discovery.shutdown(); discovery = null; }
        try { if (multicastLock != null) multicastLock.release(); } catch (Exception ignored) { }
        multicastLock = null;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_INFO) {
            statsText.setVisibility(statsText.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------ интерфейс

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams wrap(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    private View buildWaitingPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF070B16, 0xFF0F1B3D, 0xFF1D3A8A}));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        panel.addView(logo, new LinearLayout.LayoutParams(dp(88), dp(88)));

        panel.addView(text("ScreenCast TV", 40, Color.WHITE, true), wrap(14));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(0xFF38BDF8);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dlp.rightMargin = dp(10);
        statusRow.addView(dot, dlp);
        statusRow.addView(text("Ожидание подключения с компьютера", 20, 0xFFBFD3FF, false));
        panel.addView(statusRow, wrap(10));
        AlphaAnimation pulse = new AlphaAnimation(1f, 0.2f);
        pulse.setDuration(900);
        pulse.setRepeatMode(Animation.REVERSE);
        pulse.setRepeatCount(Animation.INFINITE);
        dot.startAnimation(pulse);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(48), dp(22), dp(48), dp(22));
        GradientDrawable cardBg = rounded(0x1FFFFFFF, dp(20));
        cardBg.setStroke(dp(1), 0x33FFFFFF);
        card.setBackground(cardBg);
        card.addView(text("АДРЕС ТЕЛЕВИЗОРА", 13, 0xFF93A4C8, true));
        ipText = text("—", 46, Color.WHITE, true);
        ipText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        card.addView(ipText, wrap(6));
        card.addView(text("порт " + PORT, 15, 0xFF93A4C8, false), wrap(2));
        panel.addView(card, wrap(34));

        TextView hint = text("Откройте ScreenCast на компьютере и нажмите «Найти ТВ» —\n"
                + "телевизор появится в списке автоматически.\n\n"
                + "Во время трансляции: ОК на пульте — статистика, Назад — выход.",
                16, 0xFF8EA0C4, false);
        hint.setLineSpacing(0, 1.25f);
        panel.addView(hint, wrap(34));
        return panel;
    }

    private void showWaiting() {
        final String ip = getLocalIp();
        ui.post(new Runnable() {
            @Override public void run() {
                ipText.setText(ip);
                waitingPanel.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hideWaiting() {
        ui.post(new Runnable() {
            @Override public void run() { waitingPanel.setVisibility(View.GONE); }
        });
    }

    private final Runnable hideToast = new Runnable() {
        @Override public void run() { toastText.setVisibility(View.GONE); }
    };

    private void toast(final String msg) {
        ui.post(new Runnable() {
            @Override public void run() {
                toastText.setText(msg);
                toastText.setVisibility(View.VISIBLE);
                ui.removeCallbacks(hideToast);
                ui.postDelayed(hideToast, 2500);
            }
        });
    }

    private final Runnable statsUpdater = new Runnable() {
        @Override public void run() {
            if (statsText.getVisibility() == View.VISIBLE) {
                if (streaming) {
                    statsText.setText(String.format(Locale.US,
                            "%dx%d\n%.0f к/с\nдекод %.1f мс\n%.1f Мбит/с\nпропущено %d\nПК %s",
                            frameW, frameH, statFps, statDecodeMs, statKbps * 8 / 1000f,
                            statDropped, clientAddr));
                } else {
                    statsText.setText("нет трансляции");
                }
            }
            ui.postDelayed(this, 500);
        }
    };

    private static String getLocalIp() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        if (a.isSiteLocalAddress()) return a.getHostAddress();
                        if (fallback == null) fallback = a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return fallback != null ? fallback : "нет сети";
    }

    private String deviceName() {
        String name = null;
        try {
            if (Build.VERSION.SDK_INT >= 25) {
                name = Settings.Global.getString(getContentResolver(), "device_name");
            }
        } catch (Exception ignored) { }
        if (name == null || name.trim().isEmpty()) name = Build.MANUFACTURER + " " + Build.MODEL;
        return name.replace('|', ' ').trim();
    }

    // ------------------------------------------------------------ отрисовка

    @Override public void surfaceCreated(SurfaceHolder h) { holder = h; }
    @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) { holder = h; }
    @Override public void surfaceDestroyed(SurfaceHolder h) { holder = null; }

    private void drawFrame(Bitmap bmp) {
        SurfaceHolder h = holder;
        if (h == null) return;
        Surface surface = h.getSurface();
        Canvas c = null;
        boolean hw = false;
        try {
            if (useHwCanvas && Build.VERSION.SDK_INT >= 23) {
                try {
                    c = surface.lockHardwareCanvas();
                    hw = true;
                } catch (Exception e) {
                    useHwCanvas = false;
                    c = null;
                }
            }
            if (c == null) c = h.lockCanvas();
            if (c == null) return;

            int cw = c.getWidth(), ch = c.getHeight();
            int bw = bmp.getWidth(), bh = bmp.getHeight();
            float scale = Math.min(cw / (float) bw, ch / (float) bh);
            int w = Math.round(bw * scale), hh = Math.round(bh * scale);
            int left = (cw - w) / 2, top = (ch - hh) / 2;
            dst.set(left, top, left + w, top + hh);
            c.drawColor(Color.BLACK);
            c.drawBitmap(bmp, null, dst, paint);
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                try {
                    if (hw) surface.unlockCanvasAndPost(c);
                    else h.unlockCanvasAndPost(c);
                } catch (Exception ignored) { }
            }
        }
    }

    private void clearSurface() {
        SurfaceHolder h = holder;
        if (h == null) return;
        try {
            Canvas c = h.lockCanvas();
            if (c != null) {
                c.drawColor(Color.BLACK);
                h.unlockCanvasAndPost(c);
            }
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ сеть: приём кадров

    private static final class FrameBuf {
        byte[] data = new byte[1 << 20];
        int len;
        int id;
    }

    /** Одно подключение: этот поток читает кадры, RenderThread декодирует и показывает. */
    private class Session {
        final Socket socket;
        final boolean ackMode;
        final DataOutputStream out;
        final Object lock = new Object();
        FrameBuf slot = new FrameBuf();
        boolean slotFresh;
        volatile boolean alive = true;
        int dropped;

        Session(Socket s, boolean ackMode) throws IOException {
            this.socket = s;
            this.ackMode = ackMode;
            this.out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 64));
        }

        void readLoop(DataInputStream in) throws IOException {
            FrameBuf w = new FrameBuf();
            long bytes = 0, t0 = SystemClock.elapsedRealtime();
            while (alive) {
                int len = in.readInt();
                if (len <= 0 || len > MAX_FRAME) throw new IOException("bad frame length " + len);
                int id = ackMode ? in.readInt() : 0;
                if (w.data.length < len) w.data = new byte[len + len / 2];
                in.readFully(w.data, 0, len);
                w.len = len;
                w.id = id;
                bytes += len + 8;
                long now = SystemClock.elapsedRealtime();
                if (now - t0 >= 1000) {
                    statKbps = bytes / (float) (now - t0);
                    bytes = 0;
                    t0 = now;
                }
                synchronized (lock) {
                    if (slotFresh) dropped++;
                    FrameBuf t = slot;
                    slot = w;
                    w = t;
                    slotFresh = true;
                    lock.notifyAll();
                }
            }
        }

        void close() {
            alive = false;
            synchronized (lock) { lock.notifyAll(); }
            try { socket.close(); } catch (IOException ignored) { }
        }

        final Thread renderThread = new Thread("ScreenCastRender") {
            @Override public void run() {
                FrameBuf r = new FrameBuf();
                Bitmap[] bitmaps = new Bitmap[2];
                int which = 0;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inMutable = true;
                int frames = 0;
                float decodeSum = 0;
                long t0 = SystemClock.elapsedRealtime();
                boolean first = true;

                while (alive) {
                    synchronized (lock) {
                        while (alive && !slotFresh) {
                            try { lock.wait(); } catch (InterruptedException e) { return; }
                        }
                        if (!alive) return;
                        FrameBuf t = slot;
                        slot = r;
                        r = t;
                        slotFresh = false;
                    }

                    long d0 = SystemClock.uptimeMillis();
                    Bitmap bmp;
                    opts.inBitmap = bitmaps[which];
                    try {
                        bmp = BitmapFactory.decodeByteArray(r.data, 0, r.len, opts);
                    } catch (IllegalArgumentException e) {
                        opts.inBitmap = null;
                        bmp = BitmapFactory.decodeByteArray(r.data, 0, r.len, opts);
                    }
                    decodeSum += SystemClock.uptimeMillis() - d0;

                    if (bmp != null) {
                        bitmaps[which] = bmp;
                        which ^= 1;
                        frameW = bmp.getWidth();
                        frameH = bmp.getHeight();
                        if (first) {
                            first = false;
                            hideWaiting();
                        }
                        drawFrame(bmp);
                    }

                    if (ackMode) {
                        try {
                            out.writeInt(r.id);
                            out.flush();
                        } catch (IOException e) {
                            close();
                            return;
                        }
                    }

                    frames++;
                    long now = SystemClock.elapsedRealtime();
                    if (now - t0 >= 1000) {
                        statFps = frames * 1000f / (now - t0);
                        statDecodeMs = decodeSum / Math.max(1, frames);
                        statDropped = dropped;
                        frames = 0;
                        decodeSum = 0;
                        t0 = now;
                    }
                }
            }
        };
    }

    private class ServerThread extends Thread {
        private volatile boolean running = true;
        private ServerSocket serverSocket;
        private volatile Session session;

        ServerThread() { super("ScreenCastServer"); }

        void shutdown() {
            running = false;
            Session s = session;
            if (s != null) s.close();
            try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        }

        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(PORT));
                while (running) {
                    Socket s = serverSocket.accept();
                    handle(s);
                    if (running) {
                        streaming = false;
                        clearSurface();
                        showWaiting();
                        toast("Трансляция завершена");
                    }
                }
            } catch (IOException e) {
                if (running) toast("Ошибка сервера: " + e.getMessage());
            }
        }

        private void handle(Socket s) {
            Session sess = null;
            try {
                s.setTcpNoDelay(true);
                s.setReceiveBufferSize(256 * 1024);
                DataInputStream in = new DataInputStream(
                        new BufferedInputStream(s.getInputStream(), 1 << 16));
                byte[] magic = new byte[4];
                in.readFully(magic);
                String m = new String(magic, "US-ASCII");
                boolean ack;
                if ("SCR2".equals(m)) ack = true;
                else if ("SCRN".equals(m)) ack = false;
                else return;

                sess = new Session(s, ack);
                session = sess;
                clientAddr = s.getInetAddress().getHostAddress();
                streaming = true;
                toast("Подключено: " + clientAddr);
                sess.renderThread.start();
                sess.readLoop(in);
            } catch (IOException ignored) {
                // клиент отключился
            } finally {
                if (sess != null) {
                    sess.close();
                    try { sess.renderThread.join(1000); } catch (InterruptedException ignored) { }
                } else {
                    try { s.close(); } catch (IOException ignored) { }
                }
                session = null;
            }
        }
    }

    // ------------------------------------------------------------ сеть: автопоиск

    private class DiscoveryThread extends Thread {
        private volatile boolean running = true;
        private DatagramSocket socket;

        DiscoveryThread() { super("ScreenCastDiscovery"); }

        void shutdown() {
            running = false;
            if (socket != null) socket.close();
        }

        @Override
        public void run() {
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.setBroadcast(true);
                socket.bind(new InetSocketAddress(DISCOVERY_PORT));
                byte[] buf = new byte[512];
                byte[] reply = ("SCREENCAST_TV|" + deviceName()).getBytes("UTF-8");
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), "UTF-8");
                    if (msg.startsWith("SCREENCAST_DISCOVER")) {
                        socket.send(new DatagramPacket(reply, reply.length, p.getAddress(), p.getPort()));
                    }
                }
            } catch (IOException ignored) {
                // закрыт при выходе или порт занят — автопоиск недоступен, ввод IP вручную работает
            }
        }
    }
}
