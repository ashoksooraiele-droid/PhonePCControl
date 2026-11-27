import asyncio
import json
import socket
import threading
import tkinter as tk
from pynput.keyboard import Controller as KeyController, Key
from pynput.mouse import Controller as MouseController, Button
import numpy as np

try:
    import vgamepad as vg
    gamepad = vg.VX360Gamepad()
except Exception:
    gamepad = None

try:
    import sounddevice as sd
except Exception:
    sd = None

import websockets

keyboard = KeyController()
mouse = MouseController()

SPECIAL_KEYS = {
    "ENTER": Key.enter,
    "BACKSPACE": Key.backspace,
    "TAB": Key.tab,
    "ESC": Key.esc,
    "SPACE": Key.space,
    "UP": Key.up,
    "DOWN": Key.down,
    "LEFT": Key.left,
    "RIGHT": Key.right,
    "DELETE": Key.delete,
    "HOME": Key.home,
    "END": Key.end,
}

MODIFIER_KEYS = {
    "SHIFT": Key.shift,
    "CTRL": Key.ctrl,
    "WIN": Key.cmd,
    "FN": Key.alt,  # Windows doesn't expose hardware Fn; mapped to Alt for shortcuts
}

GAMEPAD_BUTTONS = {}
if gamepad:
    GAMEPAD_BUTTONS = {
        "A": vg.XUSB_BUTTON.XUSB_GAMEPAD_A,
        "B": vg.XUSB_BUTTON.XUSB_GAMEPAD_B,
        "X": vg.XUSB_BUTTON.XUSB_GAMEPAD_X,
        "Y": vg.XUSB_BUTTON.XUSB_GAMEPAD_Y,
        "LB": vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,
        "RB": vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER,
        "START": vg.XUSB_BUTTON.XUSB_GAMEPAD_START,
        "BACK": vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
        "DPAD_UP": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,
        "DPAD_DOWN": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN,
        "DPAD_LEFT": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT,
        "DPAD_RIGHT": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT,
        "L3": vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB,
        "R3": vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB,
    }

active_modifiers = set()
left_hold = False
audio_stream = None
status_callback = None


def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def setup_audio_output():
    global audio_stream
    if sd is None:
        return
    try:
        devices = sd.query_devices()
        target_dev = None
        for idx, dev in enumerate(devices):
            if "CABLE Input" in dev["name"] and dev["max_output_channels"] > 0:
                target_dev = idx
                break
        if target_dev is not None:
            audio_stream = sd.OutputStream(
                device=target_dev, samplerate=16000, channels=1, dtype="int16"
            )
            audio_stream.start()
    except Exception:
        audio_stream = None


def apply_modifiers_press():
    for mod in list(active_modifiers):
        key_obj = MODIFIER_KEYS.get(mod)
        if key_obj:
            keyboard.press(key_obj)


def release_all_modifiers():
    for mod in list(active_modifiers):
        key_obj = MODIFIER_KEYS.get(mod)
        if key_obj:
            keyboard.release(key_obj)
    active_modifiers.clear()


def handle_message(raw_msg):
    global left_hold

    if isinstance(raw_msg, bytes):
        if audio_stream is not None:
            try:
                samples = np.frombuffer(raw_msg, dtype=np.int16)
                audio_stream.write(samples)
            except Exception:
                pass
        return

    try:
        data = json.loads(raw_msg)
    except Exception:
        return

    msg_type = data.get("type")

    if msg_type == "modifier":
        mod = data.get("key", "").upper()
        active = data.get("active", False)
        if active:
            active_modifiers.add(mod)
        else:
            active_modifiers.discard(mod)

    elif msg_type == "key":
        key_val = data.get("key", "")
        apply_modifiers_press()
        upper_val = key_val.upper()
        if upper_val in SPECIAL_KEYS:
            k = SPECIAL_KEYS[upper_val]
            keyboard.press(k)
            keyboard.release(k)
        elif len(key_val) == 1:
            if active_modifiers:
                keyboard.press(key_val.lower())
                keyboard.release(key_val.lower())
            else:
                keyboard.type(key_val)
        release_all_modifiers()

    elif msg_type == "mouse_move":
        dx = float(data.get("dx", 0))
        dy = float(data.get("dy", 0))
        mouse.move(int(dx), int(dy))

    elif msg_type == "mouse_scroll":
        dy = float(data.get("dy", 0))
        mouse.scroll(0, int(dy))

    elif msg_type == "mouse_click":
        btn_name = data.get("button", "left")
        action = data.get("action", "click")
        apply_modifiers_press()

        if btn_name == "left":
            if action == "hold_on":
                if not left_hold:
                    mouse.press(Button.left)
                    left_hold = True
            elif action == "hold_off":
                if left_hold:
                    mouse.release(Button.left)
                    left_hold = False
            elif action == "double":
                mouse.click(Button.left, 2)
            else:
                mouse.click(Button.left, 1)
        elif btn_name == "right":
            if action == "double":
                mouse.click(Button.right, 2)
            else:
                mouse.click(Button.right, 1)

        release_all_modifiers()

    elif msg_type == "gamepad" and gamepad is not None:
        sub = data.get("sub")
        if sub == "button":
            btn = GAMEPAD_BUTTONS.get(data.get("button", "").upper())
            pressed = data.get("pressed", False)
            if btn:
                if pressed:
                    gamepad.press_button(button=btn)
                else:
                    gamepad.release_button(button=btn)
                gamepad.update()
        elif sub == "stick":
            stick = data.get("stick", "left")
            x = float(data.get("x", 0.0))
            y = float(data.get("y", 0.0))
            if stick == "left":
                gamepad.left_joystick_float(x_value_float=x, y_value_float=-y)
            else:
                gamepad.right_joystick_float(x_value_float=x, y_value_float=-y)
            gamepad.update()
        elif sub == "trigger":
            side = data.get("side", "left")
            val = float(data.get("value", 0.0))
            if side == "left":
                gamepad.left_trigger_float(value_float=val)
            else:
                gamepad.right_trigger_float(value_float=val)
            gamepad.update()


async def ws_handler(websocket):
    if status_callback:
        status_callback(True)
    try:
        async for message in websocket:
            handle_message(message)
    except Exception:
        pass
    finally:
        if status_callback:
            status_callback(False)


async def run_server():
    setup_audio_output()
    async with websockets.serve(ws_handler, "0.0.0.0", 8765, max_size=None):
        await asyncio.Future()


def start_ws_thread():
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    loop.run_until_complete(run_server())


def launch_ui():
    global status_callback

    root = tk.Tk()
    root.title("Phone to PC Control")
    root.geometry("380x270")
    root.configure(bg="#090D16")
    root.resizable(False, False)

    try:
        root.iconbitmap("app_icon.ico")
    except Exception:
        pass

    title_lbl = tk.Label(
        root, text="Phone to PC Control", font=("Segoe UI", 16, "bold"),
        fg="#FFFFFF", bg="#090D16"
    )
    title_lbl.pack(pady=(20, 8))

    ip_text = f"PC Wi-Fi IP: {get_local_ip()}  |  Port: 8765"
    ip_lbl = tk.Label(
        root, text=ip_text, font=("Segoe UI", 10),
        fg="#94A3B8", bg="#111827", padx=12, pady=6
    )
    ip_lbl.pack(pady=8)

    status_var = tk.StringVar(value="Not Connected")
    status_lbl = tk.Label(
        root, textvariable=status_var, font=("Segoe UI", 13, "bold"),
        fg="#7C3AED", bg="#090D16"
    )
    status_lbl.pack(pady=14)

    hint_lbl = tk.Label(
        root,
        text="USB: Run 'adb reverse tcp:8765 tcp:8765'\nMic: Install VB-CABLE for virtual PC mic",
        font=("Segoe UI", 9), fg="#64748B", bg="#090D16"
    )
    hint_lbl.pack(pady=(4, 12))

    def update_status(connected):
        if connected:
            status_var.set("Connected")
            status_lbl.configure(fg="#2563EB")
        else:
            status_var.set("Not Connected")
            status_lbl.configure(fg="#7C3AED")

    status_callback = lambda c: root.after(0, update_status, c)

    threading.Thread(target=start_ws_thread, daemon=True).start()
    root.mainloop()


if __name__ == "__main__":
    launch_ui()