import sys
import time
import cv2
import mediapipe as mp

mp_hands = mp.solutions.hands
hands = mp_hands.Hands(
    static_image_mode=False,
    max_num_hands=1,
    min_detection_confidence=0.5,
    min_tracking_confidence=0.5,
)

cap = cv2.VideoCapture(0)
recent = []
window_size = 5
min_stable = 4
min_hold_ms = 500
last_event_at = None
last_hand_at = 0

def classify(points):
    if len(points) < 21:
        return "UNKNOWN"
    wrist = points[0]
    def dist(a, b):
        return ((a[0]-b[0])**2 + (a[1]-b[1])**2) ** 0.5
    extended = 0
    for tip, pip in ((8,6),(12,10),(16,14),(20,18)):
        tip_dist = dist(points[tip], wrist)
        pip_dist = dist(points[pip], wrist)
        if pip_dist <= 0.0001:
            continue
        if tip_dist > pip_dist * 1.20:
            extended += 1
    if extended >= 3:
        return "OPEN"
    if extended <= 1:
        return "FIST"
    return "UNKNOWN"

pose = "UNKNOWN"
while True:
    ok, frame = cap.read()
    if not ok:
        time.sleep(0.1)
        continue
    now = time.time() * 1000.0
    rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
    result = hands.process(rgb)
    if not result.multi_hand_landmarks:
        if last_hand_at and now - last_hand_at > 800:
            last_hand_at = 0
            recent.clear()
            pose = "UNKNOWN"
        time.sleep(0.1)
        continue
    last_hand_at = now
    lm = result.multi_hand_landmarks[0].landmark
    points = [(p.x, p.y, p.z) for p in lm]
    recent.append(classify(points))
    while len(recent) > window_size:
        recent.pop(0)
    if len(recent) < window_size:
        time.sleep(0.1)
        continue
    fists = recent.count("FIST")
    opens = recent.count("OPEN")
    decided = "FIST" if fists >= min_stable else "OPEN" if opens >= min_stable else "UNKNOWN"
    if decided == "UNKNOWN" or decided == pose:
        time.sleep(0.1)
        continue
    previous = pose
    pose = decided
    etype = None
    if previous == "OPEN" and decided == "FIST":
        etype = "GRAB"
    elif previous == "FIST" and decided == "OPEN":
        etype = "RELEASE"
    if etype and (last_event_at is None or now - last_event_at >= min_hold_ms):
        last_event_at = now
        print(etype, flush=True)
    time.sleep(0.1)
