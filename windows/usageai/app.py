"""App nella barra delle notifiche di Windows."""
from __future__ import annotations

import logging
import threading
import webbrowser
from datetime import datetime, timezone

from . import storage
from .api import ApiError, AuthError, ClaudeClient
from .core import evaluate, format_reset, pace_text, parse_usage
from .icon import render

USAGE_URL = "https://claude.ai/settings/usage"
log = logging.getLogger("usageai")


class UsageApp:
    def __init__(self, client_factory=ClaudeClient, tray=None):
        self.settings = storage.load_settings()
        self.state, self.org_id = storage.load_state()
        self.client_factory = client_factory
        self.windows = []
        self.error: str | None = None
        self.last_check: datetime | None = None
        self.auth_warned = False
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._settings_open = threading.Lock()
        self.tray = tray

    # ---------- controllo ----------

    def check_once(self) -> list:
        key = storage.get_session_key()
        if not key:
            self.error = "Manca il sessionKey: apri Impostazioni."
            return []
        now = datetime.now(timezone.utc)
        try:
            client = self.client_factory(key, org_id=self.org_id)
            data = client.usage()
            self.org_id = client.org_id
        except AuthError as e:
            self.error = str(e)
            if not self.auth_warned:
                self.auth_warned = True
                self.notify("UsageAI: sessione scaduta",
                            "Apri Impostazioni e incolla un nuovo sessionKey.")
            return []
        except ApiError as e:
            self.error = str(e)
            log.warning("controllo fallito: %s", e)
            return []

        self.auth_warned = False
        self.error = None
        self.last_check = now
        self.windows = parse_usage(data)
        alerts, self.state = evaluate(self.windows, self.settings, self.state, now)
        storage.save_state(self.state, self.org_id)
        for a in alerts:
            self.notify(a.title, a.text)
        return alerts

    def loop(self):
        while not self._stop.is_set():
            try:
                self.check_once()
            except Exception:
                log.exception("errore inatteso")
                self.error = "Errore inatteso: vedi usageai.log"
            self.refresh_tray()
            self._wake.wait(self.settings.interval_min * 60)
            self._wake.clear()

    def refresh_now(self, *_):
        self._wake.set()

    # ---------- interfaccia ----------

    def notify(self, title: str, text: str):
        log.info("notifica: %s | %s", title, text)
        if self.tray is not None:
            try:
                self.tray.notify(text, title)
            except Exception:
                log.exception("notifica non mostrata")

    def _window(self, key):
        return next((w for w in self.windows if w.key == key), None)

    def tooltip(self) -> str:
        if self.error and not self.windows:
            return f"UsageAI · {self.error}"[:127]
        parts = []
        for key, short in (("five_hour", "5h"), ("seven_day", "Sett.")):
            w = self._window(key)
            if w:
                parts.append(f"{short} {w.utilization:.0f}%")
        return ("UsageAI · " + " · ".join(parts))[:127] if parts else "UsageAI"

    def menu_lines(self) -> list[str]:
        now = datetime.now(timezone.utc)
        lines = []
        for w in self.windows:
            lines.append(f"{w.label}: {w.utilization:.0f}% · {format_reset(w.resets_at, now)}")
            if w.key == "seven_day":
                p = pace_text(w, now)
                if p:
                    lines.append(f"   Ritmo: {p}")
        if self.error:
            lines.append(f"⚠ {self.error}")
        if self.last_check:
            lines.append(f"Ultimo controllo: {self.last_check.astimezone():%H:%M}")
        return lines or ["Nessun dato ancora"]

    def refresh_tray(self):
        if self.tray is None:
            return
        week = self._window("seven_day")
        five = self._window("five_hour")
        worst = max((w.utilization for w in (week, five) if w), default=None)
        self.tray.icon = render(week.utilization if week else None, worst)
        self.tray.title = self.tooltip()
        try:
            self.tray.update_menu()
        except Exception:
            pass

    def open_settings(self, *_):
        threading.Thread(target=self._settings_thread, daemon=True).start()

    def _settings_thread(self):
        if not self._settings_open.acquire(blocking=False):
            return  # già aperta
        try:
            from .settings_ui import open_settings

            def on_save(new_settings, new_key, autostart):
                self.settings = new_settings
                storage.save_settings(new_settings)
                if new_key:
                    storage.set_session_key(new_key)
                    self.org_id = None
                try:
                    storage.set_autostart(autostart)
                except OSError:
                    log.exception("avvio automatico non impostato")
                self.refresh_now()

            open_settings(self.settings, storage.get_session_key() is not None,
                          storage.autostart_enabled(), on_save)
        finally:
            self._settings_open.release()

    def quit(self, *_):
        self._stop.set()
        self._wake.set()
        if self.tray is not None:
            self.tray.stop()


def run():
    import pystray

    logging.basicConfig(
        filename=storage.data_dir() / "usageai.log", level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )
    app = UsageApp()

    def dynamic_items():
        for line in app.menu_lines():
            yield pystray.MenuItem(line, None, enabled=False)
        yield pystray.Menu.SEPARATOR
        yield pystray.MenuItem("Aggiorna ora", app.refresh_now, default=True)
        yield pystray.MenuItem("Apri pagina Utilizzo", lambda *_: webbrowser.open(USAGE_URL))
        yield pystray.MenuItem("Impostazioni…", app.open_settings)
        yield pystray.MenuItem("Esci", app.quit)

    tray = pystray.Icon("UsageAI", render(None), "UsageAI", menu=pystray.Menu(dynamic_items))
    app.tray = tray

    def setup(icon):
        icon.visible = True
        if storage.get_session_key() is None:
            app.open_settings()
        threading.Thread(target=app.loop, daemon=True).start()

    tray.run(setup=setup)
