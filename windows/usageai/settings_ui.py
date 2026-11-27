"""Finestra Impostazioni (tkinter, incluso in Python: nessuna dipendenza in più)."""
from __future__ import annotations

import tkinter as tk
from tkinter import messagebox, ttk

from .core import Settings, parse_thresholds

HELP = (
    "Come trovare il sessionKey:\n"
    "1. Apri claude.ai in Chrome o Edge, già loggato.\n"
    "2. Premi F12 → scheda Application (Applicazione) → Cookies → https://claude.ai.\n"
    "3. Copia il valore di «sessionKey» (inizia con sk-ant-sid) e incollalo qui.\n"
    "Il valore resta nella Gestione credenziali di Windows di questo PC."
)


def open_settings(settings: Settings, has_key: bool, autostart: bool, on_save,
                  _autoclose_ms: int | None = None) -> None:
    """Blocca finché la finestra è aperta. on_save(settings, new_key_or_None, autostart)."""
    root = tk.Tk()
    if _autoclose_ms:          # solo per il test automatico su GitHub Actions
        root.after(_autoclose_ms, root.destroy)
    root.title("UsageAI · Impostazioni")
    root.resizable(False, False)
    try:
        root.attributes("-topmost", True)
    except tk.TclError:
        pass

    frm = ttk.Frame(root, padding=14)
    frm.grid()

    ttk.Label(frm, text="sessionKey di claude.ai").grid(row=0, column=0, sticky="w")
    key_var = tk.StringVar()
    key_entry = ttk.Entry(frm, textvariable=key_var, show="•", width=48)
    key_entry.grid(row=1, column=0, columnspan=2, sticky="we", pady=(2, 2))
    ttk.Label(frm, text=("Già salvato: lascia vuoto per non cambiarlo." if has_key
                         else "Obbligatorio al primo avvio."),
              foreground="#666").grid(row=2, column=0, columnspan=2, sticky="w")
    ttk.Label(frm, text=HELP, foreground="#444", justify="left", wraplength=420).grid(
        row=3, column=0, columnspan=2, sticky="w", pady=(6, 12))

    def row(r, label, value):
        ttk.Label(frm, text=label).grid(row=r, column=0, sticky="w", pady=3)
        var = tk.StringVar(value=value)
        ttk.Entry(frm, textvariable=var, width=16).grid(row=r, column=1, sticky="w", pady=3)
        return var

    t5 = row(4, "Soglie sessione 5 ore (%)", ", ".join(map(str, settings.thresholds_5h)))
    tw = row(5, "Soglie settimanali (%)", ", ".join(map(str, settings.thresholds_week)))
    ttk.Label(frm, text="Più soglie separate da virgola, es. 50, 75, 90. Vuoto = nessun avviso.",
              foreground="#666").grid(row=6, column=0, columnspan=2, sticky="w")

    pace_var = tk.BooleanVar(value=settings.pace_enabled)
    ttk.Checkbutton(frm, text="Avvisami se consumo la settimana più veloce del ritmo costante",
                    variable=pace_var).grid(row=7, column=0, columnspan=2, sticky="w", pady=(10, 0))
    margin = row(8, "…oltre quanti punti di anticipo", str(settings.pace_margin))
    interval = row(9, "Controlla ogni (minuti)", str(settings.interval_min))

    auto_var = tk.BooleanVar(value=autostart)
    ttk.Checkbutton(frm, text="Avvia con Windows", variable=auto_var).grid(
        row=10, column=0, columnspan=2, sticky="w", pady=(10, 0))

    def save():
        try:
            new = Settings(
                thresholds_5h=parse_thresholds(t5.get()),
                thresholds_week=parse_thresholds(tw.get()),
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
        key = key_var.get().strip() or None
        if not has_key and not key:
            messagebox.showerror("Manca il sessionKey", "Incolla il sessionKey per iniziare.",
                                 parent=root)
            return
        on_save(new, key, auto_var.get())
        root.destroy()

    btns = ttk.Frame(frm)
    btns.grid(row=11, column=0, columnspan=2, sticky="e", pady=(14, 0))
    ttk.Button(btns, text="Annulla", command=root.destroy).grid(row=0, column=0, padx=4)
    ttk.Button(btns, text="Salva", command=save).grid(row=0, column=1)
    root.bind("<Return>", lambda _e: save())
    key_entry.focus_set()
    root.mainloop()
