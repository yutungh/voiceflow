# Reading the script (about 10 minutes)

You read 40 short sentences, each as one normal VoiceFlow dictation. A script
then picks the recordings out of VoiceFlow's own history. Nothing else to set
up.

## Before you start

1. Make sure VoiceFlow is running as usual. Its "Keep audio" setting must not
   be Off (the default, 7 days, is fine).
2. Open Notepad (or any empty text box). What VoiceFlow types there doesn't
   matter and can be thrown away.
3. Note the time you start (or tell Claude "starting now").

## For each sentence

- Start a dictation, read the sentence once at your normal pace, stop.
- One sentence per dictation. Don't say the number.
- Fluffed it? Just dictate the same sentence again. The later one is kept.
- Skipped one or lost your place? Carry on; the script lists what's missing.
- The names are made up. Say them as shown in the right-hand column.

| # | Sentence | Say it like |
|---:|---|---|
| 1 | Please leave the package beside the front door. | |
| 2 | Can you ask Bepra to send the slides before the meeting? | Bepra = BEE-pruh |
| 3 | This morning Docker would not start the container. | |
| 4 | The cluster runs on Kubernetes now. | koo-ber-NET-eez |
| 5 | I think Mirva already booked the room for Friday. | Mirva = MEER-vuh |
| 6 | The blue cable is in the top drawer. | |
| 7 | Please light the candle on the dining table. | |
| 8 | Could you check whether Zoravyn has the spare key? | Zoravyn = ZOR-uh-vin |
| 9 | I keep all my notes in Obsidian on the laptop. | |
| 10 | I read the reply in Beeper on my laptop. | Beeper = BEE-per (the chat app) |
| 11 | Tell Harbra the parcel arrived this afternoon. | Harbra = HAR-bruh |
| 12 | We moved the meeting to Friday afternoon. | |
| 13 | Please remind Dockra that the train leaves early tomorrow. | Dockra = DOCK-ruh |
| 14 | We sketched the plan on a Miro board. | Miro = MEER-oh (the whiteboard app) |
| 15 | I asked Kavren to water the plants while we are away. | Kavren = KAV-ren |
| 16 | The office printer is only reachable over Tailscale. | |
| 17 | I need another copy of the revised page. | |
| 18 | The boats came back to the harbor before the storm. | |
| 19 | Yesterday Kandrel said she would call back after lunch. | Kandrel = KAN-drul |
| 20 | I had coffee with Bepra and we talked about the trip. | Bepra = BEE-pruh |
| 21 | The kettle should be ready in a minute. | |
| 22 | All my chats are in Beeper, so I only check one app. | Beeper = BEE-per |
| 23 | I need to update the Kubernetes settings before the release. | |
| 24 | The flowers are from Zoravyn and her sister. | Zoravyn = ZOR-uh-vin |
| 25 | I rebuilt the image and restarted Docker. | |
| 26 | That window faces the quiet side street. | |
| 27 | We should invite Mirva to the planning call. | Mirva = MEER-vuh |
| 28 | Can you open the daily page in Obsidian? | |
| 29 | The candle burned all evening in the living room. | |
| 30 | My neighbor Harbra lent us a ladder for the weekend. | Harbra = HAR-bruh |
| 31 | Please keep the receipt until next week. | |
| 32 | The new designer, Dockra, starts on Monday. | Dockra = DOCK-ruh |
| 33 | Please share the Miro link with the whole team. | Miro = MEER-oh |
| 34 | I turned on Tailscale so the laptop can reach the server. | |
| 35 | The train was late again this morning. | |
| 36 | We met Kavren at the station last night. | Kavren = KAV-ren |
| 37 | We walked along the harbor after dinner. | |
| 38 | I saved the image in the shared folder. | |
| 39 | I sent the invoice to Kandrel this morning. | Kandrel = KAN-drul |
| 40 | We can review the changes after lunch. | |

## When you're done

Tell Claude the time you started. It runs
`bun collect.ts --since <start time>`, which copies only the matching
recordings to `C:\vf\vocab-test\recordings\` (outside the repository) and says
if any sentence is still missing. Your recordings are never committed.
