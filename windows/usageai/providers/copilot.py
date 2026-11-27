"""GitHub Copilot: crediti AI o richieste premium del mese (api.github.com/copilot_internal/user).

Dal 1 giugno 2026 GitHub conta i crediti AI. Campi dalla documentazione e dai test di CodexBar
(github.com/steipete/CodexBar, MIT): token_based_billing, credits_used nelle quota_snapshots,
monthly_quotas / limited_user_quotas quando le quote della risposta sono segnaposto a zero.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from ..core import Window, month_before, parse_datetime
from .base import ApiError, Provider, number, parse_json, request

URL = "https://api.github.com/copilot_internal/user"


def _reset(value) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    dt = parse_datetime(value)
    if dt is None:
        try:
            dt = datetime.strptime(value.strip()[:10], "%Y-%m-%d").replace(tzinfo=timezone.utc)
        except ValueError:
            return None
    return dt


def _month_length(reset: datetime | None) -> timedelta:
    return reset - month_before(reset) if reset else timedelta(days=30)


def _fmt(n: float) -> str:
    return f"{n:.0f}" if n == int(n) else f"{n:.1f}".replace(".", ",")


def parse(data: dict) -> list[Window]:
    if not isinstance(data, dict):
        raise ApiError("Risposta di Copilot cambiata.")
    snaps = data.get("quota_snapshots") or data.get("quotaSnapshots") or {}
    reset = _reset(data.get("quota_reset_date") or data.get("quotaResetDate"))
    length = _month_length(reset)
    credits = data.get("token_based_billing") is True
    out: list[Window] = []
    notes: list[Window] = []
    for key, label in (("premium_interactions", "Crediti AI del piano" if credits else "Richieste premium"),
                       ("chat", "Chat")):
        snap = snaps.get(key)
        if not isinstance(snap, dict):
            continue
        ent, rem = number(snap.get("entitlement")), number(snap.get("remaining"))
        used_credits = number(snap.get("credits_used"))
        if key == "premium_interactions" and used_credits is not None and (credits or used_credits > 0):
            text = f"{_fmt(used_credits)} usati" + (f" su {_fmt(ent)} compresi" if ent and ent > 0 else "")
            notes.append(Window("copilot", "copilot:credits_used", "Crediti AI", 0.0, None, None, note=text))
            if ent and ent > 0 and used_credits > ent:
                notes.append(Window("copilot", "copilot:overage", "Uso aggiuntivo in corso", 0.0, None, None,
                                    note=f"{_fmt(used_credits - ent)} crediti oltre i compresi"
                                         + (f", fino al {reset.astimezone():%d/%m}" if reset else "")))
        if snap.get("unlimited"):
            continue
        # Con i crediti AI GitHub può mandare quote finte a zero: non sono un consumo.
        if credits and ent == 0 and (rem in (None, 0)):
            continue
        remaining_pct = number(snap.get("percent_remaining"))
        if remaining_pct is None:
            if not ent or rem is None:
                continue
            remaining_pct = rem / ent * 100
        out.append(Window("copilot", f"copilot:{key}", label,
                          max(0.0, 100.0 - remaining_pct), reset, length))
    if not out:
        # Riserva: quote del mese e quanto ne resta (monthly_quotas / limited_user_quotas).
        monthly, left = data.get("monthly_quotas"), data.get("limited_user_quotas")
        if isinstance(monthly, dict) and isinstance(left, dict):
            for key in ("premium_interactions", "completions", "chat"):
                total, rest = number(monthly.get(key)), number(left.get(key))
                if total and total > 0 and rest is not None:
                    out.append(Window("copilot", "copilot:monthly", "Quota del mese",
                                      max(0.0, min(100.0, (total - rest) / total * 100)), reset, length))
                    break
    if not out and not notes:
        raise ApiError("Copilot non riporta limiti per questo account (piano illimitato o senza Copilot).")
    return out + notes


class CopilotProvider(Provider):
    id = "copilot"
    name = "Copilot"
    credential_label = "Token GitHub"
    help = ("Il modo più semplice: installa GitHub CLI, esegui «gh auth login» e poi «gh auth token», "
            "e incolla qui il token che stampa.")

    def fetch(self, credential: str, http, cache: dict) -> list[Window]:
        headers = {
            "Authorization": f"token {credential.strip()}",
            "Accept": "application/json",
            "X-Github-Api-Version": "2025-04-01",
        }
        try:
            _, text = request(http, URL, headers)
        except ApiError as e:
            if e.status == 404:
                raise ApiError("Questo account GitHub non ha Copilot, o il token non è accettato.") from e
            raise
        return parse(parse_json(text))
