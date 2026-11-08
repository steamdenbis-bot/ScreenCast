package com.screencast.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;

/**
 * Приёмник трансляции экрана.
 * Слушает TCP-порт 8765. Протокол: "SCRN", затем кадры [int32 длина big-endian][JPEG].
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    static final int PORT = 8765;
    static final int MAX_FRAME = 32 * 1024 * 1024;

    private volatile SurfaceHolder holder;
    private TextView info;
    private ServerThread server;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Rect dst = new Rect();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        SurfaceView surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        info = new TextView(this);
        info.setTextColor(Color.WHITE);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        info.setGravity(Gravity.CENTER);
        info.setLineSpacing(0, 1.3f);
        root.addView(info, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        showWaiting();
    }

    @Override
    protected void onStart() {
        super.onStart();
        server = new ServerThread();
        server.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (server != null) {
            server.shutdown();
            server = null;
        }
    }

    // ---------- Surface ----------
    @Override public void surfaceCreated(SurfaceHolder h) { holder = h; }
    @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) { holder = h; }
    @Override public void surfaceDestroyed(SurfaceHolder h) { holder = null; }

    private void drawFrame(Bitmap bmp) {
        SurfaceHolder h = holder;
        if (h == null) return;
        Canvas c = null;
        try {
            c = h.lockCanvas();
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
                try { h.unlockCanvasAndPost(c); } catch (Exception ignored) { }
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

    // ---------- Подсказки на экране ----------
    private void showWaiting() {
        final String text = "ScreenCast TV\n\nОжидание подключения с ПК...\n\n"
                + "IP телевизора:  " + getLocalIp() + "\nПорт:  " + PORT;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                info.setText(text);
                info.setVisibility(View.VISIBLE);
            }
        });
    }

    private void showMessage(final String text) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                info.setText(text);
                info.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hideInfo() {
        runOnUiThread(new Runnable() {
            @Override public void run() { info.setVisibility(View.GONE); }
        });
    }

    private static String getLocalIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return "неизвестен (проверьте сеть)";
    }

    // ---------- Сервер ----------
    private class ServerThread extends Thread {
        private volatile boolean running = true;
        private ServerSocket serverSocket;
        private volatile Socket client;

        ServerThread() { super("ScreenCastServer"); }

        void shutdown() {
            running = false;
            try { if (client != null) client.close(); } catch (IOException ignored) { }
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
                    client = s;
                    handle(s);
                    client = null;
                    if (running) {
                        clearSurface();
                        showWaiting();
                    }
                }
            } catch (IOException e) {
                if (running) showMessage("Ошибка сервера: " + e.getMessage()
                        + "\n\nПерезапустите приложение.");
            }
        }

        private void handle(Socket s) {
            try {
                s.setTcpNoDelay(true);
                s.setReceiveBufferSize(1 << 20);
                DataInputStream in = new DataInputStream(
                        new BufferedInputStream(s.getInputStream(), 1 << 16));

                byte[] magic = new byte[4];
                in.readFully(magic);
                if (magic[0] != 'S' || magic[1] != 'C' || magic[2] != 'R' || magic[3] != 'N') {
                    return;
                }
                hideInfo();

                byte[] buf = new byte[1 << 20];
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inMutable = true;
                Bitmap reuse = null;

                while (running) {
                    int len = in.readInt();
                    if (len <= 0 || len > MAX_FRAME) break;
                    if (buf.length < len) buf = new byte[len + len / 2];
                    in.readFully(buf, 0, len);

                    Bitmap bmp;
                    opts.inBitmap = reuse;
                    try {
                        bmp = BitmapFactory.decodeByteArray(buf, 0, len, opts);
                    } catch (IllegalArgumentException e) {
                        // размер кадра изменился — декодируем без переиспользования
                        opts.inBitmap = null;
                        bmp = BitmapFactory.decodeByteArray(buf, 0, len, opts);
                    }
                    if (bmp == null) continue;
                    reuse = bmp;
                    drawFrame(bmp);
                }
            } catch (IOException ignored) {
                // клиент отключился
            } finally {
                try { s.close(); } catch (IOException ignored) { }
            }
        }
    }
}
