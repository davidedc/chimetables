# Learning design

Why the sessions are short, why the player calls her rather than waiting, and what the evidence
says. Written for a (fairly bright) 10-year-old learning or polishing the tables up to 12 × 12.

## What a day looks like

- **Four short sessions** at set times (default 07:45, 12:30, 16:00, 18:00; ours starts at 08:30),
  never in quiet hours (19:30–07:00).
- **5 cards per session.** A missed card comes back two cards later, up to three times, so a
  session is 5 to about 9 answers. `7 × 8` and `8 × 7` are separate cards but never in the same
  session, so the second can't be answered from short-term memory.
- **About 30–60 seconds per session**, so a few minutes a day at most. Each answer takes a few
  seconds; a right answer pauses 0.7 s, a wrong one 2.8 s to read the correct answer; the end
  screen stays 6–10 s.
- **New cards per day:** 5 by default. We use 15 for a child who already knows the easy tables:
  all 121 cards are met in about 9 days, and known facts move out of the way after one quick answer.
- **Easiest tables first:** 2, 10, 5, 3, 4, 11, 9, 6, 7, 8, 12.

## How answers are graded

The child never rates herself and never sees a timer. The app times from the question appearing to
her **first key press**, so slow typing doesn't count:

| Answer | Grade | Next time |
|---|---|---|
| right within 3 s | Easy | a new card: in 3 days |
| right, 3–10 s | Good | a new card: in 2 hours, then the next day |
| right after 10 s | Hard | the interval grows only a little; not automatic yet |
| wrong | Again | shown the right answer, asked again in this session, then the next day |

A card missed earlier the same day counts as Good at best, even in a later session: right after
seeing the answer isn't "easy".

After that the gaps grow about 2.5× each time (1 → 3 → 8 → 20 days), capped at 60 days.
A card forgotten 4 times becomes "tricky" and is reviewed at least every 14 days until it sticks.
The details are in [the scheduler](../README.md#the-scheduler-child-tuned-sm-2).

## Playing outside the schedule

- Pressing the power button shows **"Slide to play"** whenever something is due. Playing early
  uses the same due cards and the same daily allowance of new cards, so it moves practice earlier
  rather than adding more. A later wake-up then finds less to do, or stays silent.
- **Missed wake-ups cost nothing.** The cards wait, sessions never grow beyond 5, and due cards
  always come before new ones, so if she falls behind, new cards slow down by themselves.
- When nothing is due, the slider offers a **bonus round**: up to 5 cards she has already met,
  drawn at random from her least established ones. It is pure practice. It doesn't change the
  schedule and earns no streak days, badges or stickers (a great round still gets confetti), so
  there's nothing to farm.
- **Quiet hours block playing by hand too**, so it can't become a bedtime game.
- **No restarting for a clean slate.** On her first day she found that turning the screen off
  after a mistake and sliding again gave a fresh session, and with it "Perfect!" and a sticker.
  Now a session left unfinished carries on ("Slide to carry on") if she comes back within
  10 minutes, mistakes included.

## Rewards

- A sticker and confetti for a session with at least 80% right first time, or for a new badge.
  Only the first such sticker each day is a new one for her collection; later ones re-show stickers
  she has. Without that limit she collected 7 of 9 on her first day.
- **Completing the first set unlocks something, not a prize.** She wanted to know what happens at
  9 of 9. The answer is riddles, plus more stickers, rather than a present: the reward stays
  inside the app, and nothing real is tied to how she does. After the unlock no total is shown, so
  "collecting them all" stops being a finish line. New extra stickers come at most every 3 days,
  because the daily treat is now the riddle.
- Badges for streaks, perfect sessions, mornings, answering every call in a day, runs of quick
  answers, beating a tricky card, totals, and mastering each table.
- A streak counts days with at least one finished session, and isn't broken until a whole day is missed.
- Only happy sounds: there is no "wrong" buzzer.

## Riddles

- **One a day, at the first finished session**, whenever that is (bonus rounds count), so it works
  the same on school days, weekends and holidays. She gets it for finishing, whatever her score:
  a bad round never costs her the riddle.
- **The answer comes the next day.** Her first session the next morning turns over yesterday's
  card, then deals a new one under a wax seal. A day without playing costs nothing: the riddle
  waits, and the answer comes at her next session. The morning chime screen says "🧩 Your riddle
  answer is waiting!", a small pull towards answering the first call.
- **She is never asked for her answer.** Riddles are a gift, not another test; she types answers
  all day already. Talking about them at dinner is the point.
- **About a third are number riddles she can work out in her head:** "Multiply me by myself, add
  me, and you get 42", chickens and cows by heads and legs, 37 × 3 = 111 so what's 37 × 6? The
  back of the card often has the trick or a bit of history ("a boy called Gauss…").
- **Nothing can be skipped by tapping.** On the explainer screens Next only appears after time to
  read; on a riddle card it only appears once she has opened the seal or turned the card over, and
  a card can't be turned until she has had a few seconds with the question again.
- **Her book** shows her stickers, all badges (and what is still to find), a map of the tables
  that turns gold as facts settle, and every riddle card so far. It's look-only, closed during
  quiet hours, and sits behind the same "Slide to open" as playing: the slide turns into a big Play
  button (on the right, under a right-handed thumb) and a smaller 📒 Book button. One slider keeps
  the screen calm and keeps playing the obvious choice.

## Why it's built this way

- **Retrieval beats re-reading.** Recalling an answer strengthens memory more than seeing it again
  (Roediger & Karpicke 2006). Every card asks first and only shows the answer after a miss.
- **Spaced beats massed.** The same amount of practice spread over days is remembered far longer
  than practice crammed together (Cepeda et al. 2006). Hence several tiny sessions a day, and
  intervals that grow as a fact settles.
- **Successive relearning.** Practising to a correct answer within a session, then relearning
  across sessions, is one of the best-supported study techniques (Rawson & Dunlosky 2011;
  Dunlosky et al. 2013). That is the "wrong → again in this session → again tomorrow" loop.
- **Fluency means fast recall.** Times tables are useful once they're automatic rather than worked
  out, so speed shapes the schedule. The 3-second "Easy" threshold matches the usual definition of
  automatic recall; England's Year 4 Multiplication Tables Check allows 6 seconds per question.
- **No visible time pressure.** Timed tests are linked to maths anxiety in some children
  (Boaler 2014), so speed only decides when a card comes back and is never shown to her.
- **How much is enough.** For children who are behind, US guidance suggests about 10 minutes of
  fact-fluency practice per session (Gersten et al. 2009). A child who is on track needs less;
  a few minutes a day, little and often, is the usual advice.

## References

- Cepeda, N. J., Pashler, H., Vul, E., Wixted, J. T., & Rohrer, D. (2006). Distributed practice in
  verbal recall tasks: A review and quantitative synthesis. *Psychological Bulletin*, 132(3), 354–380.
- Roediger, H. L., & Karpicke, J. D. (2006). Test-enhanced learning: Taking memory tests improves
  long-term retention. *Psychological Science*, 17(3), 249–255.
- Rawson, K. A., & Dunlosky, J. (2011). Optimizing schedules of retrieval practice for durable and
  efficient learning: How much is enough? *Journal of Experimental Psychology: General*, 140(3), 283–302.
- Dunlosky, J., Rawson, K. A., Marsh, E. J., Nathan, M. J., & Willingham, D. T. (2013). Improving
  students' learning with effective learning techniques. *Psychological Science in the Public
  Interest*, 14(1), 4–58.
- Boaler, J. (2014). Research suggests that timed tests cause math anxiety. *Teaching Children
  Mathematics*, 20(8), 469–474.
- Gersten, R., et al. (2009). *Assisting Students Struggling with Mathematics: Response to
  Intervention (RtI) for Elementary and Middle Schools* (NCEE 2009-4060). Institute of Education Sciences.
