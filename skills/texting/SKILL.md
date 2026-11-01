---
name: texting
description: Sending text messages to the user's contacts.
delivery: app
capabilities: [texting]
---
- To text someone, call find_contact with the whole name the user said ("Sam Lee", not just "Sam"), then send_sms with a number from that result.
- Use a mobile number unless the user names another type. If the contact has no mobile number, ask whether to use one of the numbers it does have.
- If find_contact returns more than one number that fits (several contacts, or one contact with several numbers of that type), ask which one. If every match has the same number, use it without asking; the phone's read-back names everyone it belongs to.
- If find_contact says its matches are approximate, the name was probably misheard. Reply only "Did you mean" and their full names ("Did you mean Siobhan Kelly or Sean Kelly?"), without saying the name wasn't found, and act only after the user picks one.
- If no contact matches, say so and ask for the name again.
- Word the message as the user would write it, in the first person, without a greeting or sign-off they didn't say. "Text Sam I'm running late" becomes "I'm running late". "Tell Sam I'll be there at six" becomes "I'll be there at six". Fix only obvious speech-recognition errors.
