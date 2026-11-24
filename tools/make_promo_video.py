import subprocess
import time
import os
import sys

ADB = "adb"
FONT = "/System/Library/Fonts/STHeiti Medium.ttc"
OUT_DIR = "/tmp/parley_video_build"
FINAL_OUT = "/Users/actor/Documents/code/kotlin-parley/promo_parley_demo.mp4"

os.makedirs(OUT_DIR, exist_ok=True)

def run(cmd_str):
    print(f"RUN: {cmd_str}")
    r = subprocess.run(cmd_str, shell=True, capture_output=True, text=True)
    if r.returncode != 0:
        print(f"ERROR: {r.stderr}")
    return r

print("=== 1. Generating Audio Narrations ===")
# Audio clips
run(f'say -r 195 -v Tingting "社恐想练英语口语，真的不用找真人外教。" -o {OUT_DIR}/narr1.aiff')
run(f'say -r 170 -v Samantha "Hi! How’s your day going so far?" -o {OUT_DIR}/user_en.aiff')
run(f'say -r 195 -v Tingting "随时长按说话，松手即刻对练，像发语音一样自然。" -o {OUT_DIR}/narr2.aiff')
run(f'say -r 195 -v Tingting "聊完自动提炼地道表达，一键存进生词本。" -o {OUT_DIR}/narr3.aiff')
run(f'say -r 195 -v Tingting "自由开口，自然习得。快来开启你的专属口语搭子吧。" -o {OUT_DIR}/narr4.aiff')

# Convert to wav
for name in ["narr1", "user_en", "narr2", "narr3", "narr4"]:
    run(f"ffmpeg -y -i {OUT_DIR}/{name}.aiff {OUT_DIR}/{name}.wav")

print("=== 2. Assembling Master Audio Track ===")
# narr1 starts at 0.5s (500ms)
# user_en starts at 3.8s (3800ms)
# narr2 starts at 6.3s (6300ms)
# narr3 starts at 10.2s (10200ms)
# narr4 starts at 13.8s (13800ms)
# Total timeline: 18.0s
filter_complex = (
    "[0:a]adelay=500|500[a0]; "
    "[1:a]adelay=3800|3800[a1]; "
    "[2:a]adelay=6300|6300[a2]; "
    "[3:a]adelay=10200|10200[a3]; "
    "[4:a]adelay=13800|13800[a4]; "
    "[a0][a1][a2][a3][a4]amix=inputs=5:duration=longest:dropout_transition=0[outa]"
)

cmd_audio = (
    f"ffmpeg -y "
    f"-i {OUT_DIR}/narr1.wav "
    f"-i {OUT_DIR}/user_en.wav "
    f"-i {OUT_DIR}/narr2.wav "
    f"-i {OUT_DIR}/narr3.wav "
    f"-i {OUT_DIR}/narr4.wav "
    f"-filter_complex \"{filter_complex}\" -map \"[outa]\" "
    f"-t 18 -c:a aac -b:a 192k {OUT_DIR}/master_audio.aac"
)
run(cmd_audio)

print("=== 3. Executing Synchronized Screen Recording ===")
# Setup phone initial screen: ensure on chat screen
run(f"{ADB} shell input tap 114 186")
time.sleep(0.5)
run(f"{ADB} shell input tap 114 186")
time.sleep(0.5)

# Start screen recording with 18 seconds time-limit
print("Starting adb screenrecord (18s)...")
rec_p = subprocess.Popen([ADB, "shell", "screenrecord", "--time-limit", "18", "--bit-rate", "10000000", "/sdcard/promo_raw.mp4"])

# 0s - 3.5s: on chat screen
time.sleep(3.5)

# 3.5s - 6.0s: hold record button
print("Action: Holding record button...")
run(f"{ADB} shell input swipe 987 2226 987 2226 2300")
time.sleep(3.8) # until ~7.3s

# 7.3s - 9.8s: view conversation
time.sleep(2.5)

# 9.8s: Tap vocabulary library
print("Action: Opening vocabulary library...")
run(f"{ADB} shell input tap 972 186")
time.sleep(1.2) # ~11.0s

# 11.0s: Scroll vocabulary
print("Action: Scrolling vocabulary library...")
run(f"{ADB} shell input swipe 540 1600 540 900 600")
time.sleep(2.0) # ~13.0s

# 13.0s: Tap back to chat
print("Action: Back to chat...")
run(f"{ADB} shell input tap 114 189")
time.sleep(1.0) # ~14.0s

# 14.0s: Open session drawer
print("Action: Open sessions drawer...")
run(f"{ADB} shell input tap 114 186")

print("Waiting for screenrecord to complete...")
rec_p.wait()
time.sleep(1.5)

print("=== 4. Pulling Raw Video ===")
run(f"{ADB} pull /sdcard/promo_raw.mp4 {OUT_DIR}/promo_raw.mp4")
run(f"{ADB} shell rm /sdcard/promo_raw.mp4")

if not os.path.exists(f"{OUT_DIR}/promo_raw.mp4"):
    print("FATAL: Failed to pull raw video")
    sys.exit(1)

print("=== 5. Post-Processing & Rendering Final Video ===")
# We format to 1080x1920 (9:16) with top & bottom branding banners
# Video filter chain:
# 1. scale to width 1080, preserve aspect ratio or pad
# 2. Add top banner: "告别社恐 · 随时随地的 AI 口语搭子"
# 3. Add dynamic subtitle banner based on time
vf = (
    "scale=1080:1920:force_original_aspect_ratio=decrease,"
    "pad=1080:1920:(ow-iw)/2:(oh-ih)/2:color=black,"
    f"drawbox=y=0:color=black@0.9:width=iw:height=140:t=fill,"
    f"drawtext=fontfile='{FONT}':text='告别社恐 · 随时随地的 AI 口语搭子':fontcolor=white:fontsize=46:x=(w-text_w)/2:y=48,"
    f"drawbox=y=ih-140:color=black@0.9:width=iw:height=140:t=fill,"
    f"drawtext=fontfile='{FONT}':text='Parley · 自由开口 自然习得':fontcolor=white:fontsize=40:x=(w-text_w)/2:y=ih-95"
)

cmd_final = (
    f"ffmpeg -y -i {OUT_DIR}/promo_raw.mp4 -i {OUT_DIR}/master_audio.aac "
    f"-filter_complex \"[0:v]{vf}[outv]\" "
    f"-map \"[outv]\" -map 1:a "
    f"-c:v libx264 -preset fast -crf 20 -c:a aac -b:a 192k -shortest "
    f"{FINAL_OUT}"
)
run(cmd_final)

if os.path.exists(FINAL_OUT):
    size_mb = os.path.getsize(FINAL_OUT) / (1024 * 1024)
    print(f"SUCCESS: Generated {FINAL_OUT} ({size_mb:.2f} MB)")
else:
    print("ERROR: Final video generation failed")
