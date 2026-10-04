"""Author original calendar artwork and independent vector keyframe tracks."""
from pathlib import Path
import json
import math

ROOT = Path(__file__).resolve().parents[2]
END = 168

def prop(value): return {"a": 0, "k": value}
def track(points):
    keys = []
    for index, (time, value) in enumerate(points):
        start = [value] if isinstance(value, (dict, int, float)) else value
        key = {"t": time, "s": start}
        if index + 1 < len(points):
            end = points[index + 1][1]
            key.update(e=[end] if isinstance(end, (dict, int, float)) else end,
                       o={"x": .28, "y": 0}, i={"x": .65, "y": 1})
        else: key["h"] = 1
        keys.append(key)
    return {"a": 1, "k": keys}
def color(value): return [int(value[i:i + 2], 16) / 255 for i in (0, 2, 4)] + [1]
def fill(value, opacity=100):
    return {"ty": "fl", "nm": "Fill", "c": prop(color(value)), "o": prop(opacity), "r": 1}
def stroke(value, width=1, opacity=100):
    return {"ty": "st", "nm": "Edge", "c": prop(color(value)), "o": prop(opacity), "w": prop(width), "lc": 2, "lj": 2, "ml": 4}
def gradient(first, second, start, end):
    return {"ty": "gf", "nm": "Paper light", "o": prop(100), "r": 1, "t": 1, "s": prop(start), "e": prop(end),
            "g": {"p": 2, "k": prop([0] + color(first)[:3] + [1] + color(second)[:3])}}
def rect(x, y, w, h, radius):
    return {"ty": "rc", "nm": "Rounded shape", "d": 1, "p": prop([x, y]), "s": prop([w, h]), "r": prop(radius)}
def ellipse(x, y, w, h):
    return {"ty": "el", "nm": "Soft ellipse", "d": 1, "p": prop([x, y]), "s": prop([w, h])}
def path(vertices, incoming=None, outgoing=None, closed=True):
    return {"v": vertices, "i": incoming or [[0, 0] for _ in vertices], "o": outgoing or [[0, 0] for _ in vertices], "c": closed}
def shape(value, animated=False):
    return {"ty": "sh", "nm": "Paper contour", "ks": value if animated else prop(value)}
def transform(anchor=(0, 0, 0), position=(0, 0, 0), rotation=0, opacity=100, scale=(100, 100, 100)):
    return {"a": prop(list(anchor)), "p": position if isinstance(position, dict) else prop(list(position)),
            "r": rotation if isinstance(rotation, dict) else prop(rotation),
            "o": opacity if isinstance(opacity, dict) else prop(opacity),
            "s": scale if isinstance(scale, dict) else prop(list(scale))}
def page_contour(curl):
    size = 3 + 25 * curl
    return path([[26, 55], [104, 55], [104, 109 - size], [104 - size, 109], [34, 109], [26, 101]],
                [[0, 0], [0, 0], [0, -2], [2 + curl * 3, -2], [4, 0], [0, 4]],
                [[0, 0], [0, 0], [-2, 2 + curl * 4], [-2, 0], [-4, 0], [0, -4]])
def curl_contour(curl):
    size = 3 + 25 * curl
    return path([[104 - size, 109], [104 - size + curl * 3, 109 - size + curl * 3], [104, 109 - size]],
                [[curl * 3, -curl * 3], [-curl * 2, curl * 2], [-curl * 2, -curl * 4]],
                [[-curl * 2, -curl * 5], [curl * 2, -curl * 2], [0, 0]])

# A full sheet turns over the binding, then tucks behind the cover.
TURN_LANDMARKS = [(0, 0), (10, 7), (27, 32), (46, 76), (57, 105),
        (72, 151), (91, 215), (110, 293), (125, 360), (END, 360)]

def continuous_turn(time):
    if time >= 125: return 360
    points = TURN_LANDMARKS[:-1]
    slopes = [0] + [(points[i + 1][1] - points[i - 1][1]) / (points[i + 1][0] - points[i - 1][0])
                    for i in range(1, len(points) - 1)] + [0]
    for index in range(len(points) - 1):
        begin, finish = points[index], points[index + 1]
        if begin[0] <= time <= finish[0]:
            span = finish[0] - begin[0]
            t = (time - begin[0]) / span
            return ((2*t**3 - 3*t**2 + 1) * begin[1] + (t**3 - 2*t**2 + t) * span * slopes[index]
                    + (-2*t**3 + 3*t**2) * finish[1] + (t**3 - t**2) * span * slopes[index + 1])

TURN = [(time, continuous_turn(time)) for time in list(range(0, 125, 3)) + [125, END]]

def flowing_track(points):
    result = track(points)
    for key in result["k"][:-1]:
        # Keep velocity through intermediate shape samples, instead of easing to
        # a stop at each one. The continuous turn owns anticipation and settling.
        key["o"] = {"x": 1/3, "y": 1/3}
        key["i"] = {"x": 2/3, "y": 2/3}
    return result

def leaf_point(x, y, angle):
    theta = math.radians(angle)
    lift = math.sin(theta)
    depth = y - 64
    # The right edge leads; a curved sheet stays attached at the spine.
    twist = -lift * (x - 65) / 39 * 7 * depth / 48
    width = 1 + .055 * abs(lift) * depth / 48
    return [65 + (x - 65) * width + lift * 2 * depth / 48,
            64 + depth * math.cos(theta) + twist]

def leaf_contour(angle):
    vertices = [[26, 64], [104, 64], [104, 104], [96, 112], [34, 112], [26, 104]]
    incoming = [[0, 0], [0, 0], [0, -4.418], [4.418, 0], [4.418, 0], [0, 4.418]]
    outgoing = [[0, 0], [0, 0], [0, 4.418], [-4.418, 0], [-4.418, 0], [0, -4.418]]
    mapped_in, mapped_out = [], []
    for vertex, before, after in zip(vertices, incoming, outgoing):
        at = leaf_point(*vertex, angle)
        for tangent, destination in ((before, mapped_in), (after, mapped_out)):
            endpoint = leaf_point(vertex[0] + tangent[0], vertex[1] + tangent[1], angle)
            destination.append([endpoint[0] - at[0], endpoint[1] - at[1]])
    return path([leaf_point(*vertex, angle) for vertex in vertices], mapped_in, mapped_out)

def author(night):
    p = dict(paper="DCE5EE", paper_low="B8CBDF", underside="88A7C4", cover="3F658B", cover_low="284969",
             edge="7495B7", ink="52789C", warm="C19A65", ring="DDE8F2", ring_edge="3D5D7F", shadow="5B83AE") if night else dict(
             paper="FFFDF6", paper_low="E9F0F6", underside="C1D6E8", cover="789FC2", cover_low="47789F",
             edge="446B8E", ink="7D9EB7", warm="DAB079", ring="FFFFFF", ring_edge="557A9A", shadow="6789A8")
    layers = []
    def layer(name, contents, parent=900, **kwargs):
        entry = {"ddd": 0, "ind": len(layers) + 1, "ty": 4, "nm": name, "sr": 1, "ks": transform(**kwargs),
                 "ao": 0, "shapes": contents, "ip": 0, "op": END, "st": 0, "bm": 0}
        if parent is not None: entry["parent"] = parent
        layers.append(entry)
    leaf = flowing_track([(time, leaf_contour(angle)) for time, angle in TURN])
    front_opacity = track([(0, 100), (47, 100), (54, 0), (END, 0)])
    rear_opacity = track([(0, 0), (47, 0), (54, 100), (END, 100)])
    # Ground shadows stay planted while the weighted cover lifts and settles.
    layer("Ambient shadow", [ellipse(66, 119, 81, 5), fill(p["shadow"], 5)], parent=None)
    layer("Contact shadow", [ellipse(66, 117.6, 67, 3.5), fill(p["shadow"], 12)], parent=None,
          opacity=track([(0, 100), (16, 92), (43, 40), (94, 76), (129, 100), (END, 100)]),
          anchor=(66, 117.6, 0), position=(66, 117.6, 0),
          scale=track([(0, [100, 100, 100]), (43, [85, 70, 100]), (102, [96, 85, 100]),
                       (129, [103, 106, 100]), (151, [100, 100, 100]), (END, [100, 100, 100])]))
    # The same sheet moves behind the cover when it crosses the spine.
    layer("Paper behind binding", [shape(leaf, True), gradient(p["paper_low"], p["underside"], [29, 19], [101, 65]),
          stroke(p["edge"], .8, 25)], opacity=rear_opacity)
    layer("Cover", [rect(65, 74, 85, 84, 11), gradient(p["cover"], p["cover_low"], [26, 33], [105, 116]), stroke(p["edge"], .9, 35)])
    layer("Cover edge light", [shape(path([[108, 47], [108, 102], [105, 109]], closed=False)), stroke(p["ring"], .9, 24)])
    layer("Back pages", [rect(66, 79, 79, 71, 8), fill(p["paper_low"]), stroke(p["edge"], .85, 22)])
    layer("Bookmark", [shape(path([[101, 48], [112, 48], [112, 80], [106.5, 75.5], [101, 80]])),
          gradient(p["warm"], p["warm"], [103, 48], [111, 80]), stroke(p["edge"], .6, 12)],
          anchor=(106.5, 48, 0), position=(106.5, 48, 0),
          rotation=track([(0, 0), (16, -3), (44, 9), (75, -7), (109, 4), (137, -1.8), (159, .35), (165, 0), (END, 0)]))
    header = path([[35.5, 33], [94.5, 33], [104, 42.5], [104, 64], [26, 64], [26, 42.5]],
                  [[-5.247, 0], [0, 0], [0, -5.247], [0, 0], [0, 0], [0, 0]],
                  [[0, 0], [5.247, 0], [0, 0], [0, 0], [0, 0], [0, -5.247]])
    layer("Header", [shape(header), gradient(p["cover"], p["cover_low"], [36, 34], [104, 64])])
    layer("Header rim", [shape(path([[36, 34.5], [94, 34.5]], closed=False)), stroke(p["ring"], 1, 40)])
    layer("Fresh page", [shape(leaf_contour(0)), gradient(p["paper"], p["paper_low"], [30, 65], [105, 116]), stroke(p["edge"], .85, 28)])
    for index, x in enumerate((42, 64, 86)):
        layer(f"Fresh date {index + 1}", [rect(x, 81, 10, 6, 2.2), fill(p["ink"], 62)],
              opacity=track([(0, 100), (58 + index * 2, 100), (83 + index * 2, 72), (127 + index * 2, 100), (END, 100)]))
    layer("Fresh note", [shape(path([[38, 99], [72, 99]], closed=False)), stroke(p["ink"], 2.8, 50)])
    for index, opacity in enumerate((4, 7, 10)):
        layer(f"Turning shadow {index + 1}", [ellipse(65, 92, 70 - index * 7, 12 - index * 2), fill(p["edge"], opacity)],
              anchor=(65, 92, 0), position=track([(0, [65, 106, 0]), (22, [65, 99, 0]), (45, [65, 78, 0]), (57, [65, 67, 0]), (END, [65, 67, 0])]),
              opacity=track([(0, 0), (15, 42), (34, 100), (49, 40), (57, 0), (END, 0)]),
              scale=track([(0, [100, 35, 100]), (29, [98, 100, 100]), (51, [95, 40, 100]), (END, [95, 40, 100])]))
    layer("Turning front page", [shape(leaf, True), gradient(p["paper"], p["paper_low"], [32, 63], [105, 114]), stroke(p["edge"], .85, 28)], opacity=front_opacity)
    for name, points, width, opacity in (("First old note", [[39, 82], [73, 82]], 3.1, 85),
                                          ("Second old note", [[39, 94], [59, 94]], 2.8, 55)):
        note = flowing_track([(time, path([leaf_point(*point, angle) for point in points], closed=False)) for time, angle in TURN])
        layer(name, [shape(note, True), stroke(p["ink"], width, opacity)], opacity=front_opacity)
    edge_track = flowing_track([(time, path([leaf_point(x, y, angle) for x, y in ((96, 112), (34, 112))], closed=False)) for time, angle in TURN])
    layer("Turning edge light", [shape(edge_track, True), stroke(p["ring"], .85, 60)], opacity=front_opacity)
    # Stable bindings keep the page turn grounded. The cover moves underneath them.
    for x, suffix in ((44, "left"), (85, "right")):
        layer(f"Binding shadow {suffix}", [rect(x + .8, 37, 6.6, 21, 3.3), fill(p["ring_edge"], 42)])
        layer(f"Binding {suffix}", [rect(x, 35, 5.2, 22, 2.6), gradient(p["ring"], p["paper_low"], [x - 2, 25], [x + 2, 46]), stroke(p["ring_edge"], .85, 55)])
        layer(f"Binding light {suffix}", [shape(path([[x - 1.1, 28], [x - 1.1, 38]], closed=False)), stroke(p["ring"], .8, 65)])
    rig = {"ddd": 0, "ind": 900, "ty": 3, "nm": "Weighted calendar", "sr": 1, "ao": 0,
           "ks": transform(anchor=(65, 76, 0), position=track([(0, [65, 76, 0]), (12, [65, 77.5, 0]), (40, [65, 69, 0]),
                    (70, [65, 70.5, 0]), (102, [65, 72.5, 0]), (128, [65, 76.7, 0]), (148, [65, 75.6, 0]), (162, [65, 76, 0]), (END, [65, 76, 0])]),
                    rotation=track([(0, 0), (12, -2.8), (39, 5.8), (74, -4.5), (107, 2.4), (131, -1.1), (149, .4), (162, 0), (END, 0)])),
           "ip": 0, "op": END, "st": 0, "bm": 0}
    return {"v": "5.7.4", "fr": 60, "ip": 0, "op": END, "w": 128, "h": 128, "ddd": 0,
            "nm": "A new page", "assets": [], "layers": [rig] + list(reversed(layers)),
            "markers": [{"tm": 0, "cm": "One page turn", "dr": END}]}

if __name__ == "__main__":
    for night in (False, True):
        target = ROOT / "app/src/main/res" / ("raw-night" if night else "raw") / "empty_calendar.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        composition = author(night)
        target.write_text(json.dumps(composition, separators=(",", ":")), encoding="utf-8")
        print(target, len(composition["layers"]), target.stat().st_size)
