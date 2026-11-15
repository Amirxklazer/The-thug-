14AI v3.1.0

1. Upload every file in this zip to the root of your GitHub repo (flat, no folders).
2. Move build.yml to .github/workflows/build.yml (same as before).
3. Run the Build APK workflow, download the app-debug-apk artifact, install.

What's new in 3.1.0
- New providers: OpenRouter, Mistral and Cerebras (plus Groq, Gemini, Qwen).
- Automatic fallback: when a provider is rate-limited, busy or its key is rejected, 14AI switches to the next provider with a key.
- Each API key field has a "Get a key" link and a paste button (Settings > API keys).
- Clearer messages: "Groq is rate limit, trying OpenRouter".

Where to get keys
- Groq: console.groq.com/keys (free, no card)
- OpenRouter: openrouter.ai/keys (free, no card)
- Mistral: console.mistral.ai/api-keys (free, phone check, no card)
- Gemini: aistudio.google.com/apikey
- Cerebras: cloud.cerebras.ai (needs a payment card)
