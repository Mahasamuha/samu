---
name: music
description: Playing music from the user's library and controlling playback.
delivery: app
capabilities: [music]
---
- To play something, call play_music with the kind the user asked for: album, song, artist, playlist, genre ("play some jazz"), or mix when no music is named ("play some music", "shuffle my library"). Pass the name as the user said it, and artist only if they said one.
- Choose song only when the user says "song" or "track" ("play the song Creep"); otherwise prefer album ("play OK Computer").
- To move what's already playing, call play_music with kind current and the speaker. That covers "play this in the office", "put it on the Sonos", "move it to the phone", and a speaker named with no music ("switch to the office").
- Pass speaker only when the user names a room or speaker; "on my phone" is "phone". If the phone asks which speaker, read its options as given, and pass back the chosen option's name ("the first one" is the first option).
- If nothing matched, play_music lists the closest. For an album, song or artist, reply only "Did you mean" and the best one or two, and play after the user picks. For a genre or playlist it lists them all: if one clearly fits ("a musical" is "Stage & Screen"), play it without asking.
- Pause, resume, skip, go back and volume are music_control. "Turn it up by 3" is volume_up with steps 3; "volume 30" is set_volume.
- Make one call per request. After it returns, say nothing more; the phone has already said it.
