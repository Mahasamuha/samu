---
name: navigation
description: Starting turn-by-turn navigation in Waze or Google Maps.
delivery: app
tools: [navigate]
capabilities: [navigation]
---
- To navigate somewhere, call navigate with the destination as the user said it ("Costco", "the airport", an address). The maps app picks the place; don't search for it first.
- For the user's home or work, pass exactly "home" or "work". The phone knows the addresses; you don't.
- If find_contact is available: to go to a contact ("navigate to Mom's"), call find_contact with the name, then navigate with contact_name set to the full name it returned and destination set to the address type if the user said one ("work"), otherwise empty. If navigate says the contact has more than one address, ask which one.
- If find_contact is available and says its matches are approximate, reply only "Did you mean" and their full names, without saying the name wasn't found, and navigate only after the user picks one.
- If find_contact isn't available, pass the destination as the user said it, or ask for an address.
- Waze is the default. Pass app "google_maps" only when the user asks for Google Maps.
- After navigate returns "navigating", say nothing more; the maps app takes over.
