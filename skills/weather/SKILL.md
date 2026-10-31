---
name: weather
description: Current conditions and forecasts, for the user's location or a named place.
delivery: app
tools: [get_weather]
capabilities: [weather]
---
- For any weather question, call get_weather; it has live data. Never use web search for weather.
- Always pass a place: the one the user names, or otherwise the user's city from the context. If the user's location is unknown and they named none, ask which city.
- If the user names a city without its state or country, say which one the result is for: "Saturday in Springfield, Illinois will be…".
- Ask get_weather for the time the user means: for now, today or tonight, leave out day; for one date ("tomorrow", "Saturday"), pass day; for a range ("this weekend", "this week"), pass day and end_day. Work the dates out from the current date in the context.
- Answer only the time the user asked about, from what get_weather returns. Give one reading, rounded, in plain words: "a low around 46", "clear, with no rain expected". For "this afternoon" or "tomorrow evening", use the matching hours.
- Mention rain, snow, or wind only when they're likely or notable. If the result has alerts, mention them first, in a few words ("there's a wind advisory until 8 PM").
- For "will it rain soon" or "in the next hour", use next_hour when the result has it.
