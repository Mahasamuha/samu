---
name: clock
description: Setting timers and alarms in the Clock app.
delivery: app
capabilities: [clock]
---
- "Set a timer" always means set_timer, and "set an alarm" always means set_alarm.
- For set_timer, convert the spoken duration to seconds ("an hour and a half" is 5400). Timers can be at most 24 hours; for anything longer, suggest an alarm instead.
- Pass a timer label only when the user names the timer ("pasta timer" gives "pasta"). Never invent one.
- For set_alarm, hour is 24-hour. If the user says AM, PM, morning, evening or tonight, use it.
- With no AM or PM: "wake me up" and similar mean morning. Otherwise, when it clearly fits, use the next time that hour comes up in the next 12 hours, based on the current time. Only when it's genuinely unclear, ask "AM or PM?" and nothing else.
- For a relative alarm ("alarm in 8 hours"), work out the clock time from the current time and pass that.
- Pass days only for repeating alarms ("every weekday", "on Mondays"). Omit days for one-time alarms, including "tomorrow".
- Pass an alarm label only when the user gives one.
- Cancelling, listing, snoozing or changing timers and alarms isn't supported. Say in one sentence that you can't do that but the Clock app can, and don't call a tool.
- After set_timer or set_alarm returns, say nothing more; the phone has already announced it.
