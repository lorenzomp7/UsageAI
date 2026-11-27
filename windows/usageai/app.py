"""App nella barra delle notifiche di Windows: controlla tutti i servizi configurati."""
from __future__ import annotations

import logging
import threading
import webbrowser
from datetime import datetime, timezone

from . import storage
from .core import Window, evaluate, format_reset, pace_text, today_text
from .icon import render
from .providers import PROVIDERS, ApiError, AuthError

USAGE_PAGES = {
    "claude": "https://claude.ai/settings/usage",
    "codex": "https://chatgpt.com/codex/settings/usage",
    "gemini": "https://gemini.google.com/usage",
    "cursor": "https://cursor.com/dashboard",
    "copilot": "https://github.com/settings/copilot",
    "perplexity": "https://www.perplexity.ai/account/usage",
    "grok": "https://grok.com",
    "mistral": "https://admin.mistral.ai/subscription",
    "openrouter": "https://openrouter.ai/activity",
}
log = logging.getLogger("usageai")


def _default_http():
    from curl_cffi import requests as creq   # impronta TLS di Chrome: passa i controlli anti-bot
    return creq.Session(impersonate="chrome")


class UsageApp:
    def __init__(self, providers=None, http=None, tray=None):
        self.providers = providers if providers is not None else PROVIDERS
        self.settings = storage.load_settings()
        self.state, self.caches = storage.load_state()
        self._http = http
        self.windows: dict[str, list[Window]] = {}
        self.errors: dict[str, str] = {}
        self.general_error: str | None = None
        self.last_check: datetime | None = None
        self.auth_warned: set[str] = set()
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._settings_open = threading.Lock()
        self.tray = tray

    @property
    def http(self):
        if self._http is None:
            self._http = _default_http()
        return self._http

    def active_providers(self):
        out = []
        for p in self.providers:
            if p.id in self.settings.disabled_providers:
                continue
            cred = storage.get_credential(p.id) or p.auto_credential()
            if cred:
                out.append((p, cred))
        return out

    # ---------- controllo ----------

    def check_once(self) -> list:
        active = self.active_providers()
        active_ids = {p.id for p, _ in active}
        for pid in list(self.windows):
            if pid not in active_ids:
                self.windows.pop(pid, None)
                self.errors.pop(pid, None)
        if not active:
            self.general_error = "Nessun servizio attivo: apri Impostazioni."
            return []
        self.general_error = None
        now = datetime.now(timezone.utc)
        fresh: list[Window] = []

        for p, cred in active:
            try:
                ws = p.fetch(cred, self.http, self.caches.setdefault(p.id, {}))
                self.windows[p.id] = ws
                fresh.extend(ws)
                self.errors.pop(p.id, None)
                self.auth_warned.discard(p.id)
            except AuthError as e:
                self.errors[p.id] = str(e)
                if p.id not in self.auth_warned:
                    self.auth_warned.add(p.id)
                    if self.settings.auth_alerts_enabled:
                        self.notify(f"UsageAI: accesso a {p.name} scaduto", str(e))
            except ApiError as e:
                self.errors[p.id] = str(e)
                log.warning("%s: %s", p.id, e)
            except Exception as e:
                self.errors[p.id] = f"Errore inatteso: {type(e).__name__}"
                log.exception("%s: errore inatteso", p.id)

        self.last_check = now
        alerts, self.state = evaluate(fresh, self.settings, self.state, now)
        storage.save_state(self.state, self.caches)
        for a in alerts:
            self.notify(a.title, a.text)
        return alerts

    def loop(self):
        while not self._stop.is_set():
            try:
                self.check_once()
            except Exception:
                log.exception("errore inatteso")
                self.general_error = "Errore inatteso: vedi usageai.log"
            self.refresh_tray()
            self._wake.wait(self.settings.interval_min * 60)
            self._wake.clear()

    def refresh_now(self, *_):
        self._wake.set()

    # ---------- interfaccia ----------

    def notify(self, title: str, text: str):
        log.info("notifica: %s | %s", title, text)
        if not self.settings.notifications_enabled:
            return
        if self.tray is not None:
            try:
                self.tray.notify(text, title)
            except Exception:
                log.exception("notifica non mostrata")

    def all_windows(self) -> list[Window]:
        """Solo le finestre con una percentuale (le righe informative restano fuori)."""
        return [w for p in self.providers for w in self.windows.get(p.id, []) if not w.is_info]

    def headline(self) -> tuple[float | None, float | None]:
        """(numero sull'icona, valore per il colore): il periodo lungo più consumato e il massimo assoluto."""
        ws = self.all_windows()
        if not ws:
            return None, None
        longs = [w.utilization for w in ws if w.is_long]
        worst = max(w.utilization for w in ws)
        return (max(longs) if longs else worst), worst

    def tooltip(self) -> str:
        parts = []
        for p in self.providers:
            ws = [w for w in self.windows.get(p.id, []) if not w.is_info]
            if ws:
                parts.append(f"{p.name} {max(w.utilization for w in ws):.0f}%")
            elif p.id in self.errors:
                parts.append(f"{p.name} ⚠")
        if not parts:
            return f"UsageAI · {self.general_error}"[:127] if self.general_error else "UsageAI"
        return ("UsageAI · " + " · ".join(parts))[:127]

    def menu_lines(self) -> list[str]:
        now = datetime.now(timezone.utc)
        lines = []
        for p in self.providers:
            ws = self.windows.get(p.id, [])
            err = self.errors.get(p.id)
            if not ws and not err:
                continue
            lines.append(p.name)
            for w in ws:
                if w.is_info:
                    lines.append(f"   {w.label}: {w.note}")
                    continue
                lines.append(f"   {w.label}: {w.utilization:.0f}% · {format_reset(w.resets_at, now)}")
                if w.is_long:
                    pt = pace_text(w, now)
                    if pt:
                        lines.append(f"      Ritmo: {pt}")
                    tt = today_text(w, self.state.get(w.key))
                    if tt:
                        lines.append(f"      Oggi: {tt.removeprefix('oggi ')}")
            if err:
                lines.append(f"   ⚠ {err}")
        if self.general_error:
            lines.append(f"⚠ {self.general_error}")
        if self.last_check:
            lines.append(f"Ultimo controllo: {self.last_check.astimezone():%H:%M}")
        return lines or ["Nessun dato ancora"]

    def refresh_tray(self):
        if self.tray is None:
            return
        number, worst = self.headline()
        self.tray.icon = render(number, worst)
        self.tray.title = self.tooltip()
        try:
            self.tray.update_menu()
        except Exception:
            pass

    def toggle_notifications(self, *_):
        self.settings.notifications_enabled = not self.settings.notifications_enabled
        storage.save_settings(self.settings)

    def open_settings(self, *_):
        threading.Thread(target=self._settings_thread, daemon=True).start()

    def _settings_thread(self):
        if not self._settings_open.acquire(blocking=False):
            return  # già aperta
        try:
            from .settings_ui import open_settings

            def on_save(new_settings, credentials: dict, autostart: bool):
                self.settings = new_settings
                storage.save_settings(new_settings)
                for pid, value in credentials.items():
                    storage.set_credential(pid, value)   # None = rimuovi
                    self.caches.pop(pid, None)
                    self.auth_warned.discard(pid)
                try:
                    storage.set_autostart(autostart)
                except OSError:
                    log.exception("avvio automatico non impostato")
                self.refresh_now()

            configured = {p.id: storage.get_credential(p.id) is not None for p in self.providers}
            auto = {p.id: p.auto_credential() is not None for p in self.providers}
            open_settings(self.settings, self.providers, configured, auto,
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

    def open_page(pid):
        return lambda *_: webbrowser.open(USAGE_PAGES[pid])

    def dynamic_items():
        for line in app.menu_lines():
            yield pystray.MenuItem(line, None, enabled=False)
        yield pystray.Menu.SEPARATOR
        yield pystray.MenuItem("Aggiorna ora", app.refresh_now, default=True)
        yield pystray.MenuItem("Notifiche attive", app.toggle_notifications,
                               checked=lambda _item: app.settings.notifications_enabled)
        pages = [pystray.MenuItem(p.name, open_page(p.id)) for p in app.providers if p.id in USAGE_PAGES]
        yield pystray.MenuItem("Apri pagina consumo", pystray.Menu(*pages))
        yield pystray.MenuItem("Impostazioni…", app.open_settings)
        yield pystray.MenuItem("Esci", app.quit)

    tray = pystray.Icon("UsageAI", render(None), "UsageAI", menu=pystray.Menu(dynamic_items))
    app.tray = tray

    def setup(icon):
        icon.visible = True
        if not app.active_providers():
            app.open_settings()
        threading.Thread(target=app.loop, daemon=True).start()

    tray.run(setup=setup)
