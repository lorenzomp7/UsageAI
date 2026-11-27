"""Finestra Impostazioni (tkinter, incluso in Python): una scheda per gli avvisi e una per servizio.

Ogni avviso e ogni servizio ha il suo interruttore.
"""
from __future__ import annotations

import tkinter as tk
from tkinter import messagebox, ttk

from .core import Settings, parse_thresholds


def open_settings(settings: Settings, providers, configured: dict, auto: dict, autostart: bool,
                  on_save, _autoclose_ms: int | None = None) -> None:
    """Blocca finché la finestra è aperta.

    on_save(settings, credentials, autostart): credentials = {id_servizio: valore nuovo, o None per rimuovere}
    con dentro solo i servizi modificati. I servizi spenti stanno in settings.disabled_providers.
    """
    root = tk.Tk()
    if _autoclose_ms:          # solo per il test automatico su GitHub Actions
        root.after(_autoclose_ms, root.destroy)
    root.title("UsageAI · Impostazioni")
    root.resizable(False, False)
    try:
        root.attributes("-topmost", True)
    except tk.TclError:
        pass

    outer = ttk.Frame(root, padding=10)
    outer.grid()
    nb = ttk.Notebook(outer)
    nb.grid(row=0, column=0, sticky="nsew")

    # ---- scheda Avvisi ----
    alerts = ttk.Frame(nb, padding=12)
    nb.add(alerts, text="Avvisi")
    r = 0

    def check(text, value, pad=8):
        nonlocal r
        var = tk.BooleanVar(value=value)
        ttk.Checkbutton(alerts, text=text, variable=var).grid(row=r, column=0, columnspan=2, sticky="w", pady=(pad, 0))
        r += 1
        return var

    def entry(label, value):
        nonlocal r
        ttk.Label(alerts, text=label).grid(row=r, column=0, sticky="w", pady=2, padx=(24, 0))
        var = tk.StringVar(value=value)
        ttk.Entry(alerts, textvariable=var, width=16).grid(row=r, column=1, sticky="w", pady=2, padx=(8, 0))
        r += 1
        return var

    def note(text):
        nonlocal r
        ttk.Label(alerts, text=text, foreground="#666", wraplength=440).grid(
            row=r, column=0, columnspan=2, sticky="w", padx=(24, 0))
        r += 1

    notif_var = check("Notifiche attive (interruttore generale)", settings.notifications_enabled, pad=0)
    short_var = check("Soglie delle sessioni brevi (es. 5 ore)", settings.short_enabled)
    ts = entry("Soglie (%)", ", ".join(map(str, settings.thresholds_short)))
    long_var = check("Soglie dei periodi lunghi (settimana o mese)", settings.long_enabled)
    tl = entry("Soglie (%)", ", ".join(map(str, settings.thresholds_long)))
    note("Più soglie separate da virgola, es. 50, 75, 90.")
    pace_var = check("Avviso sul ritmo: periodo lungo consumato più veloce del tempo passato", settings.pace_enabled)
    margin = entry("…oltre quanti punti di anticipo", str(settings.pace_margin))
    daily_var = check("Quota giornaliera: 100% / giorni del periodo (settimana = 14,29% al giorno)",
                      settings.daily_enabled)
    auth_var = check("Avviso quando l'accesso a un servizio scade", settings.auth_alerts_enabled)
    ttk.Separator(alerts).grid(row=r, column=0, columnspan=2, sticky="we", pady=10)
    r += 1
    ttk.Label(alerts, text="Controlla ogni (minuti)").grid(row=r, column=0, sticky="w")
    interval = tk.StringVar(value=str(settings.interval_min))
    ttk.Entry(alerts, textvariable=interval, width=16).grid(row=r, column=1, sticky="w", padx=(8, 0))
    r += 1
    auto_var = check("Avvia con Windows", autostart)

    # ---- una scheda per servizio ----
    cred_vars: dict[str, tk.StringVar] = {}
    remove_vars: dict[str, tk.BooleanVar] = {}
    enabled_vars: dict[str, tk.BooleanVar] = {}
    for p in providers:
        has = configured.get(p.id) or auto.get(p.id)
        on = p.id not in settings.disabled_providers
        tab = ttk.Frame(nb, padding=12)
        nb.add(tab, text=p.name + (" ✓" if has and on else (" ⏸" if has else "")))
        ev = tk.BooleanVar(value=on)
        ttk.Checkbutton(tab, text=f"Controlla {p.name}", variable=ev).grid(row=0, column=0, sticky="w")
        enabled_vars[p.id] = ev
        if configured.get(p.id):
            status = "Configurato. Lascia vuoto il campo per non cambiare la credenziale."
        elif auto.get(p.id):
            status = "Trovato da solo su questo PC: non serve fare nulla."
        else:
            status = "Non configurato: incolla la credenziale per attivarlo."
        ttk.Label(tab, text=status, foreground="#2e7d32" if has else "#666").grid(
            row=1, column=0, sticky="w", pady=(6, 0))
        ttk.Label(tab, text=p.credential_label).grid(row=2, column=0, sticky="w", pady=(10, 0))
        var = tk.StringVar()
        ttk.Entry(tab, textvariable=var, show="•", width=56).grid(row=3, column=0, sticky="we", pady=2)
        cred_vars[p.id] = var
        ttk.Label(tab, text=p.help, foreground="#444", justify="left", wraplength=430).grid(
            row=4, column=0, sticky="w", pady=(6, 0))
        if configured.get(p.id):
            rv = tk.BooleanVar(value=False)
            ttk.Checkbutton(tab, text="Cancella la credenziale salvata", variable=rv).grid(
                row=5, column=0, sticky="w", pady=(10, 0))
            remove_vars[p.id] = rv
        ttk.Label(tab, text="Togliendo la spunta in alto il servizio non viene più controllato, ma la credenziale "
                            "resta salvata. La credenziale sta nella Gestione credenziali di Windows e viene "
                            "mandata solo al sito del servizio.",
                  foreground="#888", wraplength=430).grid(row=6, column=0, sticky="w", pady=(10, 0))

    def save():
        try:
            new = Settings(
                notifications_enabled=notif_var.get(),
                short_enabled=short_var.get(),
                thresholds_short=parse_thresholds(ts.get()),
                long_enabled=long_var.get(),
                thresholds_long=parse_thresholds(tl.get()),
                pace_enabled=pace_var.get(),
                pace_margin=int(margin.get()),
                daily_enabled=daily_var.get(),
                auth_alerts_enabled=auth_var.get(),
                disabled_providers=sorted(pid for pid, v in enabled_vars.items() if not v.get()),
                interval_min=int(interval.get()),
            )
            if not 1 <= new.pace_margin <= 50:
                raise ValueError("Il margine sul ritmo va da 1 a 50 punti.")
            if not 1 <= new.interval_min <= 60:
                raise ValueError("L'intervallo va da 1 a 60 minuti.")
        except ValueError as e:
            msg = str(e) if "invalid literal" not in str(e) else "Usa solo numeri interi."
            messagebox.showerror("Valore non valido", msg, parent=root)
            return
        creds: dict[str, str | None] = {}
        for pid, var in cred_vars.items():
            value = var.get().strip()
            if value:
                creds[pid] = value
            elif pid in remove_vars and remove_vars[pid].get():
                creds[pid] = None
        on_save(new, creds, auto_var.get())
        root.destroy()

    btns = ttk.Frame(outer)
    btns.grid(row=1, column=0, sticky="e", pady=(10, 0))
    ttk.Button(btns, text="Annulla", command=root.destroy).grid(row=0, column=0, padx=4)
    ttk.Button(btns, text="Salva", command=save).grid(row=0, column=1)
    root.bind("<Return>", lambda _e: save())
    # Al primo avvio apro direttamente la scheda di Claude.
    if not any(configured.values()) and not any(auto.values()):
        nb.select(1)
    root.mainloop()
