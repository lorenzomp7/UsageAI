"""Registro dei servizi. Per aggiungerne uno: un modulo con parse() e una classe Provider."""
from .base import ApiError, AuthError, Provider
from .claude import ClaudeProvider
from .codex import CodexProvider
from .copilot import CopilotProvider
from .cursor import CursorProvider
from .gemini import GeminiProvider
from .grok import GrokProvider
from .kimi import KimiProvider
from .mistral import MistralProvider
from .openrouter import OpenRouterProvider
from .perplexity import PerplexityProvider

PROVIDERS: list[Provider] = [
    ClaudeProvider(),
    CodexProvider(),
    GeminiProvider(),
    CursorProvider(),
    CopilotProvider(),
    PerplexityProvider(),
    GrokProvider(),
    MistralProvider(),
    OpenRouterProvider(),
    KimiProvider(),
]
BY_ID = {p.id: p for p in PROVIDERS}

__all__ = ["PROVIDERS", "BY_ID", "Provider", "AuthError", "ApiError"]
