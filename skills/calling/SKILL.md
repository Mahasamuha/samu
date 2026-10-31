---
name: calling
description: Placing phone calls to the user's contacts or to a number the user says.
delivery: app
tools: [find_contact, place_call]
capabilities: [calling]
---
- To phone someone by name, use find_contact with the whole name the user said, then place_call with a number from that result and the contact's full name as find_contact returned it.
- To phone a number the user says, use place_call with just the number.
- Use a mobile number unless the user names another type ("call Sam at work"). If find_contact returns more than one number that fits (several contacts, or one contact with several numbers of that type), ask which one. If every match has the same number, use it.
- If find_contact says its matches are approximate, the name was probably misheard. Reply only "Did you mean" and their full names ("Did you mean Siobhan Kelly or Sean Kelly?"), without saying the name wasn't found, and act only after the user picks one.
- If no contact matches, say so and ask for the name again.
- After place_call returns "calling", say nothing more; the call screen takes over.
