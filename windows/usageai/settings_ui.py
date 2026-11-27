"""Finestra Impostazioni (tkinter, incluso in Python): una scheda per gli avvisi e una per servizio."""
from __future__ import annotations

import tkinter as tk
from tkinter import messagebox, ttk

from .core import Settings, parse_thresholds


def open_settings(settings: Settings, providers, configured: dict, auto: dict, autostart: bool,
                  on_save, _autoclose_ms: int | None = None) -> None:
    """Blocca finché la finestra è aperta.

    on_save(settings, credentials, autostart): credentials = {id_servizio: valore nuovo, o None per rimuovere}
    con dentro solo i servizi modificati.
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

    def row(parent, r, label, value):
        ttk.Label(parent, text=label).grid(row=r, column=0, sticky="w", pady=3)
        var = tk.StringVar(value=value)
        ttk.Entry(parent, textvariable=var, width=16).grid(row=r, column=1, sticky="w", pady=3, padx=(8, 0))
        return var

    ts = row(alerts, 0, "Soglie sessioni brevi, es. 5 ore (%)", ", ".join(map(str, settings.thresholds_short)))
    tl = row(alerts, 1, "Soglie periodi lunghi, settimana o mese (%)", ", ".join(map(str, settings.thresholds_long)))
    ttk.Label(alerts, text="Valgono per tutti i servizi. Più soglie separate da virgola; vuoto = nessun avviso.",
              foreground="#666", wraplength=430).grid(row=2, column=0, columnspan=2, sticky="w")
    pace_var = tk.BooleanVar(value=settings.pace_enabled)
    ttk.Checkbutton(alerts, text="Avvisami se consumo un periodo lungo più veloce del ritmo costante",
                    variable=pace_var).grid(row=3, column=0, columnspan=2, sticky="w", pady=(10, 0))
    margin = row(alerts, 4, "…oltre quanti punti di anticipo", str(settings.pace_margin))
    interval = row(alerts, 5, "Controlla ogni (minuti)", str(settings.interval_min))
    auto_var = tk.BooleanVar(value=autostart)
    ttk.Checkbutton(alerts, text="Avvia con Windows", variable=auto_var).grid(
        row=6, column=0, columnspan=2, sticky="w", pady=(10, 0))

    # ---- una scheda per servizio ----
    cred_vars: dict[str, tk.StringVar] = {}
    remove_vars: dict[str, tk.BooleanVar] = {}
    for p in providers:
        tab = ttk.Frame(nb, padding=12)
        nb.add(tab, text=p.name + (" ✓" if configured.get(p.id) or auto.get(p.id) else ""))
        if configured.get(p.id):
            status = "Configurato. Lascia vuoto per non cambiarlo."
        elif auto.get(p.id):
            status = "Trovato da solo su questo PC: non serve fare nulla."
        else:
            status = "Non configurato: incolla la credenziale per attivarlo."
        ttk.Label(tab, text=status, foreground="#2e7d32" if (configured.get(p.id) or auto.get(p.id))
                  else "#666").grid(row=0, column=0, sticky="w")
        ttk.Label(tab, text=p.credential_label).grid(row=1, column=0, sticky="w", pady=(10, 0))
        var = tk.StringVar()
        ttk.Entry(tab, textvariable=var, show="•", width=56).grid(row=2, column=0, sticky="we", pady=2)
        cred_vars[p.id] = var
        ttk.Label(tab, text=p.help, foreground="#444", justify="left", wraplength=430).grid(
            row=3, column=0, sticky="w", pady=(6, 0))
        if configured.get(p.id):
            rv = tk.BooleanVar(value=False)
            ttk.Checkbutton(tab, text="Rimuovi questo servizio", variable=rv).grid(
                row=4, column=0, sticky="w", pady=(10, 0))
            remove_vars[p.id] = rv
        ttk.Label(tab, text="La credenziale resta nella Gestione credenziali di Windows e viene "
                            "mandata solo al sito del servizio.",
                  foreground="#888", wraplength=430).grid(row=5, column=0, sticky="w", pady=(10, 0))

    def save():
        try:
            new = Settings(
                thresholds_short=parse_thresholds(ts.get()),
                thresholds_long=parse_thresholds(tl.get()),
                pace_enabled=pace_var.get(),
                pace_margin=int(margin.get()),
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
        will_have_any = any(
            (configured.get(p.id) and creds.get(p.id, "keep") is not None) or auto.get(p.id) or creds.get(p.id)
            for p in providers)
        if not will_have_any:
            messagebox.showerror("Nessun servizio", "Configura almeno un servizio.", parent=root)
            return
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
