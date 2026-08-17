#!/usr/bin/env python3
"""
Conect VCam — Windows remote control for Virtual Cam Android app.

Requirements:
  pip install -r requirements.txt

Usage:
  1. In OBS: Start Virtual Camera  (Tools → Start Virtual Camera)
  2. Connect Android phone via USB, enable USB Debugging
  3. Click "ADB Forward"  (or: adb forward tcp:7979 tcp:7979)
  4. Open Virtual Cam app on phone → OBS Bridge → Enable Link → copy token
  5. Paste token here, click Connect, then Start Stream
"""

import tkinter as tk
from tkinter import ttk, messagebox, font as tkfont
import socket
import json
import subprocess
import threading
import sys
import os
import base64
import io
import time

PORT      = 7979
APP_TITLE = "Conect VCam"
BG    = "#0d1117"
CARD  = "#161b22"
ACCENT = "#4F8EF7"
GREEN = "#22c55e"
RED   = "#ef4444"
FG    = "#e6edf3"
FG2   = "#8b949e"
PREVIEW_W = 384
PREVIEW_H = 216   # 16:9


class ConnectVcam:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title(APP_TITLE)
        self.root.geometry("440x920")
        self.root.resizable(False, False)
        self.root.configure(bg=BG)
        self._set_icon()

        # connection state
        self.sock: socket.socket | None = None
        self.connected = False
        self.send_lock = threading.Lock()

        # camera / stream state
        self.streaming     = False
        self.stream_thread: threading.Thread | None = None
        self.stream_stop   = threading.Event()
        self._preview_photo = None   # keep ImageTk ref alive

        # control state
        self.pan_x      = 0
        self.pan_y      = 0
        self.rotation   = 0
        self.mirror_state = False

        # Tk vars
        self.host_var         = tk.StringVar(value="localhost")
        self.port_var         = tk.IntVar(value=PORT)
        self.token_var        = tk.StringVar()
        self.zoom_var         = tk.DoubleVar(value=1.0)
        self.scale_var        = tk.DoubleVar(value=1.0)
        self.cam_index_var    = tk.IntVar(value=0)
        self.jpeg_quality_var = tk.IntVar(value=85)

        self._build_ui()

    # ── icon ──────────────────────────────────────────────────────────────
    def _set_icon(self):
        try:
            self.root.iconbitmap(default="icon.ico")
        except Exception:
            pass

    # ── UI helpers ────────────────────────────────────────────────────────
    def _card(self, title: str) -> tk.Frame:
        outer = tk.Frame(self.main_frame, bg=CARD, bd=0, relief="flat")
        outer.pack(fill="x", padx=14, pady=6)
        tk.Label(outer, text=title, bg=CARD, fg=FG2,
                 font=("Segoe UI", 9, "bold")).pack(anchor="w", padx=12, pady=(8, 4))
        inner = tk.Frame(outer, bg=CARD)
        inner.pack(fill="x", padx=12, pady=(0, 10))
        return inner

    def _btn(self, parent, text, cmd, color=ACCENT, fg=FG, width=10):
        b = tk.Button(parent, text=text, command=cmd, bg=color, fg=fg,
                      relief="flat", padx=8, pady=6, width=width,
                      font=("Segoe UI", 9), cursor="hand2",
                      activebackground=color, activeforeground=fg)
        b.pack(side="left", padx=4, pady=2)
        return b

    # ── build UI ──────────────────────────────────────────────────────────
    def _build_ui(self):
        # scrollable canvas so nothing gets cut off on small screens
        canvas = tk.Canvas(self.root, bg=BG, highlightthickness=0)
        vsb    = tk.Scrollbar(self.root, orient="vertical", command=canvas.yview)
        canvas.configure(yscrollcommand=vsb.set)
        vsb.pack(side="right", fill="y")
        canvas.pack(side="left", fill="both", expand=True)

        self.main_frame = tk.Frame(canvas, bg=BG)
        win_id = canvas.create_window((0, 0), window=self.main_frame, anchor="nw")

        def _on_configure(e):
            canvas.configure(scrollregion=canvas.bbox("all"))
            canvas.itemconfig(win_id, width=canvas.winfo_width())
        self.main_frame.bind("<Configure>", _on_configure)

        # mouse-wheel scroll
        def _on_wheel(e):
            canvas.yview_scroll(int(-1 * (e.delta / 120)), "units")
        canvas.bind_all("<MouseWheel>", _on_wheel)

        # title bar
        hdr = tk.Frame(self.main_frame, bg=BG)
        hdr.pack(fill="x", padx=14, pady=(14, 4))
        tk.Label(hdr, text="⬡ CONECT VCAM", bg=BG, fg=ACCENT,
                 font=("Segoe UI", 14, "bold")).pack(side="left")
        self.lbl_status = tk.Label(hdr, text="● Disconnected", bg=BG, fg=RED,
                                    font=("Segoe UI", 9))
        self.lbl_status.pack(side="right", padx=4)

        self._build_connection_card()
        self._build_obs_vcam_card()
        self._build_zoom_card()
        self._build_scale_card()
        self._build_pan_card()
        self._build_rotate_mirror_card()
        self._build_instructions()

    # ── connection card ───────────────────────────────────────────────────
    def _build_connection_card(self):
        f = self._card("CONNECTION / الاتصال")

        row = tk.Frame(f, bg=CARD); row.pack(fill="x", pady=2)
        tk.Label(row, text="Host:", bg=CARD, fg=FG2, width=7, anchor="e").pack(side="left")
        tk.Entry(row, textvariable=self.host_var, bg="#0d1117", fg=FG,
                 insertbackground=FG, relief="flat", bd=4, width=22).pack(side="left", padx=4)

        row2 = tk.Frame(f, bg=CARD); row2.pack(fill="x", pady=2)
        tk.Label(row2, text="Port:", bg=CARD, fg=FG2, width=7, anchor="e").pack(side="left")
        tk.Entry(row2, textvariable=self.port_var, bg="#0d1117", fg=FG,
                 insertbackground=FG, relief="flat", bd=4, width=10).pack(side="left", padx=4)

        row3 = tk.Frame(f, bg=CARD); row3.pack(fill="x", pady=2)
        tk.Label(row3, text="Token:", bg=CARD, fg=FG2, width=7, anchor="e").pack(side="left")
        tk.Entry(row3, textvariable=self.token_var, bg="#0d1117", fg=FG,
                 insertbackground=FG, relief="flat", bd=4, width=14,
                 font=("Consolas", 12, "bold")).pack(side="left", padx=4)

        btns = tk.Frame(f, bg=CARD); btns.pack(fill="x", pady=(8, 2))
        self._btn(btns, "⚡ ADB Forward", self._adb_forward, color="#21262d", width=14)
        self.btn_connect = self._btn(btns, "🔌 Connect", self._toggle_connect,
                                     color=ACCENT, width=14)

    # ── OBS Virtual Camera card ────────────────────────────────────────────
    def _build_obs_vcam_card(self):
        f = self._card("OBS VIRTUAL CAMERA / كاميرا OBS المباشرة")

        # ── اختيار الكاميرا ─────────────────────────────────────────────
        ctrl_row = tk.Frame(f, bg=CARD); ctrl_row.pack(fill="x", pady=(0, 8))

        tk.Label(ctrl_row, text="Camera:", bg=CARD, fg=FG2,
                 font=("Segoe UI", 9)).pack(side="left")
        tk.Entry(ctrl_row, textvariable=self.cam_index_var, bg="#0d1117", fg=FG,
                 insertbackground=FG, relief="flat", bd=4, width=4,
                 font=("Segoe UI", 10, "bold")).pack(side="left", padx=(6, 2))
        tk.Label(ctrl_row, text="(index)", bg=CARD, fg=FG2,
                 font=("Segoe UI", 8)).pack(side="left", padx=(0, 12))

        self._btn(ctrl_row, "🔍 Detect", self._detect_cameras, color="#21262d", width=9)

        tk.Label(ctrl_row, text="Quality:", bg=CARD, fg=FG2,
                 font=("Segoe UI", 9)).pack(side="left", padx=(8, 4))
        tk.Entry(ctrl_row, textvariable=self.jpeg_quality_var, bg="#0d1117", fg=FG,
                 insertbackground=FG, relief="flat", bd=4, width=4).pack(side="left")

        # ── معاينة مباشرة ────────────────────────────────────────────────
        preview_outer = tk.Frame(f, bg="#0d1117", bd=2, relief="sunken")
        preview_outer.pack(fill="x", pady=(0, 8))
        self.preview_label = tk.Label(
            preview_outer,
            text="📷  Preview\nاضغط Start Stream لبدء البث",
            bg="#0d1117", fg=FG2,
            font=("Segoe UI", 9),
            width=PREVIEW_W, height=12,
            cursor="crosshair"
        )
        self.preview_label.pack()

        # ── زر البدء/الإيقاف ─────────────────────────────────────────────
        btns = tk.Frame(f, bg=CARD); btns.pack(fill="x", pady=(0, 2))
        self.btn_stream = self._btn(
            btns, "▶ Start Stream", self._toggle_stream, color=GREEN, fg="#ffffff", width=16)
        self.lbl_stream = tk.Label(
            btns, text="● Idle", bg=CARD, fg=FG2, font=("Segoe UI", 8))
        self.lbl_stream.pack(side="right", padx=4, pady=8)

    # ── zoom card ─────────────────────────────────────────────────────────
    def _build_zoom_card(self):
        f = self._card("ZOOM / تقريب")
        header = tk.Frame(f, bg=CARD); header.pack(fill="x")
        tk.Label(header, text="Digital Zoom:", bg=CARD, fg=FG2,
                 font=("Segoe UI", 9)).pack(side="left")
        self.lbl_zoom = tk.Label(header, text="1.0×", bg=CARD, fg=ACCENT,
                                  font=("Segoe UI", 10, "bold"))
        self.lbl_zoom.pack(side="right")
        ttk.Scale(f, from_=1.0, to=5.0, orient="horizontal",
                  variable=self.zoom_var, command=self._on_zoom).pack(fill="x", pady=4)
        btns = tk.Frame(f, bg=CARD); btns.pack()
        self._btn(btns, "1× Reset", lambda: self._set_zoom(1.0), color="#21262d", width=9)
        self._btn(btns, "2×", lambda: self._set_zoom(2.0), color="#21262d", width=5)
        self._btn(btns, "3×", lambda: self._set_zoom(3.0), color="#21262d", width=5)
        self._btn(btns, "5×", lambda: self._set_zoom(5.0), color="#21262d", width=5)

    # ── scale card ────────────────────────────────────────────────────────
    def _build_scale_card(self):
        f = self._card("SCALE / حجم الإطار")
        header = tk.Frame(f, bg=CARD); header.pack(fill="x")
        tk.Label(header, text="Frame Fill Scale:", bg=CARD, fg=FG2,
                 font=("Segoe UI", 9)).pack(side="left")
        self.lbl_scale = tk.Label(header, text="100%", bg=CARD, fg=GREEN,
                                   font=("Segoe UI", 10, "bold"))
        self.lbl_scale.pack(side="right")
        ttk.Scale(f, from_=0.3, to=2.0, orient="horizontal",
                  variable=self.scale_var, command=self._on_scale).pack(fill="x", pady=4)
        btns = tk.Frame(f, bg=CARD); btns.pack()
        self._btn(btns, "100%", lambda: self._set_scale(1.0), color="#21262d", width=9)
        self._btn(btns, "50%",  lambda: self._set_scale(0.5), color="#21262d", width=5)
        self._btn(btns, "150%", lambda: self._set_scale(1.5), color="#21262d", width=7)
        self._btn(btns, "200%", lambda: self._set_scale(2.0), color="#21262d", width=7)

    # ── pan card ──────────────────────────────────────────────────────────
    def _build_pan_card(self):
        f = self._card("PAN / تحريك")
        grid = tk.Frame(f, bg=CARD); grid.pack(pady=4)
        arrows = [
            ("↑", 0, 1, lambda: self._pan(0,  0.05)),
            ("←", 1, 0, lambda: self._pan(-0.05, 0)),
            ("⌂", 1, 1, self._reset_pan),
            ("→", 1, 2, lambda: self._pan( 0.05, 0)),
            ("↓", 2, 1, lambda: self._pan(0, -0.05)),
        ]
        for (text, r, c, cmd) in arrows:
            tk.Button(grid, text=text, command=cmd, bg="#21262d", fg=FG,
                      relief="flat", width=3, height=1, font=("Segoe UI", 11),
                      cursor="hand2").grid(row=r, column=c, padx=2, pady=2)

    # ── rotate / mirror card ──────────────────────────────────────────────
    def _build_rotate_mirror_card(self):
        f = self._card("ROTATE & MIRROR / تدوير وعكس")
        btns = tk.Frame(f, bg=CARD); btns.pack()
        self._btn(btns, "↺ −90°", lambda: self._rotate(-90), color="#21262d", width=8)
        self._btn(btns, "↻ +90°", lambda: self._rotate( 90), color="#21262d", width=8)
        self.btn_mirror = self._btn(btns, "⇆ Mirror OFF", self._toggle_mirror,
                                    color="#21262d", width=12)

    # ── instructions ──────────────────────────────────────────────────────
    def _build_instructions(self):
        f = self._card("HOW TO USE / طريقة الاستخدام")
        steps = (
            "1. في OBS: ابدأ الكاميرا الافتراضية  (Tools → Start Virtual Camera)\n"
            "2. وصّل الهاتف بـ USB وفعّل USB Debugging\n"
            "3. اضغط ADB Forward → Connect → أدخل التوكن\n"
            "4. اضغط 🔍 Detect لاختيار الكاميرا الصحيحة\n"
            "5. اضغط ▶ Start Stream — تظهر المعاينة وتبدأ الحقن"
        )
        tk.Label(f, text=steps, bg=CARD, fg=FG2, justify="left",
                 wraplength=370, font=("Segoe UI", 8)).pack(anchor="w")

    # ── camera detection ──────────────────────────────────────────────────
    def _detect_cameras(self):
        """Scan indices 0-9 with DirectShow and report which ones open."""
        def _scan():
            try:
                import cv2
            except ImportError:
                self.root.after(0, lambda: messagebox.showerror(
                    "Missing", "Install opencv-python:\npip install -r requirements.txt"))
                return

            found = []
            for i in range(10):
                cap = cv2.VideoCapture(i, cv2.CAP_DSHOW)
                if cap.isOpened():
                    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
                    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
                    found.append((i, w, h))
                    cap.release()

            def _show():
                if not found:
                    messagebox.showwarning(
                        "No cameras found",
                        "No cameras detected.\n\n"
                        "Make sure OBS is open and Virtual Camera is started:\n"
                        "OBS → Tools → Start Virtual Camera"
                    )
                    return
                lines = "\n".join(f"  Index {i}: {w}×{h}" for i, w, h in found)
                msg = f"Cameras found:\n{lines}\n\nOBS Virtual Camera is usually the last index."
                # Auto-set to the highest index (most likely OBS VCam)
                best = found[-1][0]
                self.cam_index_var.set(best)
                messagebox.showinfo("Cameras detected", msg +
                                    f"\n\nIndex set to {best}.")
            self.root.after(0, _show)

        threading.Thread(target=_scan, daemon=True).start()
        self.lbl_stream.config(text="● Scanning...", fg=ACCENT)
        self.root.after(5000, lambda: self.lbl_stream.config(
            text="● Idle" if not self.streaming else "● Streaming", fg=FG2 if not self.streaming else GREEN))

    # ── stream control ────────────────────────────────────────────────────
    def _toggle_stream(self):
        if self.streaming:
            self._stop_stream()
            return
        try:
            quality = max(40, min(95, int(self.jpeg_quality_var.get())))
            self.jpeg_quality_var.set(quality)
            cam_idx = max(0, int(self.cam_index_var.get()))
        except (ValueError, Exception):
            messagebox.showerror("Settings", "Camera index and quality must be numbers.")
            return

        self.streaming = True
        self.stream_stop.clear()
        self.btn_stream.config(text="■ Stop Stream", bg=RED)
        self.lbl_stream.config(text="● Streaming", fg=GREEN)
        self.stream_thread = threading.Thread(target=self._stream_loop, daemon=True)
        self.stream_thread.start()

    def _stop_stream(self):
        self.streaming = False
        self.stream_stop.set()
        self.btn_stream.config(text="▶ Start Stream", bg=GREEN)
        self.lbl_stream.config(text="● Idle", fg=FG2)

    def _auto_connect(self):
        """Best-effort auto-connect: ADB forward then connect to the phone.
        Called once when Start Stream is pressed so frames reach the Android
        app without the user having to click Connect manually."""
        try:
            subprocess.run(
                ["adb", "forward", f"tcp:{PORT}", f"tcp:{PORT}"],
                capture_output=True, text=True, timeout=10
            )
        except Exception:
            pass
        if not self.connected:
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                s.settimeout(5)
                s.connect((self.host_var.get(), int(self.port_var.get())))
                s.settimeout(None)
                self.sock = s
                token = self.token_var.get().strip()
                msg = json.dumps({"cmd": "auth", "token": token}) + "\n"
                s.sendall(msg.encode("utf-8"))
                resp = json.loads(s.makefile().readline())
                if not resp.get("ok") and resp.get("status") != "ok":
                    raise ConnectionError(resp.get("message", resp.get("error", "Auth failed")))
                self.connected = True
                self.btn_connect.config(text="✕ Disconnect", bg=RED)
                self.lbl_status.config(text="● Connected", fg=GREEN)
                threading.Thread(target=self._recv_loop, daemon=True).start()
            except Exception:
                # Connection is best-effort; the local preview still works.
                self.connected = False

    # ── main stream loop ──────────────────────────────────────────────────
    def _stream_loop(self):
        """Capture from OBS Virtual Camera, show preview, stream to phone.
        Preview starts immediately; connection to the phone is attempted
        automatically in the background so the user only needs to press
        Start Stream."""
        try:
            import cv2
            from PIL import Image, ImageTk
        except ImportError:
            self.root.after(0, lambda: (
                self._stop_stream(),
                messagebox.showerror("Missing dependencies",
                                     "Run: pip install -r requirements.txt")
            ))
            return

        cam_idx = max(0, int(self.cam_index_var.get()))
        quality = max(40, min(95, int(self.jpeg_quality_var.get())))
        encode_params = [cv2.IMWRITE_JPEG_QUALITY, quality]

        # فتح الكاميرا عبر DirectShow (أسرع وأكثر توافقاً على Windows)
        cap = cv2.VideoCapture(cam_idx, cv2.CAP_DSHOW)
        # طلب أعلى جودة ممكنة
        cap.set(cv2.CAP_PROP_FRAME_WIDTH,  1920)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 1080)
        cap.set(cv2.CAP_PROP_FPS, 30)
        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)   # أدنى تخزين مؤقت = أقل تأخير

        if not cap.isOpened():
            self.root.after(0, lambda: (
                self._stop_stream(),
                messagebox.showerror(
                    "Camera error",
                    f"Cannot open camera index {cam_idx}.\n\n"
                    "• Make sure OBS Virtual Camera is started\n"
                    "• Try 🔍 Detect to find the correct index"
                )
            ))
            return

        # Attempt to connect to the phone automatically so frames are
        # forwarded over USB without the user clicking Connect manually.
        self._auto_connect()

        # اقرأ أول إطار لمعرفة الدقة الفعلية
        ret, test_frame = cap.read()
        if not ret:
            cap.release()
            self.root.after(0, lambda: (
                self._stop_stream(),
                messagebox.showerror("Camera error", "Could not read from camera.")
            ))
            return

        actual_h, actual_w = test_frame.shape[:2]
        # أبعاد المعاينة بنسبة 16:9 داخل الإطار
        prev_w = PREVIEW_W
        prev_h = int(prev_w * actual_h / actual_w) if actual_w else PREVIEW_H
        prev_h = max(prev_h, PREVIEW_H)

        try:
            while self.streaming and not self.stream_stop.is_set():
                started = time.monotonic()

                ret, frame = cap.read()
                if not ret:
                    break

                # ── إرسال JPEG للهاتف ──────────────────────────────────
                _, buf = cv2.imencode(".jpg", frame, encode_params)
                jpeg_b64 = base64.b64encode(buf.tobytes()).decode("ascii")
                self._send_frame(jpeg_b64)

                # ── تحديث المعاينة (BGR→RGB، تصغير للعرض) ──────────────
                rgb   = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                img   = Image.fromarray(rgb)
                img   = img.resize((prev_w, prev_h), Image.Resampling.BILINEAR)
                photo = ImageTk.PhotoImage(img)
                self.root.after(0, lambda p=photo: self._update_preview(p))

                # ── ~30 FPS ─────────────────────────────────────────────
                elapsed = time.monotonic() - started
                wait    = max(0.0, (1.0 / 30.0) - elapsed)
                if self.stream_stop.wait(wait):
                    break

        except Exception as exc:
            if self.streaming:
                self.root.after(0, lambda e=exc: (
                    self._stop_stream(),
                    messagebox.showerror("Stream error", str(e))
                ))
        finally:
            cap.release()
            self.root.after(0, self._clear_preview)
            if self.streaming:
                self.root.after(0, self._stop_stream)

    def _update_preview(self, photo):
        """Update the preview label in the main thread."""
        self._preview_photo = photo       # prevent garbage collection
        self.preview_label.config(image=photo, text="", width=PREVIEW_W, height=PREVIEW_H)

    def _clear_preview(self):
        """Reset preview label when stream stops."""
        self._preview_photo = None
        self.preview_label.config(
            image="",
            text="📷  Preview\nاضغط Start Stream لبدء البث",
            width=PREVIEW_W, height=12
        )

    # ── send frame ────────────────────────────────────────────────────────
    def _send_frame(self, jpeg_b64: str):
        """Fire-and-forget frame send — no ACK to avoid back-pressure at 30 fps.
        If not connected yet, the frame is silently skipped; the local preview
        keeps running. A background auto-connect is already attempting to
        establish the link."""
        if not self.connected or self.sock is None:
            return
        try:
            packet = (json.dumps({"cmd": "frame", "jpeg": jpeg_b64}) + "\n").encode("utf-8")
            with self.send_lock:
                if self.sock is not None:
                    self.sock.sendall(packet)
        except Exception:
            # Don't kill the preview just because the phone link dropped.
            self.connected = False
            if self.sock:
                try: self.sock.close()
                except Exception: pass
                self.sock = None
            self.root.after(0, lambda: self.lbl_status.config(text="● Disconnected", fg=RED))

    # ── ADB forward ───────────────────────────────────────────────────────
    def _adb_forward(self):
        try:
            out = subprocess.run(
                ["adb", "forward", f"tcp:{PORT}", f"tcp:{PORT}"],
                capture_output=True, text=True, timeout=10
            )
            if out.returncode == 0:
                messagebox.showinfo("ADB Forward", f"Port {PORT} forwarded ✓\nNow click Connect.")
            else:
                messagebox.showerror("ADB Forward", out.stderr or "ADB command failed.")
        except FileNotFoundError:
            messagebox.showerror("ADB not found",
                                  "adb.exe not found. Add Android SDK platform-tools to PATH.")
        except Exception as exc:
            messagebox.showerror("ADB Forward", str(exc))

    # ── connect / disconnect ──────────────────────────────────────────────
    def _toggle_connect(self):
        if self.connected:
            self._disconnect()
        else:
            self._connect()

    def _connect(self):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(5)
            s.connect((self.host_var.get(), int(self.port_var.get())))
            s.settimeout(None)
            self.sock = s

            # authenticate
            token = self.token_var.get().strip()
            msg   = json.dumps({"cmd": "auth", "token": token}) + "\n"
            s.sendall(msg.encode("utf-8"))
            resp  = json.loads(s.makefile().readline())
            if resp.get("status") != "ok":
                raise ConnectionError(resp.get("message", resp.get("error", "Auth failed")))

            self.connected = True
            self.btn_connect.config(text="✕ Disconnect", bg=RED)
            self.lbl_status.config(text="● Connected", fg=GREEN)

            # start keep-alive listener
            threading.Thread(target=self._recv_loop, daemon=True).start()
        except Exception as exc:
            self._disconnect()
            messagebox.showerror("Connection failed", str(exc))

    def _disconnect(self):
        if self.streaming:
            self._stop_stream()
        self.connected = False
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
            self.sock = None
        self.btn_connect.config(text="🔌 Connect", bg=ACCENT)
        self.lbl_status.config(text="● Disconnected", fg=RED)

    def _recv_loop(self):
        """Read incoming server messages (keep-alive / error detection)."""
        try:
            f = self.sock.makefile()
            for line in f:
                line = line.strip()
                if not line:
                    continue
        except Exception:
            pass
        finally:
            if self.connected:
                self.root.after(0, self._disconnect)

    # ── control helpers ───────────────────────────────────────────────────
    def _send(self, payload: dict):
        if not self.connected or self.sock is None:
            return
        try:
            msg = json.dumps(payload) + "\n"
            with self.send_lock:
                self.sock.sendall(msg.encode("utf-8"))
        except Exception:
            self._disconnect()

    def _on_zoom(self, _=None):
        v = round(self.zoom_var.get(), 2)
        self.lbl_zoom.config(text=f"{v:.1f}×")
        self._send({"cmd": "zoom", "value": v})

    def _set_zoom(self, v):
        self.zoom_var.set(v)
        self._on_zoom()

    def _on_scale(self, _=None):
        v = round(self.scale_var.get(), 2)
        self.lbl_scale.config(text=f"{int(v*100)}%")
        self._send({"cmd": "scale", "value": v})

    def _set_scale(self, v):
        self.scale_var.set(v)
        self._on_scale()

    def _pan(self, dx, dy):
        self.pan_x = round(max(-1.0, min(1.0, self.pan_x + dx)), 3)
        self.pan_y = round(max(-1.0, min(1.0, self.pan_y + dy)), 3)
        self._send({"cmd": "pan", "x": self.pan_x, "y": self.pan_y})

    def _reset_pan(self):
        self.pan_x = 0; self.pan_y = 0
        self._send({"cmd": "pan", "x": 0, "y": 0})

    def _rotate(self, deg):
        self.rotation = (self.rotation + deg) % 360
        self._send({"cmd": "rotate", "degrees": self.rotation})

    def _toggle_mirror(self):
        self.mirror_state = not self.mirror_state
        label = "⇆ Mirror ON" if self.mirror_state else "⇆ Mirror OFF"
        color = ACCENT if self.mirror_state else "#21262d"
        self.btn_mirror.config(text=label, bg=color)
        self._send({"cmd": "mirror", "enabled": self.mirror_state})


# ── entry point ───────────────────────────────────────────────────────────
def main():
    root = tk.Tk()
    app  = ConnectVcam(root)
    root.mainloop()


if __name__ == "__main__":
    main()
