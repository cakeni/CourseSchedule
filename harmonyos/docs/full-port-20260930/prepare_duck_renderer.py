"""Prepare the original Rive asset for deterministic frame export (build tool only)."""
from pathlib import Path
import argparse
import shutil

args = argparse.ArgumentParser()
args.add_argument('--runtime-js', type=Path, required=True)
args.add_argument('--wasm', type=Path, required=True)
options = args.parse_args()
root = Path(__file__).resolve().parents[3]
output = root / 'harmonyos/entry/build/validation/rive'
output.mkdir(parents=True, exist_ok=True)
shutil.copyfile(options.runtime_js, output / 'rive.js')
shutil.copyfile(options.wasm, output / 'rive.wasm')
shutil.copyfile(root / 'app/src/main/res/raw/course_duck.riv', output / 'course_duck.riv')
(output / 'index.html').write_text('''<!doctype html><html><body style="margin:0;background:transparent">
<canvas id="duck" width="192" height="192" style="width:192px;height:192px"></canvas>
<script src="rive.js"></script><script>
rive.RuntimeLoader.setWasmUrl('/rive.wasm');
fetch('/course_duck.riv').then(r => r.arrayBuffer()).then(buffer => {
  window.duck = new rive.Rive({buffer,canvas:document.getElementById('duck'),artboard:'Artboard',
    autoplay:true,stateMachines:'State Machine 1',onLoad:() => window.loaded=true});
});
</script></body></html>''', encoding='utf-8')
print(output)
