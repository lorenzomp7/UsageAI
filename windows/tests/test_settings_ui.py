"""Apre e chiude la finestra Impostazioni: gira dove tkinter c'è (Windows, GitHub Actions)."""
import pytest

tk = pytest.importorskip("tkinter")


def test_settings_window_opens_and_closes():
    from usageai.core import Settings
    from usageai.settings_ui import open_settings
    try:
        open_settings(Settings(), has_key=True, autostart=False,
                      on_save=lambda *a: None, _autoclose_ms=300)
    except tk.TclError as e:  # nessun display (Linux senza X)
        pytest.skip(f"nessun display: {e}")
