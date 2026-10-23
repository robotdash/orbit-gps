"""Generate the Autogps voice prompt clips with Kokoro voice af_heart (Voice 4).

Each clip is one phrase. The app strings clips together, e.g.
  in_500_m + use_right_2_lanes + then + turn_right
Output: clips/<id>.ogg (Ogg Vorbis, mono, 24 kHz, q5) plus clips/manifest.json.
Copy them into app/src/main/assets/voice/.

  python3 make_clips.py                   # every clip
  python3 make_clips.py continue_for_     # only clips whose id starts with this
Needs: pip install kokoro-onnx soundfile; ffmpeg with libvorbis; the model files
kokoro-v1.0.onnx and voices-v1.0.bin in ~/kokoro (kokoro-onnx GitHub releases).
"""
import json, os, subprocess, sys
import soundfile as sf
from kokoro_onnx import Kokoro

VOICE = "af_heart"
SPEED = 1.0
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "clips")
TMP = os.path.join(HERE, "tmp")

ONES = "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split()
TENS = "_ _ twenty thirty forty fifty sixty seventy eighty ninety".split()

def num(n):
    """Spoken number: 1.5 -> "one and a half", 120 -> "one hundred twenty"."""
    if n != int(n):
        assert n * 2 == int(n * 2), n
        return num(int(n)) + " and a half"
    n = int(n)
    if n < 20:
        return ONES[n]
    if n < 100:
        return TENS[n // 10] + ("" if n % 10 == 0 else "-" + ONES[n % 10])
    rest = n % 100
    return ONES[n // 100] + " hundred" + ("" if rest == 0 else " " + num(rest))

def clip_id(x):
    """1.5 -> "1_5", 20 -> "20"."""
    return str(x).replace(".", "_") if x != int(x) else str(int(x))

CLIPS = {}

# --- Distance lead-ins (said before an action) ---
for km in (3, 2):
    CLIPS[f"in_{km}_km"] = f"In {num(km)} kilometres,"
CLIPS["in_1_5_km"] = "In one and a half kilometres,"
CLIPS["in_1_km"] = "In one kilometre,"
for m in (800, 600, 500, 400, 300, 200, 150, 100, 50):
    CLIPS[f"in_{m}_m"] = f"In {num(m)} metres,"
CLIPS["now"] = "Now,"
CLIPS["then"] = "then,"
CLIPS["and_then"] = "And then,"
CLIPS["immediately"] = "and immediately,"

# --- Turns ---
for side in ("right", "left"):
    CLIPS[f"turn_{side}"] = f"turn {side}."
    CLIPS[f"bear_{side}"] = f"bear {side}."
    CLIPS[f"sharp_{side}"] = f"make a sharp {side}."
    CLIPS[f"keep_{side}"] = f"keep {side}."
    CLIPS[f"stay_{side}"] = f"stay {side}."
    CLIPS[f"next_{side}"] = f"take the next {side}."
    for i, o in enumerate(("first", "second", "third", "fourth"), 1):
        CLIPS[f"take_{i}_{side}"] = f"take the {o} {side}."
    CLIPS[f"end_of_road_{side}"] = f"at the end of the road, turn {side}."
    CLIPS[f"exit_{side}"] = f"take the exit on the {side}."
    CLIPS[f"ramp_{side}"] = f"take the ramp on the {side}."
    CLIPS[f"merge_{side}"] = f"merge {side}."
    CLIPS[f"fork_{side}"] = f"at the fork, keep {side}."
    CLIPS[f"turn_{side}_then_{side}"] = f"turn {side}, then {side} again."
CLIPS["take_exit"] = "take the exit."
CLIPS["merge_highway"] = "merge onto the highway."
CLIPS["continue_straight"] = "continue straight."
CLIPS["straight_through"] = "go straight through the intersection."
CLIPS["u_turn"] = "make a U-turn."
CLIPS["u_turn_possible"] = "make a U-turn when possible."

# --- Lanes ---
CLIPS["use_right_lane"] = "use the right lane."
CLIPS["use_left_lane"] = "use the left lane."
CLIPS["use_right_2_lanes"] = "use the right two lanes."
CLIPS["use_left_2_lanes"] = "use the left two lanes."
CLIPS["use_middle_lane"] = "use the middle lane."
CLIPS["use_middle_lanes"] = "use the middle lanes."
CLIPS["use_any_lane"] = "use any lane."
CLIPS["stay_right_lane"] = "stay in the right lane."
CLIPS["stay_left_lane"] = "stay in the left lane."
CLIPS["right_lane_ends"] = "the right lane ends."
CLIPS["left_lane_ends"] = "the left lane ends."
CLIPS["to_turn_right"] = "to turn right."
CLIPS["to_turn_left"] = "to turn left."
CLIPS["to_take_exit"] = "to take the exit."
CLIPS["to_stay_on_highway"] = "to stay on the highway."

# --- Roundabouts ---
CLIPS["enter_roundabout"] = "enter the roundabout."
for i, o in enumerate(("first", "second", "third", "fourth", "fifth"), 1):
    CLIPS[f"roundabout_exit_{i}"] = f"at the roundabout, take the {o} exit."
CLIPS["exit_roundabout"] = "exit the roundabout."

# --- Long stretches ---
# Keep in step with CONTINUE_KM in voice/PromptEngine.kt.
CONTINUE_KM = (1, 1.5, 2, 2.5, 3, 3.5, 4, 5, 6, 7, 8, 9, 10, 12, 15, 20, 25, 30, 40, 50,
               60, 70, 80, 90, 100, 120, 150, 200)
for km in CONTINUE_KM:
    unit = "kilometre" if km == 1 else "kilometres"
    CLIPS[f"continue_for_{clip_id(km)}_km"] = f"Continue on this road for {num(km)} {unit}."
CLIPS["follow_road"] = "Follow the road."

# --- Destination ---
CLIPS["dest_ahead"] = "Your destination is ahead."
CLIPS["dest_ahead_left"] = "Your destination is ahead, on the left."
CLIPS["dest_ahead_right"] = "Your destination is ahead, on the right."
CLIPS["dest_on_left"] = "Your destination is on your left."
CLIPS["dest_on_right"] = "Your destination is on your right."
CLIPS["arrived"] = "You have arrived at your destination."
CLIPS["arriving"] = "Arriving at destination."
CLIPS["stop_reached"] = "You have reached your stop."

# --- System ---
CLIPS["route_start"] = "Starting route. Drive to the highlighted route."
CLIPS["drive_to_route"] = "Drive to the highlighted route."
CLIPS["recalculating"] = "Recalculating."
CLIPS["route_recalculated"] = "Route recalculated."
CLIPS["faster_route"] = "A faster route is available."
CLIPS["gps_lost"] = "GPS signal lost."
CLIPS["gps_restored"] = "GPS signal restored."
CLIPS["ferry_ahead"] = "Ferry ahead."
CLIPS["board_ferry"] = "Board the ferry."
CLIPS["toll_ahead"] = "Toll road ahead."
CLIPS["speed_limit_changes"] = "The speed limit is changing."
CLIPS["over_speed"] = "Please check your speed."

CLIPS["stop_passed"] = "You passed your stop. Continuing to your destination."
CLIPS["stop_removed"] = "Stop removed."
CLIPS["stop_going_back"] = "Okay, going back to your stop."

def main():
    os.makedirs(OUT, exist_ok=True); os.makedirs(TMP, exist_ok=True)
    kdir = os.path.expanduser("~/kokoro")
    k = Kokoro(os.path.join(kdir, "kokoro-v1.0.onnx"), os.path.join(kdir, "voices-v1.0.bin"))
    only = sys.argv[1] if len(sys.argv) > 1 else ""
    mpath = os.path.join(OUT, "manifest.json")
    manifest = json.load(open(mpath)) if only and os.path.exists(mpath) else {}
    for cid, text in CLIPS.items():
        if not cid.startswith(only):
            continue
        # Capitalise for the model; punctuation keeps natural intonation.
        say = text[0].upper() + text[1:]
        samples, sr = k.create(say, voice=VOICE, speed=SPEED, lang="en-us")
        wav = os.path.join(TMP, cid + ".wav")
        sf.write(wav, samples, sr)
        out = os.path.join(OUT, cid + ".ogg")
        # Trim leading/trailing silence, leave a tiny tail, encode Ogg Vorbis
        # (some phones' MediaPlayer can't play Opus-in-Ogg; see 1.1).
        flt = ("silenceremove=start_periods=1:start_threshold=-45dB,"
               "areverse,silenceremove=start_periods=1:start_threshold=-45dB,areverse,"
               "apad=pad_dur=0.06")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-af", flt,
                        "-ac", "1", "-ar", "24000", "-c:a", "libvorbis", "-q:a", "5", out], check=True)
        manifest[cid] = text
        print(cid, "->", text)
    with open(mpath, "w") as f:
        json.dump(manifest, f, indent=1, ensure_ascii=False)
    print(len(manifest), "clips")

if __name__ == "__main__":
    main()
